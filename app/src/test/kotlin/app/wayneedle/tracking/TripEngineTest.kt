package app.wayneedle.tracking

import app.wayneedle.domain.model.TripCheckpoint
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripStats
import app.wayneedle.testing.latitudeAfterNorth
import app.wayneedle.testing.longitudeAfterEast
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripEngineTest {
    private val engine = TripEngine()
    private var latitude = 52.0
    private var longitude = 4.0

    private fun sec(seconds: Double): Long = (seconds * 1e9).toLong()

    private fun fixAt(
        seconds: Double,
        speedMps: Float = 10f,
        accuracyM: Float = 5f,
        lat: Double = latitude,
        lon: Double = longitude,
        corroboration: Corroboration = Corroboration.PREVIOUS_FIX,
        altitudeM: Double? = null,
        verticalAccuracyM: Float? = null,
    ) = AcceptedFix(lat, lon, accuracyM, speedMps, sec(seconds), corroboration, altitudeM, verticalAccuracyM)

    /** Moves north by [meters] and feeds a fix at [seconds]. */
    private fun moveAndFix(seconds: Double, meters: Double, speedMps: Float = 10f, accuracyM: Float = 5f): Double {
        latitude = latitudeAfterNorth(latitude, meters)
        return engine.onAcceptedFix(fixAt(seconds, speedMps, accuracyM))
    }

    @Test
    fun `valid transitions follow the documented state machine`() {
        assertTrue(engine.start("trip-1", sec(0.0), 1_000L))
        assertEquals(TripPhase.RUNNING, engine.phase)
        assertTrue(engine.pause(sec(1.0)))
        assertTrue(engine.resume(sec(2.0)))
        assertTrue(engine.finish(sec(3.0)))
        assertEquals(TripPhase.FINISHED, engine.phase)
        assertTrue(engine.dismissFinished())
        assertEquals(TripPhase.IDLE, engine.phase)
        assertTrue(engine.start("trip-2", sec(4.0), 2_000L))
        assertTrue(engine.discard())
        assertEquals(TripPhase.IDLE, engine.phase)
        assertNull(engine.tripId)
    }

    @Test
    fun `invalid transitions are rejected without changing anything`() {
        assertFalse(engine.pause(sec(0.0)))
        assertFalse(engine.resume(sec(0.0)))
        assertFalse(engine.finish(sec(0.0)))
        assertFalse(engine.discard())
        assertFalse(engine.dismissFinished())
        assertEquals(TripPhase.IDLE, engine.phase)

        engine.start("trip", sec(0.0), 1_000L)
        assertFalse(engine.start("other", sec(1.0), 2_000L))
        assertFalse(engine.resume(sec(1.0)))
        assertEquals("trip", engine.tripId)
        assertEquals(1_000L, engine.startedAtEpochMillis)

        engine.pause(sec(2.0))
        assertFalse(engine.pause(sec(3.0)))
        assertEquals(2_000L, engine.activeDurationMillis(sec(10.0)))

        engine.finish(sec(4.0))
        assertFalse(engine.finish(sec(5.0)))
        assertFalse(engine.pause(sec(5.0)))
        assertFalse(engine.resume(sec(5.0)))
        assertFalse(engine.discard())
        assertEquals(TripPhase.FINISHED, engine.phase)
    }

    @Test
    fun `active duration only counts running intervals`() {
        engine.start("trip", sec(100.0), 0L)
        assertEquals(10_000L, engine.activeDurationMillis(sec(110.0)))
        engine.pause(sec(110.0))
        assertEquals(10_000L, engine.activeDurationMillis(sec(500.0)))
        engine.resume(sec(125.0))
        engine.pause(sec(130.5))
        assertEquals(15_500L, engine.activeDurationMillis(sec(1_000.0)))
        engine.resume(sec(200.0))
        engine.finish(sec(201.0))
        assertEquals(16_500L, engine.stats(sec(9_999.0)).activeDurationMillis)
    }

    @Test
    fun `first fix after start is only an anchor and clears awaitingFix`() {
        engine.start("trip", sec(0.0), 0L)
        assertTrue(engine.awaitingFix)
        assertEquals(0.0, moveAndFix(1.0, 10.0))
        assertFalse(engine.awaitingFix)
        assertEquals(10.0, moveAndFix(2.0, 10.0), 1e-3)
        assertEquals(10.0, engine.stats(sec(2.0)).distanceMeters, 1e-3)
    }

    @Test
    fun `distance accumulates between consecutive accepted fixes`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(1.0, 0.0)
        for (s in 2..101) moveAndFix(s.toDouble(), 25.0, speedMps = 25f)
        assertEquals(2_500.0, engine.stats(sec(101.0)).distanceMeters, 0.05)
        assertEquals(25f, engine.stats(sec(101.0)).maxSpeedMps)
    }

    @Test
    fun `stationary jitter with zero speed accumulates no distance`() {
        engine.start("trip", sec(0.0), 0L)
        val random = Random(7)
        val centerLat = latitude
        val centerLon = longitude
        for (s in 1..600) {
            val radius = random.nextDouble(0.0, 8.0)
            val angle = random.nextDouble(0.0, 2 * Math.PI)
            val lat = latitudeAfterNorth(centerLat, radius * cos(angle))
            val lon = longitudeAfterEast(lat, centerLon, radius * sin(angle))
            engine.onAcceptedFix(fixAt(s.toDouble(), speedMps = 0f, lat = lat, lon = lon))
        }
        assertEquals(0.0, engine.stats(sec(600.0)).distanceMeters)
        assertEquals(0f, engine.stats(sec(600.0)).maxSpeedMps)
    }

    @Test
    fun `jitter below the noise floor accumulates no distance even with speed noise`() {
        engine.start("trip", sec(0.0), 0L)
        val random = Random(11)
        val centerLat = latitude
        val centerLon = longitude
        for (s in 1..600) {
            // Within 2 m of a fixed point: any two positions are < 4 m apart, below the 5 m floor.
            val radius = random.nextDouble(0.0, 2.0)
            val angle = random.nextDouble(0.0, 2 * Math.PI)
            val lat = latitudeAfterNorth(centerLat, radius * cos(angle))
            val lon = longitudeAfterEast(lat, centerLon, radius * sin(angle))
            val speed = if (random.nextBoolean()) 0f else random.nextDouble(0.5, 0.9).toFloat()
            engine.onAcceptedFix(fixAt(s.toDouble(), speedMps = speed, lat = lat, lon = lon))
        }
        assertEquals(0.0, engine.stats(sec(600.0)).distanceMeters)
    }

    @Test
    fun `slow movement below the noise floor per fix is still counted`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(0.0, 0.0, speedMps = 1f)
        for (s in 1..20) moveAndFix(s.toDouble(), 1.0, speedMps = 1f)
        val distance = engine.stats(sec(20.0)).distanceMeters
        assertTrue(distance in 15.0..20.01, "walked 20 m, counted $distance")
    }

    @Test
    fun `plausible segment across a signal gap is counted once the next fix confirms it`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(1.0, 0.0, speedMps = 20f)
        // 60 s tunnel at 20 m/s, nearly straight. Its end is not cross-checked yet, so it waits.
        assertEquals(0.0, moveAndFix(61.0, 1_150.0, speedMps = 20f))
        assertEquals(1_170.0, moveAndFix(62.0, 20.0, speedMps = 20f), 0.01)
    }

    @Test
    fun `a tunnel followed by a red light still counts the tunnel`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(1.0, 0.0, speedMps = 20f)
        // GPS comes back while the car waits at a light right after the tunnel.
        assertEquals(0.0, moveAndFix(91.0, 1_500.0, speedMps = 0f))
        assertEquals(1_500.0, moveAndFix(92.0, 0.0, speedMps = 0f), 0.01)
        assertEquals(1_500.0, engine.stats(sec(92.0)).distanceMeters, 0.01)
    }

    @Test
    fun `parking in an underground garage counts the drive inside it`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(1.0, 0.0, speedMps = 4f)
        // 2 minutes and 300 m without GPS, then parked; the next fixes are parked too.
        moveAndFix(121.0, 300.0, speedMps = 0f)
        for (s in 122..180) moveAndFix(s.toDouble(), 0.0, speedMps = 0f)
        assertEquals(300.0, engine.stats(sec(180.0)).distanceMeters, 0.01)
    }

    @Test
    fun `a gap whose end is a glitch adds nothing`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(1.0, 0.0, speedMps = 10f)
        // After a 60 s gap the first fix is 2 km off with a plausible-looking speed...
        moveAndFix(61.0, 2_000.0, speedMps = 30f)
        // ...and the next honest fix is back where the car really is (600 m from the start).
        latitude = latitudeAfterNorth(latitude, -1_400.0)
        assertEquals(0.0, engine.onAcceptedFix(fixAt(62.0, speedMps = 10f)))
        assertEquals(0.0, engine.stats(sec(62.0)).distanceMeters)
    }

    @Test
    fun `stopping at normal fix rate after moving still keeps a parked phone from collecting distance`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(1.0, 0.0, speedMps = 1f)
        // One second later it reports 0 with 6 m of jitter: not a gap, so treated as parked.
        assertEquals(0.0, moveAndFix(2.0, 6.0, speedMps = 0f))
    }

    @Test
    fun `top speed is the speed held across two consecutive fixes`() {
        engine.start("trip", sec(0.0), 0L)
        engine.onAcceptedFix(fixAt(1.0, speedMps = 25f, corroboration = Corroboration.NONE))
        assertEquals(0f, engine.stats(sec(1.0)).maxSpeedMps, "one reading alone is not a record")
        latitude = latitudeAfterNorth(latitude, 24.0)
        engine.onAcceptedFix(fixAt(2.0, speedMps = 23f))
        assertEquals(23f, engine.stats(sec(2.0)).maxSpeedMps)
        latitude = latitudeAfterNorth(latitude, 24.0)
        engine.onAcceptedFix(fixAt(3.0, speedMps = 24f))
        assertEquals(23f, engine.stats(sec(3.0)).maxSpeedMps)
        latitude = latitudeAfterNorth(latitude, 25.0)
        engine.onAcceptedFix(fixAt(4.0, speedMps = 26f))
        assertEquals(24f, engine.stats(sec(4.0)).maxSpeedMps)
    }

    @Test
    fun `a single glitched reading never becomes the top speed`() {
        engine.start("trip", sec(0.0), 0L)
        // Parked; after a GPS gap one reading says 9 m/s, then the car is (still) standing.
        engine.onAcceptedFix(fixAt(1.0, speedMps = 0f))
        engine.onAcceptedFix(fixAt(60.0, speedMps = 9f, corroboration = Corroboration.NONE))
        engine.onAcceptedFix(fixAt(61.0, speedMps = 0f))
        engine.onAcceptedFix(fixAt(62.0, speedMps = 0f))
        assertEquals(0f, engine.stats(sec(62.0)).maxSpeedMps)
    }

    @Test
    fun `a glitch outvoted by the next fixes never becomes the top speed`() {
        engine.start("trip", sec(0.0), 0L)
        // First fix after a gap: a 60 m/s glitch (nothing to check it against).
        engine.onAcceptedFix(fixAt(1.0, speedMps = 60f, corroboration = Corroboration.NONE))
        // The next honest fix was rejected; the one after agreed with it and overruled the glitch.
        latitude = latitudeAfterNorth(latitude, 40.0)
        engine.onAcceptedFix(fixAt(3.0, speedMps = 20f, corroboration = Corroboration.REJECTED_FIX))
        latitude = latitudeAfterNorth(latitude, 20.0)
        engine.onAcceptedFix(fixAt(4.0, speedMps = 20f))
        assertEquals(20f, engine.stats(sec(4.0)).maxSpeedMps)
    }

    @Test
    fun `implausible jump re-anchors without adding distance`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(1.0, 0.0, speedMps = 10f)
        assertEquals(0.0, moveAndFix(11.0, 5_000.0, speedMps = 10f))
        assertEquals(10.0, moveAndFix(12.0, 10.0, speedMps = 10f), 1e-3)
        assertEquals(10.0, engine.stats(sec(12.0)).distanceMeters, 1e-3)
    }

    @Test
    fun `pause drops the anchor and fixes while paused change nothing`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(1.0, 0.0)
        moveAndFix(2.0, 10.0)
        engine.pause(sec(2.0))
        val frozen = engine.stats(sec(2.0))
        assertEquals(0.0, moveAndFix(3.0, 30.0, speedMps = 30f))
        assertEquals(0.0, moveAndFix(4.0, 30.0, speedMps = 30f))
        assertEquals(frozen, engine.stats(sec(50.0)))
        engine.resume(sec(5.0))
        assertTrue(engine.awaitingFix)
        // First fix after resume is an anchor: the 60 m covered while paused is never counted.
        assertEquals(0.0, moveAndFix(6.0, 10.0))
        assertFalse(engine.awaitingFix)
        assertEquals(10.0, moveAndFix(7.0, 10.0), 1e-3)
        assertEquals(20.0, engine.stats(sec(7.0)).distanceMeters, 1e-3)
        assertEquals(10f, engine.stats(sec(7.0)).maxSpeedMps)
    }

    @Test
    fun `signal loss makes the next fix a fresh anchor`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(1.0, 0.0)
        engine.onSignalLost()
        assertTrue(engine.awaitingFix)
        assertEquals(0.0, moveAndFix(30.0, 300.0))
        assertEquals(10.0, moveAndFix(31.0, 10.0), 1e-3)
    }

    @Test
    fun `average speed is distance over active duration`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(0.0, 0.0)
        for (s in 1..10) moveAndFix(s.toDouble(), 10.0)
        engine.pause(sec(10.0))
        engine.resume(sec(100.0))
        engine.pause(sec(110.0))
        val stats = engine.stats(sec(110.0))
        assertEquals(20_000L, stats.activeDurationMillis)
        assertEquals(stats.distanceMeters / 20.0, stats.averageSpeedMps.toDouble(), 1e-4)
    }

    @Test
    fun `restore brings a checkpoint back as paused and recovered`() {
        val checkpoint = TripCheckpoint(
            tripId = "abc",
            phase = TripPhase.RUNNING,
            startedAtEpochMillis = 5L,
            stats = TripStats(distanceMeters = 1_234.5, activeDurationMillis = 600_000L, maxSpeedMps = 31f),
            savedAtEpochMillis = 99L,
        )
        assertTrue(engine.restore(checkpoint))
        val state = engine.snapshot(sec(10_000.0))
        assertEquals(TripPhase.PAUSED, state.phase)
        assertTrue(state.recovered)
        assertEquals("abc", state.tripId)
        assertEquals(5L, state.startedAtEpochMillis)
        assertEquals(checkpoint.stats, state.stats)
        assertFalse(engine.restore(checkpoint))

        engine.resume(sec(10_000.0))
        assertFalse(engine.snapshot(sec(10_000.0)).recovered)
        assertEquals(601_000L, engine.activeDurationMillis(sec(10_001.0)))
    }

    @Test
    fun `restore rejects checkpoints that make no sense`() {
        val good = TripCheckpoint("id", TripPhase.PAUSED, 0L, TripStats(), 0L)
        assertFalse(TripEngine().restore(good.copy(phase = TripPhase.FINISHED)))
        assertFalse(TripEngine().restore(good.copy(phase = TripPhase.IDLE)))
        assertFalse(TripEngine().restore(good.copy(tripId = " ")))
        assertFalse(TripEngine().restore(good.copy(stats = TripStats(distanceMeters = -1.0))))
        assertFalse(TripEngine().restore(good.copy(stats = TripStats(distanceMeters = Double.NaN))))
        assertFalse(TripEngine().restore(good.copy(stats = TripStats(activeDurationMillis = -5L))))
        assertFalse(TripEngine().restore(good.copy(stats = TripStats(maxSpeedMps = Float.POSITIVE_INFINITY))))
        assertTrue(TripEngine().restore(good))
    }

    @Test
    fun `checkpoint exists only for running or paused trips`() {
        assertNull(engine.checkpoint(sec(0.0), 0L))
        engine.start("trip", sec(0.0), 7L)
        val running = engine.checkpoint(sec(3.0), 70L)
        assertEquals(
            TripCheckpoint(
                "trip",
                TripPhase.RUNNING,
                7L,
                TripStats(activeDurationMillis = 3_000L, movingDurationMillis = 0L),
                70L,
            ),
            running,
        )
        engine.pause(sec(4.0))
        assertEquals(TripPhase.PAUSED, engine.checkpoint(sec(5.0), 80L)?.phase)
        engine.finish(sec(5.0))
        assertNull(engine.checkpoint(sec(6.0), 90L))
    }

    @Test
    fun `starting again after finishing resets every statistic`() {
        engine.start("first", sec(0.0), 0L)
        moveAndFix(0.0, 0.0)
        moveAndFix(1.0, 10.0)
        engine.finish(sec(2.0))
        engine.start("second", sec(10.0), 10L)
        val state = engine.snapshot(sec(10.0))
        assertEquals("second", state.tripId)
        assertEquals(TripStats(movingDurationMillis = 0L), state.stats)
        assertTrue(state.awaitingFix)
    }

    // ---- moving time and moving average --------------------------------------------------------------------

    @Test
    fun `moving time leaves out a stop at a red light, the average does not`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(0.0, 0.0, speedMps = 10f)
        for (s in 1..60) moveAndFix(s.toDouble(), 10.0, speedMps = 10f)
        // 60 s waiting at a light: stationary fixes, a little jitter.
        for (s in 61..120) engine.onAcceptedFix(fixAt(s.toDouble(), speedMps = 0f))
        for (s in 121..180) moveAndFix(s.toDouble(), 10.0, speedMps = 10f)
        val stats = engine.stats(sec(180.0))
        assertEquals(180_000L, stats.activeDurationMillis)
        assertEquals(1_200.0, stats.distanceMeters, 0.5)
        // 60 one-second segments before the light and 60 after it; the minute at the light is not moving.
        assertEquals(120_000L, stats.movingDurationMillis)
        assertEquals(10.0, stats.movingAverageSpeedMps!!.toDouble(), 0.01)
        assertEquals(1_200.0 / 180.0, stats.averageSpeedMps.toDouble(), 0.01)
    }

    @Test
    fun `a parked phone builds up no moving time`() {
        engine.start("trip", sec(0.0), 0L)
        val random = Random(3)
        val centerLat = latitude
        for (s in 0..600) {
            // Within the 5 m noise floor (see the jitter tests above).
            val lat = latitudeAfterNorth(centerLat, random.nextDouble(-2.0, 2.0))
            engine.onAcceptedFix(fixAt(s.toDouble(), speedMps = if (s % 7 == 0) 0.6f else 0f, lat = lat))
        }
        assertEquals(0L, engine.stats(sec(600.0)).movingDurationMillis)
        assertNull(engine.stats(sec(600.0)).movingAverageSpeedMps)
    }

    @Test
    fun `a confirmed tunnel counts as moving time, a refuted one does not`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(1.0, 0.0, speedMps = 20f)
        moveAndFix(61.0, 1_150.0, speedMps = 20f)
        assertEquals(0L, engine.stats(sec(61.0)).movingDurationMillis, "pending")
        moveAndFix(62.0, 20.0, speedMps = 20f)
        assertEquals(61_000L, engine.stats(sec(62.0)).movingDurationMillis)
    }

    @Test
    fun `moving time survives a checkpoint, and an old checkpoint without it stays unknown`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(0.0, 0.0)
        for (s in 1..10) moveAndFix(s.toDouble(), 10.0)
        val checkpoint = engine.checkpoint(sec(10.0), 1_000L)!!
        assertEquals(10_000L, checkpoint.stats.movingDurationMillis)
        val restored = TripEngine()
        assertTrue(restored.restore(checkpoint))
        assertEquals(10_000L, restored.stats(sec(99.0)).movingDurationMillis)

        val old = TripEngine()
        assertTrue(old.restore(checkpoint.copy(stats = checkpoint.stats.copy(movingDurationMillis = null))))
        assertNull(old.stats(sec(99.0)).movingDurationMillis, "the earlier part was never measured")
    }

    // ---- ascent and descent ------------------------------------------------------------------------------

    private fun climb(seconds: Double, meters: Double, altitude: Double, speedMps: Float = 5f, vertical: Float = 3f) {
        latitude = latitudeAfterNorth(latitude, meters)
        engine.onAcceptedFix(
            fixAt(seconds, speedMps = speedMps, altitudeM = altitude, verticalAccuracyM = vertical),
        )
    }

    @Test
    fun `a steady climb and descent are counted, altitude noise is not`() {
        engine.start("trip", sec(0.0), 0L)
        val random = Random(5)
        var altitude = 100.0
        // Up 60 m over 120 s, with ±2 m noise on every reading.
        for (s in 0..120) {
            altitude = 100.0 + s * 0.5
            climb(s.toDouble(), 5.0, altitude + random.nextDouble(-2.0, 2.0))
        }
        // Down 40 m.
        for (s in 121..200) climb(s.toDouble(), 5.0, altitude - (s - 120) * 0.5 + random.nextDouble(-2.0, 2.0))
        val stats = engine.stats(sec(200.0))
        assertTrue(stats.ascentMeters!! in 50.0..64.0, "climbed ~60 m, counted ${stats.ascentMeters}")
        assertTrue(stats.descentMeters!! in 30.0..44.0, "descended ~40 m, counted ${stats.descentMeters}")
    }

    @Test
    fun `a still phone whose altitude wanders gains no height`() {
        engine.start("trip", sec(0.0), 0L)
        val random = Random(8)
        for (s in 0..900) {
            engine.onAcceptedFix(
                fixAt(
                    s.toDouble(),
                    speedMps = 0f,
                    altitudeM = 100.0 + random.nextDouble(-15.0, 15.0),
                    verticalAccuracyM = 4f,
                ),
            )
        }
        val stats = engine.stats(sec(900.0))
        assertEquals(0.0, stats.ascentMeters)
        assertEquals(0.0, stats.descentMeters)
    }

    @Test
    fun `without altitude from the receiver ascent stays unknown`() {
        engine.start("trip", sec(0.0), 0L)
        for (s in 0..10) moveAndFix(s.toDouble(), 10.0)
        assertNull(engine.stats(sec(10.0)).ascentMeters)
        assertNull(engine.stats(sec(10.0)).descentMeters)
    }

    @Test
    fun `climbing while paused is not counted`() {
        engine.start("trip", sec(0.0), 0L)
        climb(0.0, 0.0, 100.0)
        climb(1.0, 5.0, 120.0)
        engine.pause(sec(2.0))
        climb(3.0, 5.0, 200.0)
        engine.resume(sec(4.0))
        climb(5.0, 5.0, 205.0)
        climb(6.0, 5.0, 230.0)
        val stats = engine.stats(sec(6.0))
        assertEquals(20.0 + 25.0, stats.ascentMeters!!, 1e-6)
    }
}

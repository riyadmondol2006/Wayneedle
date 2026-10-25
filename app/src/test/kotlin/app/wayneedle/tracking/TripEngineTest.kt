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
    ) = AcceptedFix(lat, lon, accuracyM, speedMps, sec(seconds))

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
    fun `plausible segment across a signal gap is counted`() {
        engine.start("trip", sec(0.0), 0L)
        moveAndFix(1.0, 0.0, speedMps = 20f)
        // 60 s tunnel at 20 m/s, nearly straight.
        assertEquals(1_150.0, moveAndFix(61.0, 1_150.0, speedMps = 20f), 0.01)
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
            TripCheckpoint("trip", TripPhase.RUNNING, 7L, TripStats(activeDurationMillis = 3_000L), 70L),
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
        assertEquals(TripStats(), state.stats)
        assertTrue(state.awaitingFix)
    }
}

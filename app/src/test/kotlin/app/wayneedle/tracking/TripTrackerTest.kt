@file:OptIn(ExperimentalCoroutinesApi::class)

package app.wayneedle.tracking

import app.cash.turbine.test
import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.LocationSourceEvent
import app.wayneedle.domain.model.GpsSignalQuality
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.testing.aSample
import app.wayneedle.testing.latitudeAfterNorth
import app.wayneedle.testing.longitudeAfterEast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripTrackerTest {
    private val segment = 10.0 * (GeoMath.EARTH_MEAN_RADIUS_M / 6_371_000.0)

    @Test
    fun `initial state reflects the permission checker and holds no GPS listener`() = runTest {
        val h =
            TrackerHarness(this, permissions = FakePermissionChecker(LocationPermissionState.GRANTED_APPROXIMATE_ONLY))
        h.runCurrent()
        assertEquals(LocationPermissionState.GRANTED_APPROXIMATE_ONLY, h.state.permission)
        assertEquals(TripPhase.IDLE, h.state.trip.phase)
        assertEquals(LiveSpeed.Unavailable, h.state.liveSpeed)
        assertEquals(GpsSignalQuality.NONE, h.state.gps.quality)
        assertEquals(0, h.source.totalCollections)
    }

    @Test
    fun `live speed works without a trip and converts to km per h by 3_6`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        assertEquals(1, h.source.activeCollectors)
        assertEquals(listOf(1_000L), h.source.requestedIntervals)
        h.advance(1_000L)
        h.fix(speedMps = 10f)
        val speed = assertIs<LiveSpeed.Available>(h.state.liveSpeed)
        assertEquals(10f, speed.rawMps)
        assertEquals(10f, speed.smoothedMps)
        assertEquals(36.0, SpeedUnit.KMH.fromMetersPerSecond(speed.rawMps.toDouble()), 1e-4)
        assertEquals(GpsSignalQuality.EXCELLENT, h.state.gps.quality)
        assertEquals(5f, h.state.gps.horizontalAccuracyM)
        assertEquals(0L, h.state.gps.lastFixAgeMillis)
        assertEquals(TripPhase.IDLE, h.state.trip.phase)
    }

    @Test
    fun `a genuine zero reading shows 0 while a fix without speed shows unavailable`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.advance(1_000L)
        h.fix(speedMps = 0f)
        assertEquals(LiveSpeed.Available(0f, 0f), h.state.liveSpeed)
        h.advance(1_000L)
        h.fix(speedMps = 0.3f)
        assertEquals(LiveSpeed.Available(0f, 0f), h.state.liveSpeed)
        h.advance(1_000L)
        h.fix(speedMps = null)
        assertEquals(LiveSpeed.Unavailable, h.state.liveSpeed)
        // The fix itself was current, so the signal indicator still reflects it.
        assertEquals(GpsSignalQuality.EXCELLENT, h.state.gps.quality)
    }

    @Test
    fun `unreliable speed accuracy makes live speed unavailable`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.advance(1_000L)
        h.fix(speedMps = 12f)
        h.advance(1_000L)
        h.fix(speedMps = 12f, speedAccuracyMps = 6f)
        assertEquals(LiveSpeed.Unavailable, h.state.liveSpeed)
    }

    @Test
    fun `a speed spike is ignored for display and max speed`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        assertEquals(CommandResult.Ok, h.tracker.startTrip())
        h.drive(5, speedMps = 10f)
        h.advance(1_000L)
        h.latitude = latitudeAfterNorth(h.latitude, 10.0)
        h.fix(speedMps = 45f)
        val shown = assertIs<LiveSpeed.Available>(h.state.liveSpeed)
        assertEquals(10f, shown.rawMps)
        assertEquals(10f, h.state.trip.stats.maxSpeedMps)
        h.drive(2, speedMps = 11f)
        assertEquals(11f, h.state.trip.stats.maxSpeedMps)
    }

    @Test
    fun `live speed and signal quality downgrade when fixes stop arriving`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.advance(1_000L)
        h.fix(speedMps = 15f)
        h.advance(3_000L)
        assertIs<LiveSpeed.Available>(h.state.liveSpeed)
        assertEquals(GpsSignalQuality.GOOD, h.state.gps.quality, "one level down after missing updates")
        h.advance(1_000L)
        assertIs<LiveSpeed.Available>(h.state.liveSpeed)
        h.advance(1_000L)
        assertEquals(LiveSpeed.Unavailable, h.state.liveSpeed)
        assertEquals(GpsSignalQuality.NONE, h.state.gps.quality)
        assertEquals(5_000L, h.state.gps.lastFixAgeMillis)
    }

    @Test
    fun `a late delivered fix is rejected as stale`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.advance(10_000L)
        h.fix(speedMps = 20f, ageMillis = 6_000L)
        assertEquals(LiveSpeed.Unavailable, h.state.liveSpeed)
        assertNull(h.state.gps.lastFixAgeMillis)
    }

    @Test
    fun `duration ticks about once per second without any fix`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        h.runCurrent()
        h.tracker.state.map { it.trip.stats.activeDurationMillis }.distinctUntilChanged().test {
            assertEquals(0L, awaitItem())
            h.advance(1_000L)
            assertEquals(1_000L, awaitItem())
            h.advance(1_000L)
            assertEquals(2_000L, awaitItem())
            h.advance(1_000L)
            assertEquals(3_000L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `in the background the state is refreshed every five seconds, and every second again once visible`() =
        runTest {
            val h = TrackerHarness(this)
            h.tracker.startTrip()
            h.runCurrent()
            h.tracker.state.map { it.trip.stats.activeDurationMillis }.distinctUntilChanged().test {
                assertEquals(0L, awaitItem())
                h.advance(4_000L)
                expectNoEvents()
                h.advance(1_000L)
                assertEquals(5_000L, awaitItem())
                // The user opens the app: published at once, then every second.
                h.advance(500L)
                h.tracker.setUiVisible(true)
                h.runCurrent()
                assertEquals(5_500L, awaitItem())
                h.advance(1_000L)
                assertEquals(6_500L, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `full lifecycle start pause resume finish`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()

        assertEquals(CommandResult.Ok, h.tracker.startTrip())
        val tripId = assertNotNull(h.state.trip.tripId)
        UUID.fromString(tripId)
        assertEquals(TripPhase.RUNNING, h.state.trip.phase)
        assertTrue(h.state.trip.awaitingFix)
        assertEquals(h.time.epochMillis(), h.state.trip.startedAtEpochMillis)

        h.drive(1, speedMps = 10f)
        assertFalse(h.state.trip.awaitingFix, "first fix anchors")
        assertEquals(0.0, h.state.trip.stats.distanceMeters, "first fix is only an anchor")
        h.drive(9, speedMps = 10f)
        assertEquals(9 * segment, h.state.trip.stats.distanceMeters, 1e-3)

        assertEquals(CommandResult.Ok, h.tracker.pauseTrip())
        assertEquals(TripPhase.PAUSED, h.state.trip.phase)
        assertEquals(10_000L, h.state.trip.stats.activeDurationMillis)

        assertEquals(CommandResult.Ok, h.tracker.resumeTrip())
        assertTrue(h.state.trip.awaitingFix)
        h.drive(5, speedMps = 10f)
        assertFalse(h.state.trip.awaitingFix)
        assertEquals(13 * segment, h.state.trip.stats.distanceMeters, 1e-3)

        val result = assertIs<FinishResult.Saved>(h.tracker.finishTrip())
        val trip = result.trip
        assertEquals(tripId, trip.id)
        assertEquals(15_000L, trip.stats.activeDurationMillis)
        assertEquals(13 * segment, trip.stats.distanceMeters, 1e-3)
        assertEquals(10f, trip.stats.maxSpeedMps)
        assertEquals(h.time.epochMillis(), trip.endedAtEpochMillis)
        assertEquals(trip, h.repository.stored[tripId])

        assertEquals(TripPhase.FINISHED, h.state.trip.phase)
        assertEquals(trip.stats, h.state.trip.stats)
        assertEquals(listOf("start", "start", "stop"), h.foreground.calls)
        h.runCurrent()
        assertNull(h.checkpoints.checkpoint)

        h.tracker.dismissFinished()
        assertEquals(TripPhase.IDLE, h.state.trip.phase)
        assertNull(h.state.trip.tripId)
    }

    @Test
    fun `nothing changes in the statistics while paused even with fast fixes`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        h.drive(10, speedMps = 10f)
        h.tracker.pauseTrip()
        val frozen = h.state.trip.stats

        h.drive(30, speedMps = 20f)
        h.advance(60_000L)
        h.drive(5, speedMps = 20f)

        assertEquals(frozen, h.state.trip.stats)
        assertEquals(20f, assertIs<LiveSpeed.Available>(h.state.liveSpeed).rawMps, "live speed still shown")
        assertEquals(TripPhase.PAUSED, h.state.trip.phase)
    }

    @Test
    fun `distance covered while paused is never counted and the first fix after resume is an anchor`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        h.drive(3, speedMps = 10f)
        assertEquals(2 * segment, h.state.trip.stats.distanceMeters, 1e-3)
        h.tracker.pauseTrip()
        h.drive(20, speedMps = 15f)
        h.tracker.resumeTrip()
        assertTrue(h.state.trip.awaitingFix)
        h.drive(1, speedMps = 10f)
        assertFalse(h.state.trip.awaitingFix)
        assertEquals(2 * segment, h.state.trip.stats.distanceMeters, 1e-3)
        h.drive(2, speedMps = 10f)
        assertEquals(4 * segment, h.state.trip.stats.distanceMeters, 1e-3)
    }

    @Test
    fun `average speed is distance over active duration`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        h.drive(11, speedMps = 10f)
        h.tracker.pauseTrip()
        h.advance(30_000L)
        h.tracker.resumeTrip()
        h.advance(9_000L)
        h.tracker.pauseTrip()
        val stats = h.state.trip.stats
        assertEquals(20_000L, stats.activeDurationMillis)
        assertEquals(10 * segment, stats.distanceMeters, 1e-3)
        assertEquals((10 * segment / 20.0).toFloat(), stats.averageSpeedMps, 1e-4f)
    }

    @Test
    fun `parked phone with jittery fixes accumulates no distance`() = runTest {
        val h = TrackerHarness(this)
        h.tracker.startTrip()
        val random = Random(3)
        val centerLat = h.latitude
        val centerLon = h.longitude
        repeat(300) {
            val radius = random.nextDouble(0.0, 2.0)
            val angle = random.nextDouble(0.0, 2 * Math.PI)
            h.latitude = latitudeAfterNorth(centerLat, radius * cos(angle))
            h.longitude = longitudeAfterEast(h.latitude, centerLon, radius * sin(angle))
            h.advance(1_000L)
            h.fix(speedMps = random.nextDouble(0.0, 0.9).toFloat())
        }
        assertEquals(0.0, h.state.trip.stats.distanceMeters)
        assertEquals(300_000L, h.state.trip.stats.activeDurationMillis)
    }

    @Test
    fun `invalid and duplicate transitions are rejected and change nothing`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        assertEquals(CommandResult.InvalidTransition(TripPhase.IDLE), h.tracker.pauseTrip())
        assertEquals(CommandResult.InvalidTransition(TripPhase.IDLE), h.tracker.resumeTrip())
        assertEquals(CommandResult.InvalidTransition(TripPhase.IDLE), h.tracker.discardTrip())
        assertEquals(FinishResult.InvalidTransition(TripPhase.IDLE), h.tracker.finishTrip())

        assertEquals(CommandResult.Ok, h.tracker.startTrip())
        val tripId = h.state.trip.tripId
        assertEquals(CommandResult.InvalidTransition(TripPhase.RUNNING), h.tracker.startTrip())
        assertEquals(CommandResult.InvalidTransition(TripPhase.RUNNING), h.tracker.resumeTrip())
        assertEquals(tripId, h.state.trip.tripId)

        h.advance(2_000L)
        assertEquals(CommandResult.Ok, h.tracker.pauseTrip())
        assertEquals(CommandResult.InvalidTransition(TripPhase.PAUSED), h.tracker.pauseTrip())
        assertEquals(CommandResult.InvalidTransition(TripPhase.PAUSED), h.tracker.startTrip())
        h.advance(5_000L)
        assertEquals(2_000L, h.state.trip.stats.activeDurationMillis)

        assertIs<FinishResult.Saved>(h.tracker.finishTrip())
        assertEquals(FinishResult.InvalidTransition(TripPhase.FINISHED), h.tracker.finishTrip())
        assertEquals(CommandResult.InvalidTransition(TripPhase.FINISHED), h.tracker.pauseTrip())
        assertEquals(CommandResult.InvalidTransition(TripPhase.FINISHED), h.tracker.resumeTrip())
        assertEquals(CommandResult.InvalidTransition(TripPhase.FINISHED), h.tracker.discardTrip())
        assertEquals(1, h.repository.saveCalls)
        assertEquals(listOf("start", "stop"), h.foreground.calls)

        h.tracker.dismissFinished()
        h.tracker.dismissFinished()
        assertEquals(TripPhase.IDLE, h.state.trip.phase)
    }

    @Test
    fun `a new trip can start straight from the finished summary`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        h.drive(3, speedMps = 10f)
        val first = assertIs<FinishResult.Saved>(h.tracker.finishTrip()).trip
        assertEquals(CommandResult.Ok, h.tracker.startTrip())
        assertNotEquals(first.id, h.state.trip.tripId)
        assertEquals(0.0, h.state.trip.stats.distanceMeters)
        assertEquals(0L, h.state.trip.stats.activeDurationMillis)
        assertTrue(h.state.trip.awaitingFix)
    }

    @Test
    fun `concurrent finish saves once and rejects the second caller`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        h.drive(3, speedMps = 10f)
        val gate = CompletableDeferred<Unit>()
        h.repository.saveGate = gate

        val first = async { h.tracker.finishTrip() }
        h.runCurrent()
        assertEquals(TripPhase.PAUSED, h.state.trip.phase, "frozen while saving")
        assertEquals(FinishResult.InvalidTransition(TripPhase.PAUSED), h.tracker.finishTrip())
        assertEquals(CommandResult.InvalidTransition(TripPhase.PAUSED), h.tracker.resumeTrip())
        assertEquals(CommandResult.InvalidTransition(TripPhase.PAUSED), h.tracker.discardTrip())

        gate.complete(Unit)
        assertIs<FinishResult.Saved>(first.await())
        assertEquals(1, h.repository.saveCalls)
        assertEquals(TripPhase.FINISHED, h.state.trip.phase)
        assertEquals(listOf("start", "stop"), h.foreground.calls)
    }

    @Test
    fun `discard drops the trip and stops the service`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        h.drive(3, speedMps = 10f)
        assertEquals(CommandResult.Ok, h.tracker.discardTrip())
        h.runCurrent()
        assertEquals(TripPhase.IDLE, h.state.trip.phase)
        assertNull(h.state.trip.tripId)
        assertNull(h.checkpoints.checkpoint)
        assertEquals(0, h.repository.saveCalls)
        assertEquals(listOf("start", "stop"), h.foreground.calls)
    }

    @Test
    fun `start requires precise permission and an enabled provider`() = runTest {
        val h =
            TrackerHarness(this, permissions = FakePermissionChecker(LocationPermissionState.GRANTED_APPROXIMATE_ONLY))
        h.showUi()
        assertEquals(0, h.source.activeCollectors, "approximate location cannot measure speed")
        assertEquals(CommandResult.PermissionRequired, h.tracker.startTrip())
        h.permissions.state = LocationPermissionState.DENIED
        assertEquals(CommandResult.PermissionRequired, h.tracker.startTrip())
        assertEquals(TripPhase.IDLE, h.state.trip.phase)

        h.permissions.state = LocationPermissionState.GRANTED_PRECISE
        h.source.providerEnabled = false
        h.tracker.refreshEnvironment()
        h.runCurrent()
        assertEquals(1, h.source.activeCollectors)
        assertFalse(h.state.gps.providerEnabled)
        assertEquals(CommandResult.LocationDisabled, h.tracker.startTrip())
        assertEquals(TripPhase.IDLE, h.state.trip.phase)

        h.send(LocationSourceEvent.ProviderEnabledChanged(true))
        assertEquals(CommandResult.Ok, h.tracker.startTrip())
        assertEquals(listOf("start"), h.foreground.calls)
    }

    @Test
    fun `resume requires precise permission`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        h.tracker.pauseTrip()
        h.permissions.state = LocationPermissionState.DENIED
        assertEquals(CommandResult.PermissionRequired, h.tracker.resumeTrip())
        assertEquals(TripPhase.PAUSED, h.state.trip.phase)
        assertEquals(listOf("start"), h.foreground.calls)
    }

    @Test
    fun `exactly one GPS collection follows UI visibility and the running phase`() = runTest {
        val h = TrackerHarness(this)
        val source = h.source
        h.runCurrent()
        assertEquals(0, source.activeCollectors)

        h.showUi()
        assertEquals(1, source.activeCollectors)

        h.tracker.setUiVisible(false)
        h.runCurrent()
        assertEquals(0, source.activeCollectors, "released: hidden and not running")

        h.showUi()
        assertEquals(1, source.activeCollectors, "reacquired when visible again")
        val collections = source.totalCollections
        h.tracker.startTrip()
        h.runCurrent()
        assertEquals(collections, source.totalCollections, "starting a trip reuses the collection")

        h.tracker.setUiVisible(false)
        h.runCurrent()
        assertEquals(1, source.activeCollectors, "kept while RUNNING in the background")
        h.drive(3, speedMps = 10f)
        assertEquals(2 * segment, h.state.trip.stats.distanceMeters, 1e-3)

        h.tracker.pauseTrip()
        h.runCurrent()
        assertEquals(0, source.activeCollectors, "released when PAUSED in the background")
        assertEquals(LiveSpeed.Unavailable, h.state.liveSpeed)
        assertEquals(GpsSignalQuality.NONE, h.state.gps.quality)

        h.tracker.resumeTrip()
        h.runCurrent()
        assertEquals(1, source.activeCollectors, "resumed from the notification")

        h.showUi()
        assertIs<FinishResult.Saved>(h.tracker.finishTrip())
        h.runCurrent()
        assertEquals(1, source.activeCollectors, "visible UI keeps live speed after finishing")
        h.tracker.setUiVisible(false)
        h.runCurrent()
        assertEquals(0, source.activeCollectors)

        assertEquals(1, source.maxConcurrentCollectors)
    }

    @Test
    fun `rapid visibility toggling never creates concurrent collections`() = runTest {
        val h = TrackerHarness(this)
        repeat(25) {
            h.tracker.setUiVisible(true)
            h.tracker.setUiVisible(false)
            if (it % 5 == 0) h.runCurrent()
        }
        h.tracker.setUiVisible(true)
        h.runCurrent()
        assertEquals(1, h.source.activeCollectors)
        assertEquals(1, h.source.maxConcurrentCollectors)
        h.advance(1_000L)
        h.fix(speedMps = 8f)
        assertEquals(8f, assertIs<LiveSpeed.Available>(h.state.liveSpeed).rawMps)
    }

    @Test
    fun `a new collection waits until the previous listener has unregistered`() = runTest {
        val h = TrackerHarness(this, source = FakeLocationSource(teardownMillis = 300L))
        h.showUi()
        h.tracker.setUiVisible(false)
        h.tracker.setUiVisible(true)
        h.runCurrent()
        assertEquals(1, h.source.activeCollectors, "old listener still unregistering, new one waiting")
        h.advance(300L)
        assertEquals(1, h.source.activeCollectors)
        assertEquals(2, h.source.totalCollections)
        assertEquals(1, h.source.maxConcurrentCollectors)
        h.fix(speedMps = 5f)
        assertEquals(5f, assertIs<LiveSpeed.Available>(h.state.liveSpeed).rawMps)
    }

    @Test
    fun `permission revoked mid trip keeps running without distance until GPS returns`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        h.drive(5, speedMps = 10f)
        assertEquals(4 * segment, h.state.trip.stats.distanceMeters, 1e-3)

        h.send(LocationSourceEvent.PermissionDenied)
        assertEquals(TripPhase.RUNNING, h.state.trip.phase)
        assertTrue(h.state.trip.awaitingFix)
        assertEquals(LiveSpeed.Unavailable, h.state.liveSpeed)
        assertEquals(LocationPermissionState.DENIED, h.state.permission)
        assertEquals(0, h.source.activeCollectors, "the source completed after PermissionDenied")

        h.latitude = latitudeAfterNorth(h.latitude, 500.0)
        h.advance(10_000L)
        assertEquals(15_000L, h.state.trip.stats.activeDurationMillis, "duration keeps counting")
        assertEquals(4 * segment, h.state.trip.stats.distanceMeters, 1e-3)

        h.tracker.refreshEnvironment()
        h.runCurrent()
        assertEquals(1, h.source.activeCollectors)
        assertEquals(LocationPermissionState.GRANTED_PRECISE, h.state.permission)
        h.drive(1, speedMps = 10f)
        assertFalse(h.state.trip.awaitingFix)
        assertEquals(4 * segment, h.state.trip.stats.distanceMeters, 1e-3, "fresh anchor, blind path not counted")
        h.drive(2, speedMps = 10f)
        assertEquals(6 * segment, h.state.trip.stats.distanceMeters, 1e-3)
        assertEquals(1, h.source.maxConcurrentCollectors)
    }

    @Test
    fun `permission downgrade seen on refresh releases the listener but keeps the trip running`() = runTest {
        val h = TrackerHarness(this)
        h.tracker.startTrip()
        h.drive(3, speedMps = 10f)
        h.permissions.state = LocationPermissionState.GRANTED_APPROXIMATE_ONLY
        h.tracker.refreshEnvironment()
        h.runCurrent()
        assertEquals(0, h.source.activeCollectors)
        assertEquals(TripPhase.RUNNING, h.state.trip.phase)
        assertTrue(h.state.trip.awaitingFix)
        assertEquals(LocationPermissionState.GRANTED_APPROXIMATE_ONLY, h.state.permission)
        // Hidden app: the next background refresh is at 10 s.
        h.advance(7_000L)
        assertEquals(10_000L, h.state.trip.stats.activeDurationMillis)
    }

    @Test
    fun `provider switched off mid trip keeps running and re-anchors when it returns`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        h.drive(4, speedMps = 10f)
        assertEquals(3 * segment, h.state.trip.stats.distanceMeters, 1e-3)

        h.send(LocationSourceEvent.ProviderEnabledChanged(false))
        assertFalse(h.state.gps.providerEnabled)
        assertEquals(GpsSignalQuality.NONE, h.state.gps.quality)
        assertEquals(LiveSpeed.Unavailable, h.state.liveSpeed)
        assertEquals(TripPhase.RUNNING, h.state.trip.phase)
        assertTrue(h.state.trip.awaitingFix)

        h.latitude = latitudeAfterNorth(h.latitude, 300.0)
        h.advance(20_000L)
        assertEquals(24_000L, h.state.trip.stats.activeDurationMillis)

        h.send(LocationSourceEvent.ProviderEnabledChanged(true))
        h.drive(1, speedMps = 10f)
        assertEquals(3 * segment, h.state.trip.stats.distanceMeters, 1e-3)
        h.drive(1, speedMps = 10f)
        assertEquals(4 * segment, h.state.trip.stats.distanceMeters, 1e-3)
        assertEquals(1, h.source.totalCollections, "provider changes do not re-register the listener")
    }

    @Test
    fun `a current fix proves the provider is on even if the callback was missed`() = runTest {
        val h = TrackerHarness(this, source = FakeLocationSource(providerEnabled = false))
        h.showUi()
        assertFalse(h.state.gps.providerEnabled)
        h.advance(1_000L)
        h.fix(speedMps = 4f)
        assertTrue(h.state.gps.providerEnabled)
        assertEquals(CommandResult.Ok, h.tracker.startTrip())
    }

    @Test
    fun `satellite counts are reported and cleared when the listener is released`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.send(LocationSourceEvent.Satellites(used = 14, inView = 27))
        assertEquals(14, h.state.gps.satellitesUsed)
        assertEquals(27, h.state.gps.satellitesInView)
        h.advance(1_000L)
        h.fix(horizontalAccuracyM = 3f)
        assertEquals(GpsSignalQuality.EXCELLENT, h.state.gps.quality)
        h.send(LocationSourceEvent.Satellites(used = 3, inView = 9))
        assertEquals(GpsSignalQuality.POOR, h.state.gps.quality)
        h.tracker.setUiVisible(false)
        h.runCurrent()
        assertNull(h.state.gps.satellitesUsed)
        assertNull(h.state.gps.satellitesInView)
    }

    @Test
    fun `commands are atomic when called from many threads`() = runTest {
        val h = TrackerHarness(this)
        h.tracker.startTrip()
        h.runCurrent()
        val pauses = AtomicInteger()
        val resumes = AtomicInteger()
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = List(4) {
                pool.submit {
                    ready.await()
                    repeat(500) {
                        if (h.tracker.pauseTrip() == CommandResult.Ok) pauses.incrementAndGet()
                        if (h.tracker.resumeTrip() == CommandResult.Ok) resumes.incrementAndGet()
                    }
                }
            }
            ready.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        h.runCurrent()
        val phase = h.state.trip.phase
        val expectedResumes = if (phase == TripPhase.PAUSED) pauses.get() - 1 else pauses.get()
        assertEquals(expectedResumes, resumes.get(), "every resume matches exactly one earlier pause")
        assertEquals(1 + resumes.get(), h.foreground.calls.count { it == "start" })
        assertEquals(1, h.source.maxConcurrentCollectors)
    }

    @Test
    fun `heading and altitude are shown while moving and disappear with the live speed`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        fun fixWith(speed: Float, bearing: Float?, bearingAccuracy: Float? = 5f) {
            h.advance(1_000L)
            h.latitude = latitudeAfterNorth(h.latitude, speed.toDouble())
            h.send(
                LocationSourceEvent.Fix(
                    aSample(
                        latitude = h.latitude,
                        speedMps = speed,
                        elapsedRealtimeMillis = h.time.nowMillis,
                        altitudeM = 120.0,
                        verticalAccuracyM = 4f,
                        bearingDeg = bearing,
                        bearingAccuracyDeg = bearingAccuracy,
                    ),
                ),
            )
        }
        fixWith(speed = 10f, bearing = 47f)
        assertEquals(47f, h.state.gps.bearingDeg)
        assertEquals(120.0, h.state.gps.altitudeM)
        assertEquals(4f, h.state.gps.verticalAccuracyM)
        assertEquals(0.5f, h.state.gps.speedAccuracyMps)

        fixWith(speed = 10f, bearing = 47f, bearingAccuracy = 90f)
        assertNull(h.state.gps.bearingDeg, "a course this uncertain is not shown")
        fixWith(speed = 0f, bearing = 200f)
        assertNull(h.state.gps.bearingDeg, "standing still has no course")

        h.advance(6_000L)
        assertNull(h.state.gps.altitudeM, "no current fix, no altitude")
    }

    @Test
    fun `the state says whether the app is on screen`() = runTest {
        val h = TrackerHarness(this)
        assertFalse(h.state.uiVisible)
        h.showUi()
        assertTrue(h.state.uiVisible)
        h.tracker.setUiVisible(false)
        assertFalse(h.state.uiVisible)
    }
}

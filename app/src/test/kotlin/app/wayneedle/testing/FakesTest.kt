package app.wayneedle.testing

import app.cash.turbine.test
import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.LocationSourceEvent
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripCheckpoint
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripState
import app.wayneedle.domain.model.TripStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakesTest {
    private fun trip(id: String, start: Long, end: Long = start + 1_000L) =
        Trip(id = id, startedAtEpochMillis = start, endedAtEpochMillis = end, stats = TripStats(distanceMeters = 10.0))

    private fun checkpoint(id: String, phase: TripPhase = TripPhase.RUNNING, saved: Long = 0L) = TripCheckpoint(
        tripId = id,
        phase = phase,
        startedAtEpochMillis = 100L,
        stats = TripStats(),
        savedAtEpochMillis = saved,
    )

    // ---- InMemoryTripRepository ----------------------------------------------------------------------------

    @Test
    fun `saving the same trip twice never duplicates it`() = runTest {
        val repo = InMemoryTripRepository()
        repo.saveTrip(trip("a", 1_000L))
        repo.saveTrip(trip("a", 1_000L).copy(stats = TripStats(distanceMeters = 99.0)))
        assertEquals(1, repo.stored.size)
        assertEquals(99.0, repo.getTrip("a")?.stats?.distanceMeters)
        assertEquals(2, repo.saveCalls)
    }

    @Test
    fun `trips are listed newest first`() = runTest {
        val repo = InMemoryTripRepository(listOf(trip("old", 1_000L), trip("new", 3_000L)))
        repo.saveTrip(trip("mid", 2_000L))
        assertEquals(listOf("new", "mid", "old"), repo.observeTrips().first().map { it.id })
    }

    @Test
    fun `observeTrip emits null once the trip is deleted`() = runTest {
        val repo = InMemoryTripRepository(listOf(trip("a", 1_000L)))
        repo.observeTrip("a").test {
            assertEquals("a", awaitItem()?.id)
            repo.deleteTrip("a")
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(repo.getTrip("a"))
    }

    @Test
    fun `a failing save throws and leaves the stored trips untouched`() = runTest {
        val repo = InMemoryTripRepository()
        repo.saveFailure = IllegalStateException("disk full")
        assertFailsWith<IllegalStateException> { repo.saveTrip(trip("a", 1_000L)) }
        assertTrue(repo.stored.isEmpty())
        repo.saveFailure = null
        repo.saveTrip(trip("a", 1_000L))
        repo.deleteAllTrips()
        assertTrue(repo.stored.isEmpty())
    }

    // ---- InMemoryTripCheckpointStore -------------------------------------------------------------------------

    @Test
    fun `checkpoint store keeps one slot and remembers every write`() = runTest {
        val store = InMemoryTripCheckpointStore()
        assertNull(store.load())
        store.save(checkpoint("a", saved = 1L))
        store.save(checkpoint("a", phase = TripPhase.PAUSED, saved = 2L))
        assertEquals(TripPhase.PAUSED, store.load()?.phase)
        assertEquals(listOf(1L, 2L), store.savedCheckpoints.map { it.savedAtEpochMillis })
        store.clear()
        assertNull(store.load())
        assertEquals(1, store.clearCalls)
        assertEquals(2, store.savedCheckpoints.size)
    }

    @Test
    fun `checkpoint save failures are injectable`() = runTest {
        val store = InMemoryTripCheckpointStore()
        store.saveFailure = IllegalStateException("io")
        assertFailsWith<IllegalStateException> { store.save(checkpoint("a")) }
        assertNull(store.current)
    }

    // ---- launcher, permission, settings --------------------------------------------------------------------

    @Test
    fun `foreground launcher start and stop are idempotent and counted`() {
        val launcher = FakeTripForegroundLauncher()
        launcher.start()
        launcher.start()
        assertTrue(launcher.isRunning)
        launcher.stop()
        launcher.stop()
        assertFalse(launcher.isRunning)
        assertEquals(2, launcher.startCalls)
        assertEquals(2, launcher.stopCalls)
    }

    @Test
    fun `permission checker returns what it is told`() {
        val checker = FakeLocationPermissionChecker()
        assertEquals(LocationPermissionState.GRANTED_PRECISE, checker.current())
        checker.state = LocationPermissionState.GRANTED_APPROXIMATE_ONLY
        assertFalse(checker.current().canMeasureSpeed)
    }

    @Test
    fun `settings fake applies every setter`() = runTest {
        val settings = FakeSettingsRepository()
        settings.setSpeedUnit(SpeedUnit.MPH)
        settings.setThemeMode(ThemeMode.DARK)
        settings.setDynamicColor(false)
        settings.setKeepScreenOn(false)
        val current = settings.settings.first()
        assertEquals(SpeedUnit.MPH, current.speedUnit)
        assertEquals(ThemeMode.DARK, current.themeMode)
        assertFalse(current.dynamicColor)
        assertFalse(current.keepScreenOn)
        assertEquals(current, settings.current)
    }

    // ---- FakeLocationSource ----------------------------------------------------------------------------------

    @Test
    fun `location source emits the provider state first and then delivered fixes`() = runTest {
        val source = FakeLocationSource(providerEnabled = true)
        val fix = aSample(speedMps = 12f)
        source.events(minIntervalMillis = 500L).test {
            assertEquals(LocationSourceEvent.ProviderEnabledChanged(true), awaitItem())
            source.emitFix(fix)
            assertEquals(LocationSourceEvent.Fix(fix), awaitItem())
            source.setProviderEnabled(false)
            assertEquals(LocationSourceEvent.ProviderEnabledChanged(false), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(500L, source.lastRequestedIntervalMillis)
    }

    @Test
    fun `location source delivers a scripted route in order`() = runTest {
        val route = scriptedRoute { cruise(steps = 3, metersPerStep = 10.0) }
        val source = FakeLocationSource()
        source.events().test {
            awaitItem()
            source.emitFixes(route)
            route.forEach { assertEquals(LocationSourceEvent.Fix(it), awaitItem()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `location source reports denied permission and completes`() = runTest {
        val source = FakeLocationSource(permissionGranted = false)
        source.events().test {
            assertEquals(LocationSourceEvent.PermissionDenied, awaitItem())
            awaitComplete()
        }
        assertEquals(0, source.activeCollectors)
    }

    @Test
    fun `location source is cold and tracks collectors, so a second subscriber is detectable`() = runTest {
        val source = FakeLocationSource()
        assertEquals(0, source.activeCollectors)
        source.events().test {
            awaitItem()
            assertEquals(1, source.activeCollectors)
            source.events().test {
                awaitItem()
                assertEquals(2, source.activeCollectors)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, source.activeCollectors)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, source.activeCollectors)
        assertEquals(2, source.maxConcurrentCollectors)
        assertEquals(2, source.subscriptionCount)
    }

    // ---- FakeTrackingController ------------------------------------------------------------------------------

    @Test
    fun `tracking controller fake records commands and serves pushed states`() = runTest {
        val controller = FakeTrackingController()
        assertEquals(CommandResult.Ok, controller.startTrip())
        controller.commandResult = CommandResult.PermissionRequired
        assertEquals(CommandResult.PermissionRequired, controller.pauseTrip())
        controller.setUiVisible(true)
        controller.refreshEnvironment()
        assertEquals(FinishResult.InvalidTransition(TripPhase.IDLE), controller.finishTrip())
        controller.recoverResult = true
        assertTrue(controller.recoverInterruptedTrip())
        assertEquals(
            listOf(
                FakeTrackingController.Command.START,
                FakeTrackingController.Command.PAUSE,
                FakeTrackingController.Command.REFRESH_ENVIRONMENT,
                FakeTrackingController.Command.FINISH,
                FakeTrackingController.Command.RECOVER,
            ),
            controller.commands,
        )
        assertTrue(controller.uiVisible)

        controller.state.test {
            assertEquals(TrackingState.Initial, awaitItem())
            val running = TrackingState(trip = TripState(phase = TripPhase.RUNNING, tripId = "t"))
            controller.emit(running)
            assertEquals(running, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

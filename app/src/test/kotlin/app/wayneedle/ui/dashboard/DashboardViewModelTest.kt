package app.wayneedle.ui.dashboard

import app.cash.turbine.test
import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.testing.FakeTimeSource
import app.wayneedle.ui.MainDispatcherRule
import app.wayneedle.ui.keepActive
import app.wayneedle.ui.preview.FakeSettingsRepository
import app.wayneedle.ui.preview.FakeTrackingController
import app.wayneedle.ui.preview.FakeTripRepository
import app.wayneedle.ui.preview.PreviewData
import app.wayneedle.ui.speedometer.SpeedReading
import app.wayneedle.ui.speedometer.SpeedUnavailableReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val trips = FakeTripRepository()
    private val timeSource = FakeTimeSource(wallClockMillis = PreviewData.NOW)

    private fun viewModel(
        controller: FakeTrackingController,
        hasGpsHardware: Boolean = true,
    ) = DashboardViewModel(controller, settings, trips, timeSource, hasGpsHardware)

    /** A deliberate next tap, well after the double-tap guard. */
    private fun thinkBeforeTapping() = timeSource.advanceMillis(DashboardViewModel.DOUBLE_TAP_GUARD_MILLIS + 400L)

    // ---- state mapping ----------------------------------------------------------------------------------------

    @Test
    fun `waiting for the first fix shows an explicit searching state, not zero`() = runTest {
        val vm = viewModel(FakeTrackingController(PreviewData.idleSearching))
        keepActive(vm.uiState)
        val state = vm.uiState.value
        assertEquals(SpeedReading.Unavailable(SpeedUnavailableReason.SEARCHING), state.gauge.reading)
        assertEquals(TripPhase.IDLE, state.trip.phase)
        assertNull(state.trip.currentSpeedMps)
    }

    @Test
    fun `a genuine standstill is an available zero`() = runTest {
        val vm = viewModel(FakeTrackingController(PreviewData.idleStationary))
        keepActive(vm.uiState)
        assertEquals(SpeedReading.Available(0f), vm.uiState.value.gauge.reading)
        assertEquals(0f, vm.uiState.value.trip.currentSpeedMps)
    }

    @Test
    fun `speed is shown in the selected unit and follows unit changes`() = runTest {
        val controller = FakeTrackingController(PreviewData.idleMoving) // 15 m/s
        val vm = viewModel(controller)
        keepActive(vm.uiState)
        assertEquals(54f, assertIs<SpeedReading.Available>(vm.uiState.value.gauge.reading).valueInUnit, 1e-3f)
        assertEquals(SpeedUnit.KMH, vm.uiState.value.unit)

        settings.setSpeedUnit(SpeedUnit.MPH)
        val mph = vm.uiState.value
        assertEquals(SpeedUnit.MPH, mph.unit)
        assertEquals(33.55f, assertIs<SpeedReading.Available>(mph.gauge.reading).valueInUnit, 0.01f)
        assertEquals(150, mph.gauge.scale.max)
    }

    @Test
    fun `the unavailable reason follows permission and provider state`() = runTest {
        val controller = FakeTrackingController(PreviewData.permissionDenied)
        val vm = viewModel(controller)
        keepActive(vm.uiState)
        assertEquals(
            SpeedReading.Unavailable(SpeedUnavailableReason.PERMISSION_REQUIRED),
            vm.uiState.value.gauge.reading,
        )

        controller.update { PreviewData.approximateOnly }
        assertEquals(SpeedReading.Unavailable(SpeedUnavailableReason.APPROXIMATE_ONLY), vm.uiState.value.gauge.reading)

        controller.update { PreviewData.providerOff }
        assertEquals(SpeedReading.Unavailable(SpeedUnavailableReason.GPS_OFF), vm.uiState.value.gauge.reading)
    }

    @Test
    fun `no GPS hardware is reported as such`() = runTest {
        val vm = viewModel(FakeTrackingController(PreviewData.permissionDenied), hasGpsHardware = false)
        keepActive(vm.uiState)
        assertEquals(SpeedReading.Unavailable(SpeedUnavailableReason.NO_GPS_HARDWARE), vm.uiState.value.gauge.reading)
        assertFalse(vm.uiState.value.hasGpsHardware)
    }

    @Test
    fun `gauge scale grows when the speed exceeds it and does not shrink back`() = runTest {
        val controller = FakeTrackingController(PreviewData.idleMoving)
        val vm = viewModel(controller)
        keepActive(vm.uiState)
        assertEquals(240, vm.uiState.value.gauge.scale.max)

        controller.update { it.copy(liveSpeed = PreviewData.live(70f)) } // 252 km/h
        assertEquals(280, vm.uiState.value.gauge.scale.max)

        controller.update { it.copy(liveSpeed = PreviewData.live(5f)) }
        assertEquals(280, vm.uiState.value.gauge.scale.max)
    }

    @Test
    fun `trip statistics, awaiting fix and recovered flag are mapped`() = runTest {
        val controller = FakeTrackingController(PreviewData.runningAwaitingFix)
        val vm = viewModel(controller)
        keepActive(vm.uiState)
        assertTrue(vm.uiState.value.trip.awaitingFix)
        assertEquals(TripPhase.RUNNING, vm.uiState.value.trip.phase)

        controller.update { PreviewData.recovered }
        val recovered = vm.uiState.value.trip
        assertTrue(recovered.recovered)
        assertEquals(TripPhase.PAUSED, recovered.phase)
        assertEquals(27_800.0, recovered.stats.distanceMeters)
        assertEquals(2_292_000L, recovered.stats.activeDurationMillis)
    }

    // ---- real-life taps and restarts ------------------------------------------------------------------------------

    @Test
    fun `a double tap on Start does not pause the trip it just started`() = runTest {
        val controller = FakeTrackingController(PreviewData.idleStationary)
        val vm = viewModel(controller)
        keepActive(vm.uiState)
        assertEquals(CommandResult.Ok, vm.startTrip())
        // The second tap lands where Pause now is, 150 ms later.
        timeSource.advanceMillis(150L)
        vm.pauseTrip()
        vm.requestFinish()
        assertEquals(TripPhase.RUNNING, vm.uiState.value.trip.phase)
        assertNull(vm.uiState.value.dialog)
        assertEquals(listOf("startTrip"), controller.commands)
        // A deliberate Pause a moment later works.
        thinkBeforeTapping()
        vm.pauseTrip()
        assertEquals(TripPhase.PAUSED, vm.uiState.value.trip.phase)
    }

    @Test
    fun `a double tap on Pause does not resume at once`() = runTest {
        val controller = FakeTrackingController(PreviewData.running)
        val vm = viewModel(controller)
        keepActive(vm.uiState)
        vm.pauseTrip()
        timeSource.advanceMillis(200L)
        vm.resumeTrip()
        assertEquals(TripPhase.PAUSED, vm.uiState.value.trip.phase)
    }

    @Test
    fun `the summary survives the app being closed and reopened`() = runTest {
        // The trip was finished and stored earlier; this is a brand-new view model on the FINISHED tracker.
        trips.saveTrip(PreviewData.savedTrip)
        val vm = viewModel(FakeTrackingController(PreviewData.finished))
        keepActive(vm.uiState)
        val trip = vm.uiState.value.trip
        assertEquals(PreviewData.savedTrip.id, trip.savedTripId, "View details must still work")
        assertEquals(PreviewData.savedTrip.totalDurationMillis, trip.totalDurationMillis)
    }

    @Test
    fun `the summary drops its details link when the trip is deleted from History`() = runTest {
        val controller = FakeTrackingController(PreviewData.paused, trips)
        val vm = viewModel(controller)
        keepActive(vm.uiState)
        vm.confirmFinish()
        val id = assertNotNull(vm.uiState.value.trip.savedTripId)
        trips.deleteTrip(id)
        assertNull(vm.uiState.value.trip.savedTripId)
    }

    @Test
    fun `a refused foreground service is shown only while a trip is active`() = runTest {
        val controller = FakeTrackingController(PreviewData.running.copy(backgroundRecordingUnavailable = true))
        val vm = viewModel(controller)
        keepActive(vm.uiState)
        assertTrue(vm.uiState.value.backgroundRecordingUnavailable)
        controller.update { PreviewData.idleStationary.copy(backgroundRecordingUnavailable = true) }
        assertFalse(vm.uiState.value.backgroundRecordingUnavailable)
    }

    // ---- speed alert -------------------------------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `crossing the speed alert limit vibrates once and colours the dial`() = runTest {
        settings.setSpeedAlertEnabled(true)
        settings.setSpeedAlertMps(25f)
        val controller = FakeTrackingController(PreviewData.idleMoving) // 15 m/s
        val vm = viewModel(controller)
        val alerts = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.speedAlerts.toList(alerts) }
        keepActive(vm.uiState)
        assertFalse(vm.uiState.value.gauge.overLimit)
        assertEquals(90f, assertNotNull(vm.uiState.value.gauge.limitInUnit), 0.01f)

        controller.update { it.copy(liveSpeed = PreviewData.live(27f)) }
        controller.update { it.copy(liveSpeed = PreviewData.live(28f)) }
        controller.update { it.copy(liveSpeed = PreviewData.live(26f)) }
        assertTrue(vm.uiState.value.gauge.overLimit)
        assertEquals(1, alerts.size, "one vibration per crossing")

        controller.update { it.copy(liveSpeed = PreviewData.live(20f)) }
        controller.update { it.copy(liveSpeed = PreviewData.live(27f)) }
        assertEquals(2, alerts.size, "the next crossing alerts again")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `no alert and no limit mark while the speed alert is off`() = runTest {
        val controller = FakeTrackingController(PreviewData.idleMoving)
        val vm = viewModel(controller)
        val alerts = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.speedAlerts.toList(alerts) }
        keepActive(vm.uiState)
        controller.update { it.copy(liveSpeed = PreviewData.live(60f)) }
        assertFalse(vm.uiState.value.gauge.overLimit)
        assertNull(vm.uiState.value.gauge.limitInUnit)
        assertTrue(alerts.isEmpty())
    }

    // ---- total time ---------------------------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `total time keeps counting every second while paused, active time does not`() = runTest {
        val vm = viewModel(FakeTrackingController(PreviewData.paused))
        keepActive(vm.uiState)
        val start = vm.uiState.value.trip
        assertEquals(2_592_000L, start.totalDurationMillis)

        repeat(3) {
            timeSource.advanceMillis(1_000L)
            advanceTimeBy(1_000L)
            runCurrent()
        }

        val later = vm.uiState.value.trip
        assertEquals(2_595_000L, later.totalDurationMillis)
        assertEquals(start.stats.activeDurationMillis, later.stats.activeDurationMillis)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `total time is absent without a trip and stays absent after a trip ends`() = runTest {
        val controller = FakeTrackingController(PreviewData.running)
        val vm = viewModel(controller)
        keepActive(vm.uiState)
        assertEquals(2_592_000L, vm.uiState.value.trip.totalDurationMillis)

        controller.update { PreviewData.idleStationary }
        timeSource.advanceMillis(5_000L)
        advanceTimeBy(5_000L)
        runCurrent()
        assertNull(vm.uiState.value.trip.totalDurationMillis)
    }

    // ---- commands -------------------------------------------------------------------------------------------------

    @Test
    fun `start pause and resume are forwarded to the controller`() = runTest {
        val controller = FakeTrackingController(PreviewData.idleStationary)
        val vm = viewModel(controller)
        keepActive(vm.uiState)

        assertEquals(CommandResult.Ok, vm.startTrip())
        assertEquals(TripPhase.RUNNING, vm.uiState.value.trip.phase)
        thinkBeforeTapping()
        vm.pauseTrip()
        assertEquals(TripPhase.PAUSED, vm.uiState.value.trip.phase)
        thinkBeforeTapping()
        assertEquals(CommandResult.Ok, vm.resumeTrip())
        assertEquals(TripPhase.RUNNING, vm.uiState.value.trip.phase)
        assertEquals(listOf("startTrip", "pauseTrip", "resumeTrip"), controller.commands)
    }

    @Test
    fun `a rejected start refreshes the environment so the permission card can appear`() = runTest {
        val controller = FakeTrackingController(PreviewData.idleStationary)
        controller.startResult = CommandResult.PermissionRequired
        val vm = viewModel(controller)

        assertEquals(CommandResult.PermissionRequired, vm.startTrip())
        assertEquals(listOf("startTrip", "refreshEnvironment"), controller.commands)

        controller.commands.clear()
        controller.startResult = CommandResult.LocationDisabled
        vm.startTrip()
        assertEquals(listOf("startTrip", "refreshEnvironment"), controller.commands)
    }

    @Test
    fun `refreshEnvironment is forwarded`() = runTest {
        val controller = FakeTrackingController()
        viewModel(controller).refreshEnvironment()
        assertEquals(listOf("refreshEnvironment"), controller.commands)
    }

    // ---- finish flow ----------------------------------------------------------------------------------------------

    @Test
    fun `finishing needs confirmation and nothing is saved before it`() = runTest {
        val controller = FakeTrackingController(PreviewData.running)
        val vm = viewModel(controller)
        keepActive(vm.uiState)

        vm.requestFinish()
        assertEquals(DashboardDialog.ConfirmFinish, vm.uiState.value.dialog)
        assertFalse(controller.commands.contains("finishTrip"))

        vm.dismissDialog()
        assertNull(vm.uiState.value.dialog)
        assertEquals(TripPhase.RUNNING, vm.uiState.value.trip.phase)
    }

    @Test
    fun `finish dialog is not offered when no trip is active`() = runTest {
        val vm = viewModel(FakeTrackingController(PreviewData.idleStationary))
        keepActive(vm.uiState)
        vm.requestFinish()
        vm.requestDiscard()
        assertNull(vm.uiState.value.dialog)
    }

    @Test
    fun `confirming finish saves the trip and shows the summary with a details link`() = runTest {
        val controller = FakeTrackingController(PreviewData.paused, trips)
        val vm = viewModel(controller)
        keepActive(vm.uiState)

        vm.requestFinish()
        vm.confirmFinish()

        val state = vm.uiState.value
        assertEquals(TripPhase.FINISHED, state.trip.phase)
        assertEquals("trip-running", state.trip.savedTripId)
        assertNull(state.dialog)
        assertFalse(state.isFinishing)
        assertTrue(state.trip.endedAtEpochMillis != null)
    }

    @Test
    fun `done leaves the summary and returns to idle`() = runTest {
        val controller = FakeTrackingController(PreviewData.paused, trips)
        val vm = viewModel(controller)
        keepActive(vm.uiState)
        vm.confirmFinish()

        vm.dismissFinished()

        assertEquals(TripPhase.IDLE, vm.uiState.value.trip.phase)
        assertNull(vm.uiState.value.trip.savedTripId)
        assertTrue(controller.commands.contains("dismissFinished"))
    }

    @Test
    fun `a failed save keeps the trip paused and offers a retry`() = runTest {
        val controller = FakeTrackingController(PreviewData.paused, trips)
        controller.finishHandler = { FinishResult.SaveFailed(IOException("disk full")) }
        val vm = viewModel(controller)
        keepActive(vm.uiState)

        vm.confirmFinish()

        val failed = vm.uiState.value
        assertEquals(DashboardDialog.SaveFailed, failed.dialog)
        assertEquals(TripPhase.PAUSED, failed.trip.phase)
        assertFalse(failed.isFinishing)

        // Retry from the dialog succeeds this time.
        controller.finishHandler = { FinishResult.Saved(PreviewData.savedTrip) }
        vm.confirmFinish()
        val saved = vm.uiState.value
        assertNull(saved.dialog)
        assertEquals(TripPhase.FINISHED, saved.trip.phase)
        assertEquals(2, controller.commands.count { it == "finishTrip" })
    }

    @Test
    fun `an unexpected exception while saving is handled like a failed save`() = runTest {
        val controller = FakeTrackingController(PreviewData.paused)
        controller.finishHandler = { throw IllegalStateException("boom") }
        val vm = viewModel(controller)
        keepActive(vm.uiState)

        vm.confirmFinish()

        assertEquals(DashboardDialog.SaveFailed, vm.uiState.value.dialog)
        assertFalse(vm.uiState.value.isFinishing)
    }

    @Test
    fun `finish is not started twice while saving`() = runTest {
        val controller = FakeTrackingController(PreviewData.running)
        val gate = CompletableDeferred<Unit>()
        controller.finishHandler = { current ->
            gate.await()
            FinishResult.Saved(PreviewData.savedTrip.copy(stats = current.trip.stats))
        }
        val vm = viewModel(controller)
        keepActive(vm.uiState)

        vm.confirmFinish()
        assertTrue(vm.uiState.value.isFinishing)
        vm.confirmFinish()
        gate.complete(Unit)

        assertEquals(1, controller.commands.count { it == "finishTrip" })
        assertFalse(vm.uiState.value.isFinishing)
    }

    // ---- discard flow ---------------------------------------------------------------------------------------------

    @Test
    fun `discarding needs confirmation and then returns to idle`() = runTest {
        val controller = FakeTrackingController(PreviewData.paused)
        val vm = viewModel(controller)
        keepActive(vm.uiState)

        vm.requestDiscard()
        assertEquals(DashboardDialog.ConfirmDiscard, vm.uiState.value.dialog)
        assertFalse(controller.commands.contains("discardTrip"))

        vm.confirmDiscard()
        assertNull(vm.uiState.value.dialog)
        assertEquals(TripPhase.IDLE, vm.uiState.value.trip.phase)
        assertTrue(controller.commands.contains("discardTrip"))
    }

    @Test
    fun `cancelling the discard dialog keeps the trip`() = runTest {
        val controller = FakeTrackingController(PreviewData.paused)
        val vm = viewModel(controller)
        keepActive(vm.uiState)
        vm.requestDiscard()
        vm.dismissDialog()
        assertEquals(TripPhase.PAUSED, vm.uiState.value.trip.phase)
        assertFalse(controller.commands.contains("discardTrip"))
    }

    // ---- sequences ------------------------------------------------------------------------------------------------

    @Test
    fun `state emissions are distinct and settings changes are applied`() = runTest {
        val controller = FakeTrackingController(PreviewData.idleStationary)
        val vm = viewModel(controller)
        vm.uiState.test {
            assertEquals(SpeedUnit.KMH, awaitItem().unit)
            settings.setSpeedUnit(SpeedUnit.MPH)
            assertEquals(SpeedUnit.MPH, awaitItem().unit)
            controller.update {
                it.copy(permission = LocationPermissionState.DENIED, liveSpeed = LiveSpeed.Unavailable)
            }
            val denied = awaitItem()
            assertIs<SpeedReading.Unavailable>(denied.gauge.reading)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

package app.wayneedle.ui.dashboard

import app.cash.turbine.test
import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.ui.MainDispatcherRule
import app.wayneedle.ui.keepActive
import app.wayneedle.ui.preview.FakeSettingsRepository
import app.wayneedle.ui.preview.FakeTrackingController
import app.wayneedle.ui.preview.PreviewData
import app.wayneedle.ui.speedometer.SpeedReading
import app.wayneedle.ui.speedometer.SpeedUnavailableReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val settings = FakeSettingsRepository()

    private fun viewModel(
        controller: FakeTrackingController,
        hasGpsHardware: Boolean = true,
    ) = DashboardViewModel(controller, settings, hasGpsHardware)

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
        assertEquals(SpeedReading.Unavailable(SpeedUnavailableReason.PERMISSION_REQUIRED), vm.uiState.value.gauge.reading)

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

    // ---- commands -------------------------------------------------------------------------------------------------

    @Test
    fun `start pause and resume are forwarded to the controller`() = runTest {
        val controller = FakeTrackingController(PreviewData.idleStationary)
        val vm = viewModel(controller)
        keepActive(vm.uiState)

        assertEquals(CommandResult.Ok, vm.startTrip())
        assertEquals(TripPhase.RUNNING, vm.uiState.value.trip.phase)
        vm.pauseTrip()
        assertEquals(TripPhase.PAUSED, vm.uiState.value.trip.phase)
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
        val controller = FakeTrackingController(PreviewData.paused)
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
        val controller = FakeTrackingController(PreviewData.paused)
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
        val controller = FakeTrackingController(PreviewData.paused)
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
            controller.update { it.copy(permission = LocationPermissionState.DENIED, liveSpeed = LiveSpeed.Unavailable) }
            val denied = awaitItem()
            assertIs<SpeedReading.Unavailable>(denied.gauge.reading)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

package app.wayneedle.ui.dashboard

import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.ui.preview.PreviewData
import app.wayneedle.ui.speedometer.GaugeScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardLogicTest {
    @Test
    fun `idle shows the start button only when location is ready`() {
        assertTrue(showsPinnedControls(TripPhase.IDLE, recovered = false, gate = LocationGate.Ready))
        assertFalse(showsPinnedControls(TripPhase.IDLE, recovered = false, gate = LocationGate.GpsOff))
        assertFalse(showsPinnedControls(TripPhase.IDLE, recovered = false, gate = LocationGate.NeedsRationale))
    }

    @Test
    fun `an active trip keeps its controls even when location is lost`() {
        assertTrue(showsPinnedControls(TripPhase.RUNNING, recovered = false, gate = LocationGate.GpsOff))
        assertTrue(showsPinnedControls(TripPhase.PAUSED, recovered = false, gate = LocationGate.PermanentlyDenied))
    }

    @Test
    fun `a recovered trip uses the banner actions instead of the pinned controls`() {
        assertFalse(showsPinnedControls(TripPhase.PAUSED, recovered = true, gate = LocationGate.Ready))
    }

    @Test
    fun `the summary has its own buttons`() {
        assertFalse(showsPinnedControls(TripPhase.FINISHED, recovered = false, gate = LocationGate.Ready))
    }

    @Test
    fun `the saved trip is only used while the controller reports FINISHED`() {
        val running = PreviewData.dashboard(PreviewData.running, savedTrip = PreviewData.savedTrip)
        assertNull(running.trip.savedTripId)
        assertNull(running.trip.endedAtEpochMillis)

        val finished = PreviewData.dashboard(PreviewData.finished, savedTrip = PreviewData.savedTrip)
        assertEquals(PreviewData.savedTrip.id, finished.trip.savedTripId)
        assertEquals(PreviewData.savedTrip.endedAtEpochMillis, finished.trip.endedAtEpochMillis)
        assertEquals(PreviewData.savedTrip.stats, finished.trip.stats)
    }

    @Test
    fun `finished without a saved trip still shows the engine statistics but no details link`() {
        val state = buildUiState(
            state = PreviewData.finished,
            unit = SpeedUnit.KMH,
            scale = GaugeScale.base(SpeedUnit.KMH),
            dialog = null,
            isFinishing = false,
            savedTrip = null,
            hasGpsHardware = true,
        )
        assertNull(state.trip.savedTripId)
        assertEquals(PreviewData.finished.trip.stats, state.trip.stats)
        assertTrue(state.trip.phase == TripPhase.FINISHED)
    }

    @Test
    fun `isTripActive is true only while running or paused`() {
        assertTrue(PreviewData.dashboard(PreviewData.running).isTripActive)
        assertTrue(PreviewData.dashboard(PreviewData.paused).isTripActive)
        assertFalse(PreviewData.dashboard(PreviewData.idleStationary).isTripActive)
        assertFalse(PreviewData.dashboard(PreviewData.finished).isTripActive)
    }
}

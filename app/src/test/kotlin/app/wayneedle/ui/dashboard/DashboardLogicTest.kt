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
            nowEpochMillis = PreviewData.NOW,
        )
        assertNull(state.trip.savedTripId)
        assertEquals(PreviewData.finished.trip.stats, state.trip.stats)
        assertTrue(state.trip.phase == TripPhase.FINISHED)
    }

    @Test
    fun `total time spans start to now and includes the pause that active time leaves out`() {
        val running = PreviewData.dashboard(PreviewData.running).trip
        assertEquals(2_292_000L, running.stats.activeDurationMillis)
        assertEquals(2_592_000L, running.totalDurationMillis)
    }

    @Test
    fun `a paused trip keeps accruing total time while its active time stands still`() {
        fun paused(now: Long) = buildUiState(
            state = PreviewData.paused,
            unit = SpeedUnit.KMH,
            scale = GaugeScale.base(SpeedUnit.KMH),
            dialog = null,
            isFinishing = false,
            savedTrip = null,
            hasGpsHardware = true,
            nowEpochMillis = now,
        ).trip

        val soon = paused(PreviewData.NOW)
        val later = paused(PreviewData.NOW + 90_000L)
        assertEquals(soon.stats.activeDurationMillis, later.stats.activeDurationMillis)
        assertEquals(90_000L, later.totalDurationMillis!! - soon.totalDurationMillis!!)
    }

    @Test
    fun `a saved trip reports its stored total time and does not grow afterwards`() {
        val finished = PreviewData.dashboard(PreviewData.finished, savedTrip = PreviewData.savedTrip).trip
        assertEquals(PreviewData.savedTrip.totalDurationMillis, finished.totalDurationMillis)
        assertEquals(2_592_000L, finished.totalDurationMillis)
    }

    @Test
    fun `there is no total time without a trip, or for a finished trip that was not saved`() {
        assertNull(PreviewData.dashboard(PreviewData.idleStationary).trip.totalDurationMillis)
        assertNull(PreviewData.dashboard(PreviewData.finished).trip.totalDurationMillis)
    }

    @Test
    fun `isTripActive is true only while running or paused`() {
        assertTrue(PreviewData.dashboard(PreviewData.running).isTripActive)
        assertTrue(PreviewData.dashboard(PreviewData.paused).isTripActive)
        assertFalse(PreviewData.dashboard(PreviewData.idleStationary).isTripActive)
        assertFalse(PreviewData.dashboard(PreviewData.finished).isTripActive)
    }
}

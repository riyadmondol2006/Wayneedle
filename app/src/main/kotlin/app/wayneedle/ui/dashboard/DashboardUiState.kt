package app.wayneedle.ui.dashboard

import androidx.compose.runtime.Immutable
import app.wayneedle.domain.model.GpsStatus
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripStats
import app.wayneedle.ui.speedometer.GaugeScale
import app.wayneedle.ui.speedometer.SpeedReading

/** Modal prompts owned by the view model so they survive rotation. */
enum class DashboardDialog {
    ConfirmFinish,
    ConfirmDiscard,

    /** Saving the finished trip failed; the trip is still paused and intact. */
    SaveFailed,
}

@Immutable
data class GaugeUiState(
    val reading: SpeedReading,
    val scale: GaugeScale,
)

/**
 * @property currentSpeedMps latest display speed in m/s, or null when there is no reliable speed.
 * @property savedTripId id of the trip stored by this screen's Finish action, for the "View details" link.
 */
@Immutable
data class TripUiState(
    val phase: TripPhase = TripPhase.IDLE,
    val stats: TripStats = TripStats.Empty,
    val currentSpeedMps: Float? = null,
    val startedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val awaitingFix: Boolean = false,
    val recovered: Boolean = false,
    val savedTripId: String? = null,
)

@Immutable
data class DashboardUiState(
    val unit: SpeedUnit,
    val gauge: GaugeUiState,
    val gps: GpsStatus,
    val permission: LocationPermissionState,
    val hasGpsHardware: Boolean,
    val trip: TripUiState,
    val dialog: DashboardDialog? = null,
    /** True while a Finish is being saved; commands are disabled meanwhile. */
    val isFinishing: Boolean = false,
) {
    val isTripActive: Boolean get() = trip.phase == TripPhase.RUNNING || trip.phase == TripPhase.PAUSED
}

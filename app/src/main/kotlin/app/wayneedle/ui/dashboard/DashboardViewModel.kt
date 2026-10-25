package app.wayneedle.ui.dashboard

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TrackingController
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.ui.STOP_TIMEOUT_MILLIS
import app.wayneedle.ui.wayneedleApplication
import app.wayneedle.ui.speedometer.GaugeScale
import app.wayneedle.ui.speedometer.speedReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State and commands of the dashboard. It only observes [TrackingController.state] and forwards commands;
 * the controller owns the trip, so leaving the screen never touches an active trip.
 */
class DashboardViewModel(
    private val controller: TrackingController,
    settingsRepository: SettingsRepository,
    private val hasGpsHardware: Boolean,
) : ViewModel() {
    private data class Local(
        val dialog: DashboardDialog? = null,
        val isFinishing: Boolean = false,
        val savedTrip: Trip? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<DashboardUiState> = flow {
        var scale = GaugeScale.base(SpeedUnit.KMH)
        combine(
            controller.state,
            settingsRepository.settings.map { it.speedUnit }.distinctUntilChanged(),
            local,
        ) { state, unit, local -> Triple(state, unit, local) }
            .collect { (state, unit, local) ->
                scale = scale.extendedFor(unit, displayedSpeed(state, unit))
                emit(buildUiState(state, unit, scale, local.dialog, local.isFinishing, local.savedTrip, hasGpsHardware))
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = buildUiState(
            state = controller.state.value,
            unit = SpeedUnit.KMH,
            scale = GaugeScale.base(SpeedUnit.KMH),
            dialog = null,
            isFinishing = false,
            savedTrip = null,
            hasGpsHardware = hasGpsHardware,
        ),
    )

    fun startTrip(): CommandResult = controller.startTrip().also(::refreshWhenBlocked)

    fun pauseTrip() {
        controller.pauseTrip()
    }

    fun resumeTrip(): CommandResult = controller.resumeTrip().also(::refreshWhenBlocked)

    fun requestFinish() = openDialogIfActive(DashboardDialog.ConfirmFinish)

    fun requestDiscard() = openDialogIfActive(DashboardDialog.ConfirmDiscard)

    fun dismissDialog() = local.update { it.copy(dialog = null) }

    /** Saves the trip. Also the retry action of [DashboardDialog.SaveFailed]. */
    fun confirmFinish() {
        if (local.value.isFinishing) return
        local.update { it.copy(dialog = null, isFinishing = true) }
        viewModelScope.launch {
            // The save must complete even if the user leaves the screen right after confirming.
            val result = withContext(NonCancellable) {
                try {
                    controller.finishTrip()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    FinishResult.SaveFailed(failure)
                }
            }
            local.update {
                when (result) {
                    is FinishResult.Saved -> it.copy(isFinishing = false, savedTrip = result.trip)
                    is FinishResult.SaveFailed -> it.copy(isFinishing = false, dialog = DashboardDialog.SaveFailed)
                    is FinishResult.InvalidTransition -> it.copy(isFinishing = false)
                }
            }
        }
    }

    fun confirmDiscard() {
        local.update { it.copy(dialog = null) }
        controller.discardTrip()
    }

    /** Leaves the trip summary so a new trip can start. */
    fun dismissFinished() {
        controller.dismissFinished()
        local.update { it.copy(savedTrip = null) }
    }

    /** Re-reads permission and GPS provider state; call after a permission result or returning from settings. */
    fun refreshEnvironment() = controller.refreshEnvironment()

    private fun openDialogIfActive(dialog: DashboardDialog) {
        val phase = controller.state.value.trip.phase
        if (phase == TripPhase.RUNNING || phase == TripPhase.PAUSED) {
            local.update { it.copy(dialog = dialog) }
        }
    }

    /** A rejected start/resume means our picture of the environment was stale; refresh so the gate card shows. */
    private fun refreshWhenBlocked(result: CommandResult) {
        if (result is CommandResult.PermissionRequired || result is CommandResult.LocationDisabled) {
            controller.refreshEnvironment()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val application = wayneedleApplication()
                val container = application.container
                DashboardViewModel(
                    controller = container.trackingController,
                    settingsRepository = container.settingsRepository,
                    hasGpsHardware = application.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
                )
            }
        }
    }
}

/** The speed shown on the gauge, in [unit]; 0 when there is none (only used to size the scale). */
private fun displayedSpeed(state: TrackingState, unit: SpeedUnit): Float = when (val live = state.liveSpeed) {
    is LiveSpeed.Available -> unit.fromMetersPerSecond(live.smoothedMps.toDouble()).toFloat()
    LiveSpeed.Unavailable -> 0f
}

internal fun buildUiState(
    state: TrackingState,
    unit: SpeedUnit,
    scale: GaugeScale,
    dialog: DashboardDialog?,
    isFinishing: Boolean,
    savedTrip: Trip?,
    hasGpsHardware: Boolean,
): DashboardUiState {
    val phase = state.trip.phase
    val saved = savedTrip?.takeIf { phase == TripPhase.FINISHED }
    return DashboardUiState(
        unit = unit,
        gauge = GaugeUiState(
            reading = speedReading(state.liveSpeed, unit, state.permission, state.gps, hasGpsHardware),
            scale = scale,
        ),
        gps = state.gps,
        permission = state.permission,
        hasGpsHardware = hasGpsHardware,
        trip = TripUiState(
            phase = phase,
            stats = saved?.stats ?: state.trip.stats,
            currentSpeedMps = (state.liveSpeed as? LiveSpeed.Available)?.smoothedMps,
            startedAtEpochMillis = saved?.startedAtEpochMillis ?: state.trip.startedAtEpochMillis,
            endedAtEpochMillis = saved?.endedAtEpochMillis,
            awaitingFix = state.trip.awaitingFix,
            recovered = state.trip.recovered,
            savedTripId = saved?.id,
        ),
        dialog = dialog,
        isFinishing = isFinishing,
    )
}

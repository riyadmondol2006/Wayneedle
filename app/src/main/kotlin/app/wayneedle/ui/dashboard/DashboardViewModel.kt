package app.wayneedle.ui.dashboard

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TimeSource
import app.wayneedle.domain.TrackingController
import app.wayneedle.domain.TripRepository
import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.ui.STOP_TIMEOUT_MILLIS
import app.wayneedle.ui.speedometer.GaugeScale
import app.wayneedle.ui.speedometer.SpeedReading
import app.wayneedle.ui.speedometer.speedReading
import app.wayneedle.ui.wayneedleApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State and commands of the dashboard. It only observes [TrackingController.state] and forwards commands;
 * the controller owns the trip, so leaving the screen never touches an active trip.
 *
 * **Double taps.** The trip buttons swap places when the phase changes (Start becomes Pause, Pause becomes
 * Resume), so the second tap of a double tap would land on the new button and undo the first. Trip commands
 * arriving within [DOUBLE_TAP_GUARD_MILLIS] of the last phase change made here are ignored.
 */
class DashboardViewModel(
    private val controller: TrackingController,
    settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val timeSource: TimeSource,
    private val hasGpsHardware: Boolean,
) : ViewModel() {
    private data class Local(
        val dialog: DashboardDialog? = null,
        val isFinishing: Boolean = false,
        /** The trip this screen just saved; shown until storage has been read. */
        val savedTrip: Trip? = null,
    )

    /** The stored copy of the trip the controller reports as FINISHED. */
    private sealed interface FinishedTrip {
        data object None : FinishedTrip

        data object Pending : FinishedTrip

        /** [trip] is null when it was deleted meanwhile or cannot be read. */
        data class Stored(val trip: Trip?) : FinishedTrip
    }

    private data class Inputs(
        val state: TrackingState,
        val settings: AppSettings,
        val local: Local,
        val nowEpochMillis: Long,
        val finished: FinishedTrip,
    ) {
        val savedTrip: Trip?
            get() = when (finished) {
                FinishedTrip.None -> null
                FinishedTrip.Pending -> local.savedTrip?.takeIf { it.id == state.trip.tripId }
                is FinishedTrip.Stored -> finished.trip
            }
    }

    private val local = MutableStateFlow(Local())

    /** Grows with the fastest speed shown and never shrinks back, also when the screen subscribes again. */
    private var gaugeScale = GaugeScale.base(SpeedUnit.KMH)

    /** Elapsed-realtime nanos of the last phase change requested here, for the double-tap guard. */
    private var lastPhaseChangeNanos: Long? = null

    private val speedAlert = SpeedAlertMonitor()
    private val speedAlertEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once each time the live speed crosses above the alert limit while this screen is shown. */
    val speedAlerts: Flow<Unit> = speedAlertEvents.asSharedFlow()

    /**
     * Wall-clock readings for the total trip time. It ticks once a second while a trip is RUNNING or PAUSED:
     * the tracker publishes nothing while paused, but paused time still counts toward the total.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val clock: Flow<Long> = controller.state
        .map { it.trip.phase == TripPhase.RUNNING || it.trip.phase == TripPhase.PAUSED }
        .distinctUntilChanged()
        .flatMapLatest { tripActive ->
            flow {
                emit(timeSource.epochMillis())
                while (tripActive) {
                    delay(CLOCK_TICK_MILLIS)
                    emit(timeSource.epochMillis())
                }
            }
        }

    /**
     * The summary reads the saved trip from storage, so it survives this view model being recreated (the
     * app was closed and reopened on the summary) and notices when the trip was deleted from History.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val finishedTrip: Flow<FinishedTrip> = controller.state
        .map { state -> state.trip.tripId.takeIf { state.trip.phase == TripPhase.FINISHED } }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(FinishedTrip.None)
            } else {
                tripRepository.observeTrip(id)
                    .map<Trip?, FinishedTrip> { FinishedTrip.Stored(it) }
                    .onStart { emit(FinishedTrip.Pending) }
                    .catch { emit(FinishedTrip.Stored(null)) }
            }
        }

    val uiState: StateFlow<DashboardUiState> = combine(
        controller.state,
        settingsRepository.settings.distinctUntilChanged(),
        local,
        clock,
        finishedTrip,
    ) { state, settings, local, now, finished -> Inputs(state, settings, local, now, finished) }
        .map { inputs ->
            val unit = inputs.settings.speedUnit
            gaugeScale = gaugeScale.extendedFor(unit, displayedSpeed(inputs.state, unit))
            val liveMps = (inputs.state.liveSpeed as? LiveSpeed.Available)?.smoothedMps
            if (speedAlert.update(liveMps, inputs.settings.speedAlertEnabled, inputs.settings.speedAlertMps)) {
                speedAlertEvents.tryEmit(Unit)
            }
            buildUiState(
                state = inputs.state,
                unit = unit,
                scale = gaugeScale,
                dialog = inputs.local.dialog,
                isFinishing = inputs.local.isFinishing,
                savedTrip = inputs.savedTrip,
                hasGpsHardware = hasGpsHardware,
                nowEpochMillis = inputs.nowEpochMillis,
                speedAlertLimitMps = inputs.settings.speedAlertMps.takeIf { inputs.settings.speedAlertEnabled },
                overSpeedLimit = speedAlert.isOverLimit,
            )
        }
        .stateIn(
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
                nowEpochMillis = timeSource.epochMillis(),
            ),
        )

    fun startTrip(): CommandResult {
        if (isSecondTapOfDoubleTap()) return CommandResult.InvalidTransition(controller.state.value.trip.phase)
        return controller.startTrip().also(::onPhaseCommand)
    }

    fun pauseTrip() {
        if (isSecondTapOfDoubleTap()) return
        onPhaseCommand(controller.pauseTrip())
    }

    fun resumeTrip(): CommandResult {
        if (isSecondTapOfDoubleTap()) return CommandResult.InvalidTransition(controller.state.value.trip.phase)
        return controller.resumeTrip().also(::onPhaseCommand)
    }

    fun requestFinish() {
        if (!isSecondTapOfDoubleTap()) openDialogIfActive(DashboardDialog.ConfirmFinish)
    }

    fun requestDiscard() {
        if (!isSecondTapOfDoubleTap()) openDialogIfActive(DashboardDialog.ConfirmDiscard)
    }

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
            if (result is FinishResult.Saved) markPhaseChange()
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
        if (controller.discardTrip() == CommandResult.Ok) markPhaseChange()
    }

    /** Leaves the trip summary so a new trip can start. */
    fun dismissFinished() {
        controller.dismissFinished()
        markPhaseChange()
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

    private fun onPhaseCommand(result: CommandResult) {
        if (result == CommandResult.Ok) markPhaseChange()
        // A rejected start/resume means our picture of the environment was stale; refresh so the gate card shows.
        if (result is CommandResult.PermissionRequired || result is CommandResult.LocationDisabled) {
            controller.refreshEnvironment()
        }
    }

    private fun markPhaseChange() {
        lastPhaseChangeNanos = timeSource.elapsedRealtimeNanos()
    }

    private fun isSecondTapOfDoubleTap(): Boolean {
        val last = lastPhaseChangeNanos ?: return false
        return timeSource.elapsedRealtimeNanos() - last < DOUBLE_TAP_GUARD_MILLIS * NANOS_PER_MILLI
    }

    companion object {
        private const val CLOCK_TICK_MILLIS = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Twice Android's double-tap timeout (300 ms), far shorter than a deliberate second action. */
        const val DOUBLE_TAP_GUARD_MILLIS = 600L

        val Factory = viewModelFactory {
            initializer {
                val application = wayneedleApplication()
                val container = application.container
                DashboardViewModel(
                    controller = container.trackingController,
                    settingsRepository = container.settingsRepository,
                    tripRepository = container.tripRepository,
                    timeSource = container.timeSource,
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
    nowEpochMillis: Long,
    speedAlertLimitMps: Float? = null,
    overSpeedLimit: Boolean = false,
): DashboardUiState {
    val reading = speedReading(state.liveSpeed, unit, state.permission, state.gps, hasGpsHardware)
    val phase = state.trip.phase
    val saved = savedTrip?.takeIf { phase == TripPhase.FINISHED }
    val stats = saved?.stats ?: state.trip.stats
    val startedAt = saved?.startedAtEpochMillis ?: state.trip.startedAtEpochMillis
    val totalDurationMillis = when {
        saved != null -> saved.totalDurationMillis

        startedAt != null && (phase == TripPhase.RUNNING || phase == TripPhase.PAUSED) ->
            stats.totalDurationMillis(startedAt, nowEpochMillis)

        else -> null
    }
    return DashboardUiState(
        unit = unit,
        gauge = GaugeUiState(
            reading = reading,
            scale = scale,
            limitInUnit = speedAlertLimitMps?.let { unit.fromMetersPerSecond(it.toDouble()).toFloat() },
            overLimit = overSpeedLimit && reading is SpeedReading.Available,
        ),
        gps = state.gps,
        permission = state.permission,
        hasGpsHardware = hasGpsHardware,
        trip = TripUiState(
            phase = phase,
            stats = stats,
            currentSpeedMps = (state.liveSpeed as? LiveSpeed.Available)?.smoothedMps,
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = saved?.endedAtEpochMillis,
            totalDurationMillis = totalDurationMillis,
            awaitingFix = state.trip.awaitingFix,
            recovered = state.trip.recovered,
            savedTripId = saved?.id,
        ),
        dialog = dialog,
        isFinishing = isFinishing,
        backgroundRecordingUnavailable = state.backgroundRecordingUnavailable &&
            (phase == TripPhase.RUNNING || phase == TripPhase.PAUSED),
    )
}

package app.wayneedle.ui.preview

import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TrackingController
import app.wayneedle.domain.TripRepository
import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An in-memory [TrackingController] for previews and tests: a minimal copy of the documented state machine
 * with every command recorded in [commands]. Results can be forced through the `*Result` properties.
 */
class FakeTrackingController(initial: TrackingState = TrackingState.Initial) : TrackingController {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<TrackingState> = mutableState.asStateFlow()

    /** Commands in the order they were received, e.g. `startTrip`, `setUiVisible(true)`. */
    val commands = mutableListOf<String>()

    var startResult: CommandResult? = null
    var resumeResult: CommandResult? = null

    /** Produces the outcome of [finishTrip]; the default saves the trip. */
    var finishHandler: suspend (TrackingState) -> FinishResult = { current ->
        FinishResult.Saved(current.toTrip())
    }

    /** Replaces part of the state, as the real engine would on a new GPS fix or permission change. */
    fun update(transform: (TrackingState) -> TrackingState) = mutableState.update(transform)

    override fun startTrip(): CommandResult {
        commands += "startTrip"
        startResult?.let { return it }
        val phase = mutableState.value.trip.phase
        if (phase != TripPhase.IDLE && phase != TripPhase.FINISHED) return CommandResult.InvalidTransition(phase)
        mutableState.update {
            it.copy(trip = TripState(phase = TripPhase.RUNNING, tripId = "fake-trip", startedAtEpochMillis = START_TIME, awaitingFix = true))
        }
        return CommandResult.Ok
    }

    override fun pauseTrip(): CommandResult {
        commands += "pauseTrip"
        val phase = mutableState.value.trip.phase
        if (phase != TripPhase.RUNNING) return CommandResult.InvalidTransition(phase)
        mutableState.update { it.copy(trip = it.trip.copy(phase = TripPhase.PAUSED)) }
        return CommandResult.Ok
    }

    override fun resumeTrip(): CommandResult {
        commands += "resumeTrip"
        resumeResult?.let { return it }
        val phase = mutableState.value.trip.phase
        if (phase != TripPhase.PAUSED) return CommandResult.InvalidTransition(phase)
        mutableState.update { it.copy(trip = it.trip.copy(phase = TripPhase.RUNNING, awaitingFix = true, recovered = false)) }
        return CommandResult.Ok
    }

    override suspend fun finishTrip(): FinishResult {
        commands += "finishTrip"
        val current = mutableState.value
        if (current.trip.phase != TripPhase.RUNNING && current.trip.phase != TripPhase.PAUSED) {
            return FinishResult.InvalidTransition(current.trip.phase)
        }
        val result = finishHandler(current)
        if (result is FinishResult.Saved) {
            mutableState.update { it.copy(trip = it.trip.copy(phase = TripPhase.FINISHED, awaitingFix = false)) }
        }
        return result
    }

    override fun discardTrip(): CommandResult {
        commands += "discardTrip"
        val phase = mutableState.value.trip.phase
        if (phase != TripPhase.RUNNING && phase != TripPhase.PAUSED) return CommandResult.InvalidTransition(phase)
        mutableState.update { it.copy(trip = TripState()) }
        return CommandResult.Ok
    }

    override fun dismissFinished() {
        commands += "dismissFinished"
        mutableState.update { if (it.trip.phase == TripPhase.FINISHED) it.copy(trip = TripState()) else it }
    }

    override fun setUiVisible(visible: Boolean) {
        commands += "setUiVisible($visible)"
    }

    override fun refreshEnvironment() {
        commands += "refreshEnvironment"
    }

    override suspend fun recoverInterruptedTrip(): Boolean {
        commands += "recoverInterruptedTrip"
        return false
    }

    private fun TrackingState.toTrip() = Trip(
        id = trip.tripId ?: "fake-trip",
        startedAtEpochMillis = trip.startedAtEpochMillis ?: START_TIME,
        endedAtEpochMillis = (trip.startedAtEpochMillis ?: START_TIME) + trip.stats.activeDurationMillis,
        stats = trip.stats,
    )

    private companion object {
        const val START_TIME = 1_792_929_600_000L
    }
}

/** An in-memory [SettingsRepository]. */
class FakeSettingsRepository(initial: AppSettings = AppSettings.Default) : SettingsRepository {
    private val current = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = current.asStateFlow()

    override suspend fun setSpeedUnit(unit: SpeedUnit) = current.update { it.copy(speedUnit = unit) }

    override suspend fun setThemeMode(mode: ThemeMode) = current.update { it.copy(themeMode = mode) }

    override suspend fun setDynamicColor(enabled: Boolean) = current.update { it.copy(dynamicColor = enabled) }

    override suspend fun setKeepScreenOn(enabled: Boolean) = current.update { it.copy(keepScreenOn = enabled) }
}

/** An in-memory [TripRepository] that keeps trips newest first, like the real one. */
class FakeTripRepository(initial: List<Trip> = emptyList()) : TripRepository {
    private val trips = MutableStateFlow(initial.sortedByDescending { it.startedAtEpochMillis })

    /** When set, [deleteTrip] throws it (to test failure handling). */
    var deleteFailure: Exception? = null

    override fun observeTrips(): Flow<List<Trip>> = trips.asStateFlow()

    override fun observeTrip(id: String): Flow<Trip?> = trips.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getTrip(id: String): Trip? = trips.value.firstOrNull { it.id == id }

    override suspend fun saveTrip(trip: Trip) {
        trips.update { list -> (list.filterNot { it.id == trip.id } + trip).sortedByDescending { it.startedAtEpochMillis } }
    }

    override suspend fun deleteTrip(id: String) {
        deleteFailure?.let { throw it }
        trips.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun deleteAllTrips() = trips.update { emptyList() }
}

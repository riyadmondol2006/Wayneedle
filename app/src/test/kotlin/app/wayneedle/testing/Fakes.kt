package app.wayneedle.testing

import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.LocationPermissionChecker
import app.wayneedle.domain.LocationSource
import app.wayneedle.domain.LocationSourceEvent
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TrackingController
import app.wayneedle.domain.TripCheckpointStore
import app.wayneedle.domain.TripForegroundLauncher
import app.wayneedle.domain.TripRepository
import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.LocationSample
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripCheckpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// Reusable in-memory fakes for the domain contracts. They follow the documented contract semantics (idempotent
// saves, newest-first ordering, single-slot checkpoint, cold location flow) and expose counters so tests can
// assert on interactions without a mocking library.

/** In-memory [TripRepository]. Saving the same id twice replaces; listing is newest first (by start time). */
class InMemoryTripRepository(initial: List<Trip> = emptyList()) : TripRepository {
    private val trips = MutableStateFlow(initial.associateBy { it.id })
    private val saves = AtomicInteger()

    /** How many times [saveTrip] was called, including failed attempts. */
    val saveCalls: Int get() = saves.get()

    /** When set, [saveTrip] throws it instead of storing (models a full disk or a closed database). */
    @Volatile
    var saveFailure: Throwable? = null

    /** Snapshot of the stored trips, newest first. */
    val stored: List<Trip> get() = trips.value.values.sortedWith(NEWEST_FIRST)

    override fun observeTrips(): Flow<List<Trip>> = trips.map { it.values.sortedWith(NEWEST_FIRST) }

    override fun observeTrip(id: String): Flow<Trip?> = trips.map { it[id] }

    override suspend fun getTrip(id: String): Trip? = trips.value[id]

    override suspend fun saveTrip(trip: Trip) {
        saves.incrementAndGet()
        saveFailure?.let { throw it }
        trips.update { it + (trip.id to trip) }
    }

    override suspend fun deleteTrip(id: String) {
        trips.update { it - id }
    }

    override suspend fun deleteAllTrips() {
        trips.value = emptyMap()
    }

    private companion object {
        val NEWEST_FIRST: Comparator<Trip> = compareByDescending<Trip> { it.startedAtEpochMillis }
            .thenByDescending { it.endedAtEpochMillis }
            .thenBy { it.id }
    }
}

/** In-memory single-slot [TripCheckpointStore] that remembers every write for cadence assertions. */
class InMemoryTripCheckpointStore(initial: TripCheckpoint? = null) : TripCheckpointStore {
    private val slot = MutableStateFlow(initial)
    private val history = CopyOnWriteArrayList<TripCheckpoint>()
    private val clears = AtomicInteger()

    /** The checkpoint a [load] would return right now. */
    val current: TripCheckpoint? get() = slot.value

    /** Every checkpoint ever passed to [save], oldest first. */
    val savedCheckpoints: List<TripCheckpoint> get() = history.toList()

    val clearCalls: Int get() = clears.get()

    /** When set, [save] throws it instead of storing. */
    @Volatile
    var saveFailure: Throwable? = null

    override suspend fun save(checkpoint: TripCheckpoint) {
        saveFailure?.let { throw it }
        history += checkpoint
        slot.value = checkpoint
    }

    override suspend fun load(): TripCheckpoint? = slot.value

    override suspend fun clear() {
        clears.incrementAndGet()
        slot.value = null
    }
}

/** Records foreground-service start/stop requests; both are idempotent like the real launcher. */
class FakeTripForegroundLauncher : TripForegroundLauncher {
    private val starts = AtomicInteger()
    private val stops = AtomicInteger()

    @Volatile
    var isRunning: Boolean = false
        private set

    val startCalls: Int get() = starts.get()
    val stopCalls: Int get() = stops.get()

    override fun start(): Boolean {
        starts.incrementAndGet()
        isRunning = true
        return true
    }

    override fun stop() {
        stops.incrementAndGet()
        isRunning = false
    }
}

class FakeLocationPermissionChecker(
    @Volatile var state: LocationPermissionState = LocationPermissionState.GRANTED_PRECISE,
) : LocationPermissionChecker {
    override fun current(): LocationPermissionState = state
}

/**
 * Scriptable [LocationSource] that honours the documented contract: a cold flow, the provider state emitted
 * first on collection, [LocationSourceEvent.PermissionDenied] followed by completion when permission is missing.
 * [maxConcurrentCollectors] lets a test prove that only one component ever subscribes.
 */
class FakeLocationSource(
    @Volatile var providerEnabled: Boolean = true,
    @Volatile var permissionGranted: Boolean = true,
) : LocationSource {
    private val shared = MutableSharedFlow<LocationSourceEvent>(extraBufferCapacity = 256)
    private val active = AtomicInteger()
    private val peak = AtomicInteger()
    private val started = AtomicInteger()

    @Volatile
    var lastRequestedIntervalMillis: Long? = null
        private set

    /** Collectors that are subscribed right now. */
    val activeCollectors: Int get() = active.get()

    /** The highest number of simultaneous collectors seen; the architecture requires this to stay at most 1. */
    val maxConcurrentCollectors: Int get() = peak.get()

    /** How many collections were ever started (a re-subscribe after release counts again). */
    val subscriptionCount: Int get() = started.get()

    override fun events(minIntervalMillis: Long): Flow<LocationSourceEvent> = flow {
        lastRequestedIntervalMillis = minIntervalMillis
        started.incrementAndGet()
        peak.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            if (!permissionGranted) {
                emit(LocationSourceEvent.PermissionDenied)
                return@flow
            }
            emit(LocationSourceEvent.ProviderEnabledChanged(providerEnabled))
            emitAll(shared)
        } finally {
            active.decrementAndGet()
        }
    }

    /** Delivers [event] to the current collector (dropped when nobody is collecting, like a real listener). */
    suspend fun emit(event: LocationSourceEvent) = shared.emit(event)

    suspend fun emitFix(sample: LocationSample) = emit(LocationSourceEvent.Fix(sample))

    /** Delivers a whole [scriptedRoute] in order. */
    suspend fun emitFixes(route: List<LocationSample>) = route.forEach { emitFix(it) }

    suspend fun setProviderEnabled(enabled: Boolean) {
        providerEnabled = enabled
        emit(LocationSourceEvent.ProviderEnabledChanged(enabled))
    }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings.Default) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    val current: AppSettings get() = state.value

    override val settings: Flow<AppSettings> = state

    override suspend fun setSpeedUnit(unit: SpeedUnit) = state.update { it.copy(speedUnit = unit) }

    override suspend fun setThemeMode(mode: ThemeMode) = state.update { it.copy(themeMode = mode) }

    override suspend fun setDynamicColor(enabled: Boolean) = state.update { it.copy(dynamicColor = enabled) }

    override suspend fun setKeepScreenOn(enabled: Boolean) = state.update { it.copy(keepScreenOn = enabled) }

    override suspend fun setSpeedAlertEnabled(enabled: Boolean) = state.update { it.copy(speedAlertEnabled = enabled) }

    override suspend fun setSpeedAlertMps(limitMps: Float) = state.update { it.copy(speedAlertMps = limitMps) }

    override suspend fun setUpdateCheckEnabled(enabled: Boolean) = state.update {
        it.copy(updateCheckEnabled = enabled)
    }
}

/**
 * [TrackingController] stand-in for UI and service tests: tests push states with [emit] and read back the
 * commands the unit under test sent. It does not simulate the state machine; use the real tracker for that.
 */
class FakeTrackingController(initial: TrackingState = TrackingState.Initial) : TrackingController {
    enum class Command {
        START,
        PAUSE,
        RESUME,
        FINISH,
        DISCARD,
        DISMISS_FINISHED,
        REFRESH_ENVIRONMENT,
        RECOVER,
        REPORT_BACKGROUND_UNAVAILABLE,
    }

    private val mutableState = MutableStateFlow(initial)
    private val log = CopyOnWriteArrayList<Command>()

    override val state: StateFlow<TrackingState> = mutableState.asStateFlow()

    /** Commands received so far, oldest first. */
    val commands: List<Command> get() = log.toList()

    @Volatile
    var uiVisible: Boolean = false
        private set

    /** Result returned by start, pause, resume and discard. */
    @Volatile
    var commandResult: CommandResult = CommandResult.Ok

    /** Produces the result of [finishTrip]; defaults to "not valid from the current phase". */
    @Volatile
    var finishResult: () -> FinishResult = { FinishResult.InvalidTransition(mutableState.value.trip.phase) }

    @Volatile
    var recoverResult: Boolean = false

    fun emit(state: TrackingState) {
        mutableState.value = state
    }

    fun update(transform: (TrackingState) -> TrackingState) = mutableState.update(transform)

    override fun startTrip(): CommandResult = record(Command.START)

    override fun pauseTrip(): CommandResult = record(Command.PAUSE)

    override fun resumeTrip(): CommandResult = record(Command.RESUME)

    override suspend fun finishTrip(): FinishResult {
        log += Command.FINISH
        return finishResult()
    }

    override fun discardTrip(): CommandResult = record(Command.DISCARD)

    override fun dismissFinished() {
        log += Command.DISMISS_FINISHED
    }

    override fun setUiVisible(visible: Boolean) {
        uiVisible = visible
    }

    override fun refreshEnvironment() {
        log += Command.REFRESH_ENVIRONMENT
    }

    override fun reportBackgroundRecordingUnavailable() {
        log += Command.REPORT_BACKGROUND_UNAVAILABLE
        mutableState.update { it.copy(backgroundRecordingUnavailable = true) }
    }

    override suspend fun recoverInterruptedTrip(): Boolean {
        log += Command.RECOVER
        return recoverResult
    }

    private fun record(command: Command): CommandResult {
        log += command
        return commandResult
    }
}

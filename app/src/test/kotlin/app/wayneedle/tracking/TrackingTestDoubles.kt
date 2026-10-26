@file:OptIn(ExperimentalCoroutinesApi::class)

package app.wayneedle.tracking

import app.wayneedle.domain.LocationPermissionChecker
import app.wayneedle.domain.LocationSource
import app.wayneedle.domain.LocationSourceEvent
import app.wayneedle.domain.TimeSource
import app.wayneedle.domain.TripCheckpointStore
import app.wayneedle.domain.TripForegroundLauncher
import app.wayneedle.domain.TripRepository
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripCheckpoint
import app.wayneedle.testing.aSample
import app.wayneedle.testing.latitudeAfterNorth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Location source that records how many collectors are active at once.
 *
 * @property teardownMillis virtual time a cancelled collection needs to unregister (a slow platform
 *   `removeUpdates`); the collector counts as active until then.
 */
class FakeLocationSource(
    var providerEnabled: Boolean = true,
    private val teardownMillis: Long = 0L,
) : LocationSource {
    private val events = MutableSharedFlow<LocationSourceEvent>(extraBufferCapacity = 256)
    private val active = AtomicInteger(0)
    private val maxActive = AtomicInteger(0)
    private val total = AtomicInteger(0)
    val requestedIntervals: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    val activeCollectors: Int get() = active.get()
    val maxConcurrentCollectors: Int get() = maxActive.get()
    val totalCollections: Int get() = total.get()

    /** True when a collector is subscribed and [send] would deliver. */
    val hasSubscriber: Boolean get() = events.subscriptionCount.value > 0

    override fun events(minIntervalMillis: Long): Flow<LocationSourceEvent> = flow {
        requestedIntervals += minIntervalMillis
        total.incrementAndGet()
        val nowActive = active.incrementAndGet()
        maxActive.accumulateAndGet(nowActive) { a, b -> maxOf(a, b) }
        try {
            emit(LocationSourceEvent.ProviderEnabledChanged(providerEnabled))
            // Per the contract the flow completes right after PermissionDenied.
            emitAll(
                events.transformWhile {
                    emit(it)
                    it != LocationSourceEvent.PermissionDenied
                },
            )
        } finally {
            if (teardownMillis > 0L) withContext(NonCancellable) { delay(teardownMillis) }
            active.decrementAndGet()
        }
    }

    /** Delivers [event] to the active collector; fails if nobody listens (catches "listener released"). */
    fun send(event: LocationSourceEvent) {
        check(events.subscriptionCount.value > 0) { "No active location collector" }
        check(events.tryEmit(event)) { "Event buffer full" }
    }
}

class FakePermissionChecker(var state: LocationPermissionState = LocationPermissionState.GRANTED_PRECISE) :
    LocationPermissionChecker {
    override fun current(): LocationPermissionState = state
}

class InMemoryTripRepository : TripRepository {
    private val trips = MutableStateFlow<Map<String, Trip>>(emptyMap())
    var saveCalls = 0
        private set

    /** When set, saveTrip throws it (and keeps throwing until cleared). */
    var saveFailure: Exception? = null

    /** When set, saveTrip suspends until it completes. */
    var saveGate: CompletableDeferred<Unit>? = null

    /** When set, getTrip throws it. */
    var getFailure: Exception? = null

    /** When set, getTrip suspends until it completes. */
    var getGate: CompletableDeferred<Unit>? = null

    val stored: Map<String, Trip> get() = trips.value

    override fun observeTrips(): Flow<List<Trip>> =
        trips.map { all -> all.values.sortedByDescending { it.startedAtEpochMillis } }

    override fun observeTrip(id: String): Flow<Trip?> = trips.map { it[id] }

    override suspend fun getTrip(id: String): Trip? {
        getGate?.await()
        getFailure?.let { throw it }
        return trips.value[id]
    }

    override suspend fun saveTrip(trip: Trip) {
        saveCalls++
        saveGate?.await()
        saveFailure?.let { throw it }
        trips.value += trip.id to trip
    }

    override suspend fun deleteTrip(id: String) {
        trips.value -= id
    }

    override suspend fun deleteAllTrips() {
        trips.value = emptyMap()
    }
}

class InMemoryCheckpointStore(initial: TripCheckpoint? = null) : TripCheckpointStore {
    var checkpoint: TripCheckpoint? = initial
        private set

    /** Every checkpoint actually written, in order. */
    val writes: MutableList<TripCheckpoint> = mutableListOf()
    var clearCalls = 0
        private set
    var saveCalls = 0
        private set

    /** When set, save() suspends (after being called) until it completes: an in-flight write. */
    var saveGate: CompletableDeferred<Unit>? = null
    var failSaves = false
    var failClears = false
    var failLoads = false

    override suspend fun save(checkpoint: TripCheckpoint) {
        saveCalls++
        saveGate?.await()
        if (failSaves) throw IOException("disk full")
        this.checkpoint = checkpoint
        writes += checkpoint
    }

    override suspend fun load(): TripCheckpoint? {
        if (failLoads) throw IOException("database locked")
        return checkpoint
    }

    override suspend fun clear() {
        clearCalls++
        if (failClears) throw IOException("disk full")
        checkpoint = null
    }
}

class RecordingForegroundLauncher : TripForegroundLauncher {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun start() {
        calls += "start"
    }

    override fun stop() {
        calls += "stop"
    }
}

/** Clock driven by the coroutine test scheduler, so tickers (`delay`) and timestamps stay in sync. */
class SchedulerTimeSource(
    private val scheduler: TestCoroutineScheduler,
    private val epochBaseMillis: Long = 1_700_000_000_000L,
) : TimeSource {
    val nowMillis: Long get() = scheduler.currentTime

    override fun elapsedRealtimeNanos(): Long = scheduler.currentTime * 1_000_000L

    override fun epochMillis(): Long = epochBaseMillis + scheduler.currentTime
}

/** A [TripTracker] wired to fakes inside a [TestScope]; the tracker runs in `backgroundScope`. */
class TrackerHarness(
    private val testScope: TestScope,
    val config: TrackerConfig = TrackerConfig(),
    val source: FakeLocationSource = FakeLocationSource(),
    val permissions: FakePermissionChecker = FakePermissionChecker(),
    val repository: InMemoryTripRepository = InMemoryTripRepository(),
    val checkpoints: InMemoryCheckpointStore = InMemoryCheckpointStore(),
    val foreground: RecordingForegroundLauncher = RecordingForegroundLauncher(),
) {
    val time = SchedulerTimeSource(testScope.testScheduler)
    val tracker = TripTracker(
        locationSource = source,
        permissionChecker = permissions,
        tripRepository = repository,
        checkpointStore = checkpoints,
        foreground = foreground,
        timeSource = time,
        scope = testScope.backgroundScope,
        config = config,
    )

    var latitude = 52.0
    var longitude = 4.0

    val state: TrackingState get() = tracker.state.value

    fun runCurrent() = testScope.runCurrent()

    /** Advances virtual time and runs everything due at the new time. */
    fun advance(millis: Long) {
        testScope.advanceTimeBy(millis)
        testScope.runCurrent()
    }

    fun send(event: LocationSourceEvent) {
        testScope.runCurrent()
        source.send(event)
        testScope.runCurrent()
    }

    /** Emits a fix stamped "now" at the current position. */
    fun fix(
        speedMps: Float? = 10f,
        horizontalAccuracyM: Float? = 5f,
        speedAccuracyMps: Float? = 0.5f,
        ageMillis: Long = 0L,
    ) {
        send(
            LocationSourceEvent.Fix(
                aSample(
                    latitude = latitude,
                    longitude = longitude,
                    horizontalAccuracyM = horizontalAccuracyM,
                    speedMps = speedMps,
                    speedAccuracyMps = speedAccuracyMps,
                    elapsedRealtimeMillis = time.nowMillis - ageMillis,
                ),
            ),
        )
    }

    /** Drives north at [speedMps] for [seconds] one-second steps, emitting a consistent fix each second. */
    fun drive(seconds: Int, speedMps: Float, horizontalAccuracyM: Float = 5f) {
        repeat(seconds) {
            advance(1_000L)
            latitude = latitudeAfterNorth(latitude, speedMps.toDouble())
            fix(speedMps = speedMps, horizontalAccuracyM = horizontalAccuracyM)
        }
    }

    /** Makes the UI visible and lets the GPS collection start. */
    fun showUi() {
        tracker.setUiVisible(true)
        runCurrent()
    }
}

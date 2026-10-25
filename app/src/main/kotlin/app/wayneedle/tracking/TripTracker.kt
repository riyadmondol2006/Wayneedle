package app.wayneedle.tracking

import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.LocationPermissionChecker
import app.wayneedle.domain.LocationSource
import app.wayneedle.domain.LocationSourceEvent
import app.wayneedle.domain.TimeSource
import app.wayneedle.domain.TrackingController
import app.wayneedle.domain.TripCheckpointStore
import app.wayneedle.domain.TripForegroundLauncher
import app.wayneedle.domain.TripRepository
import app.wayneedle.domain.model.GpsStatus
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.LocationSample
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

/**
 * The [TrackingController] implementation and the only subscriber of [LocationSource].
 *
 * **Threading.** All mutable state is guarded by one lock and every mutation (commands, GPS events,
 * ticks) happens while holding it, so there is exactly one writer at a time and [state] is published in
 * mutation order. Commands are thread-safe; the non-suspend ones complete synchronously under the lock
 * rather than launching coroutines that could reorder. [TripForegroundLauncher] is called under the lock
 * so start/stop calls keep their order; it must not call back into this controller synchronously.
 *
 * **GPS listener.** Exactly one `events()` collection exists while the listener is wanted, i.e. the UI
 * is visible or the trip is RUNNING, and precise permission is granted. It is cancelled otherwise
 * (including PAUSED in the background). A new collection first joins the cancelled previous one, so two
 * platform listeners never overlap, and events from a superseded collection are ignored.
 *
 * **Live speed.** Every accepted fix updates [LiveSpeed.Available] (smoothed for display, raw for
 * statistics), in every phase. It becomes [LiveSpeed.Unavailable] when the receiver says the speed is
 * unknown (see [RejectReason.clearsLiveSpeed]), when no accepted fix arrived for
 * [TrackerConfig.liveSpeedTimeoutMillis], and whenever the listener is released or GPS is lost.
 * While the listener is wanted a ticker re-publishes the state every [TrackerConfig.tickIntervalMillis]
 * so the duration advances and stale readings are downgraded even when no fixes arrive.
 *
 * **Signal quality** describes the latest *current* fix even when it is too inaccurate to be used
 * (accuracy above 25 m shows as POOR, so "weak signal" differs from "no signal"). NONE means no current
 * fix for [SignalQualityConfig.staleAfterMillis], provider off, or listener released.
 *
 * **Losing GPS mid-trip** (provider switched off, permission revoked): the trip stays RUNNING and its
 * duration keeps counting, because the user did not ask to pause and the trip is still under way. The
 * distance anchor is dropped (`awaitingFix = true`), so no distance accrues while blind and the first fix
 * after GPS returns is a fresh anchor; the unobserved path is not guessed. A mere signal gap with the
 * provider on (tunnel) keeps the anchor, and the segment across the gap is counted only if it is
 * physically plausible (see [DistanceConfig]).
 *
 * **Checkpoints** are written on every phase change and after every [TrackerConfig.checkpointIntervalMillis]
 * of active time, through [CheckpointWriter] (single FIFO writer + trip generation), so a late write can
 * never resurrect a trip after finish/discard cleared its checkpoint.
 *
 * **Finishing.** [finishTrip] first freezes the trip as PAUSED (statistics stop changing and other
 * commands are rejected while the save is in flight), then saves it. On success the phase becomes
 * FINISHED, the checkpoint is cleared and the foreground service stopped. On failure the trip stays
 * PAUSED with statistics and checkpoint intact. The save is not cancelled if the caller is.
 *
 * **Recovery** restores a checkpoint as PAUSED with `recovered = true`, never starts the foreground
 * service and never counts the unobserved gap (at most the last checkpoint interval of observed time is
 * lost as well). `recovered` is cleared by [resumeTrip]. A checkpoint whose trip already exists in the
 * repository (the process died between saving and clearing) is cleared instead of restored.
 */
class TripTracker(
    private val locationSource: LocationSource,
    private val permissionChecker: LocationPermissionChecker,
    private val tripRepository: TripRepository,
    checkpointStore: TripCheckpointStore,
    private val foreground: TripForegroundLauncher,
    private val timeSource: TimeSource,
    private val scope: CoroutineScope,
    private val config: TrackerConfig = TrackerConfig(),
) : TrackingController {
    private val lock = ReentrantLock()

    // Everything below is guarded by [lock].
    private val engine = TripEngine(config.distance)
    private val filter = GpsFixFilter(config.fixFilter)
    private val smoother = SpeedSmoother(config.smoother)
    private val classifier = GpsSignalClassifier(config.signalQuality)
    private var permission: LocationPermissionState = permissionChecker.current()
    private var providerEnabled = GpsStatus.Initial.providerEnabled
    private var satellitesUsed: Int? = null
    private var satellitesInView: Int? = null
    private var latestFixNanos: Long? = null
    private var latestFixAccuracyM: Float? = null
    private var lastAcceptedFixNanos: Long? = null
    private var liveSpeed: LiveSpeed = LiveSpeed.Unavailable
    private var uiVisible = false
    private var finishing = false
    private var tripGeneration = 0L
    private var lastCheckpointActiveMillis = 0L
    private var jobIds = NO_JOB
    private var collectionJob: Job? = null
    private var activeCollectionId = NO_JOB
    private var tickerJob: Job? = null
    private var activeTickerId = NO_JOB

    private val checkpoints = CheckpointWriter(checkpointStore, scope) { generation ->
        lock.withLock { generation == tripGeneration }
    }

    private val mutableState = MutableStateFlow(lock.withLock { buildStateLocked(nowNanos()) })
    override val state: StateFlow<TrackingState> = mutableState.asStateFlow()

    override fun startTrip(): CommandResult {
        return lock.withLock {
            val phase = engine.phase
            if (finishing || (phase != TripPhase.IDLE && phase != TripPhase.FINISHED)) {
                return CommandResult.InvalidTransition(phase)
            }
            permission = permissionChecker.current()
            if (!permission.canMeasureSpeed) {
                updateListenerLocked()
                publishLocked()
                return CommandResult.PermissionRequired
            }
            if (!providerEnabled) {
                updateListenerLocked()
                publishLocked()
                return CommandResult.LocationDisabled
            }
            val now = nowNanos()
            engine.start(UUID.randomUUID().toString(), now, timeSource.epochMillis())
            tripGeneration++
            foreground.start()
            writeCheckpointLocked(now)
            updateListenerLocked()
            publishLocked(now)
            CommandResult.Ok
        }
    }

    override fun pauseTrip(): CommandResult {
        return lock.withLock {
            val phase = engine.phase
            if (finishing || phase != TripPhase.RUNNING) return CommandResult.InvalidTransition(phase)
            val now = nowNanos()
            engine.pause(now)
            writeCheckpointLocked(now)
            updateListenerLocked()
            publishLocked(now)
            CommandResult.Ok
        }
    }

    override fun resumeTrip(): CommandResult {
        return lock.withLock {
            val phase = engine.phase
            if (finishing || phase != TripPhase.PAUSED) return CommandResult.InvalidTransition(phase)
            // A location foreground service cannot start without location permission (Android 14+).
            permission = permissionChecker.current()
            if (!permission.canMeasureSpeed) {
                updateListenerLocked()
                publishLocked()
                return CommandResult.PermissionRequired
            }
            val now = nowNanos()
            engine.resume(now)
            // Idempotent; needed because a recovered trip resumes without a running service.
            foreground.start()
            writeCheckpointLocked(now)
            updateListenerLocked()
            publishLocked(now)
            CommandResult.Ok
        }
    }

    override suspend fun finishTrip(): FinishResult {
        val trip = lock.withLock {
            val phase = engine.phase
            if (finishing || (phase != TripPhase.RUNNING && phase != TripPhase.PAUSED)) {
                return FinishResult.InvalidTransition(phase)
            }
            val now = nowNanos()
            if (phase == TripPhase.RUNNING) {
                engine.pause(now)
                writeCheckpointLocked(now)
                updateListenerLocked()
            }
            finishing = true
            publishLocked(now)
            Trip(
                id = checkNotNull(engine.tripId),
                startedAtEpochMillis = checkNotNull(engine.startedAtEpochMillis),
                endedAtEpochMillis = timeSource.epochMillis(),
                stats = engine.stats(now),
            )
        }
        return withContext(NonCancellable) { persistFinishedTrip(trip) }
    }

    private suspend fun persistFinishedTrip(trip: Trip): FinishResult {
        var result: FinishResult? = null
        try {
            val failure = try {
                tripRepository.saveTrip(trip)
                null
            } catch (e: Exception) {
                e
            }
            val outcome = lock.withLock {
                finishing = false
                if (failure != null) {
                    publishLocked()
                    FinishResult.SaveFailed(failure)
                } else {
                    val now = nowNanos()
                    engine.finish(now)
                    tripGeneration++
                    checkpoints.clear()
                    foreground.stop()
                    updateListenerLocked()
                    publishLocked(now)
                    FinishResult.Saved(trip)
                }
            }
            result = outcome
            return outcome
        } finally {
            if (result == null) {
                lock.withLock {
                    finishing = false
                    publishLocked()
                }
            }
        }
    }

    override fun discardTrip(): CommandResult {
        return lock.withLock {
            val phase = engine.phase
            if (finishing || (phase != TripPhase.RUNNING && phase != TripPhase.PAUSED)) {
                return CommandResult.InvalidTransition(phase)
            }
            engine.discard()
            tripGeneration++
            checkpoints.clear()
            foreground.stop()
            updateListenerLocked()
            publishLocked()
            CommandResult.Ok
        }
    }

    override fun dismissFinished() {
        lock.withLock {
            if (engine.dismissFinished()) publishLocked()
        }
    }

    override fun setUiVisible(visible: Boolean) {
        lock.withLock {
            if (uiVisible == visible) return
            uiVisible = visible
            updateListenerLocked()
            publishLocked()
        }
    }

    override fun refreshEnvironment() {
        lock.withLock {
            permission = permissionChecker.current()
            updateListenerLocked()
            publishLocked()
        }
    }

    override suspend fun recoverInterruptedTrip(): Boolean {
        val generationAtRequest = lock.withLock {
            if (finishing || engine.phase != TripPhase.IDLE) return false
            tripGeneration
        }
        // Queued behind any pending save/clear, so a just-discarded trip cannot come back.
        val checkpoint = try {
            checkpoints.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false
        val alreadySaved = try {
            tripRepository.getTrip(checkpoint.tripId) != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        lock.withLock {
            if (finishing || engine.phase != TripPhase.IDLE || tripGeneration != generationAtRequest) return false
            if (alreadySaved || !engine.restore(checkpoint)) {
                checkpoints.clear()
                return false
            }
            tripGeneration++
            val now = nowNanos()
            writeCheckpointLocked(now)
            updateListenerLocked()
            publishLocked(now)
            return true
        }
    }

    private fun updateListenerLocked() {
        val wanted = uiVisible || engine.phase == TripPhase.RUNNING
        if (wanted && activeTickerId == NO_JOB) {
            startTickerLocked()
        } else if (!wanted && activeTickerId != NO_JOB) {
            stopTickerLocked()
        }
        val collect = wanted && permission.canMeasureSpeed
        if (collect && activeCollectionId == NO_JOB) {
            startCollectionLocked()
        } else if (!collect && activeCollectionId != NO_JOB) {
            stopCollectionLocked()
            loseSignalLocked()
        }
    }

    private fun startCollectionLocked() {
        val id = ++jobIds
        activeCollectionId = id
        filter.reset()
        val previous = collectionJob
        collectionJob = scope.launch {
            previous?.cancelAndJoin()
            try {
                locationSource.events(config.locationIntervalMillis).collect { event ->
                    lock.withLock { if (activeCollectionId == id) onSourceEventLocked(event) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failing source ends the collection like a completed one (handled below).
            } finally {
                lock.withLock { if (activeCollectionId == id) onCollectionEndedLocked() }
            }
        }
    }

    private fun stopCollectionLocked() {
        activeCollectionId = NO_JOB
        collectionJob?.cancel()
    }

    /** The source completed on its own (e.g. after PermissionDenied). Restarted only by a later command. */
    private fun onCollectionEndedLocked() {
        activeCollectionId = NO_JOB
        loseSignalLocked()
        publishLocked()
    }

    private fun startTickerLocked() {
        val id = ++jobIds
        activeTickerId = id
        tickerJob = scope.launch {
            while (true) {
                delay(config.tickIntervalMillis)
                val current = lock.withLock {
                    (activeTickerId == id).also { if (it) onTickLocked() }
                }
                if (!current) break
            }
        }
    }

    private fun stopTickerLocked() {
        activeTickerId = NO_JOB
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun onTickLocked() {
        val now = nowNanos()
        checkpointIfDueLocked(now)
        publishLocked(now)
    }

    private fun onSourceEventLocked(event: LocationSourceEvent) {
        when (event) {
            is LocationSourceEvent.Fix -> onFixLocked(event.sample)

            is LocationSourceEvent.ProviderEnabledChanged -> {
                providerEnabled = event.enabled
                if (!event.enabled) loseSignalLocked()
                publishLocked()
            }

            is LocationSourceEvent.Satellites -> {
                satellitesUsed = event.used.takeIf { it >= 0 }
                satellitesInView = event.inView.takeIf { it >= 0 }
                publishLocked()
            }

            LocationSourceEvent.PermissionDenied -> {
                val current = permissionChecker.current()
                // The platform just refused us, whatever the checker claims: wait for refreshEnvironment.
                permission = if (current.canMeasureSpeed) LocationPermissionState.DENIED else current
                stopCollectionLocked()
                loseSignalLocked()
                publishLocked()
            }
        }
    }

    private fun onFixLocked(sample: LocationSample) {
        val now = nowNanos()
        val decision = filter.evaluate(sample, now)
        if (decision !is FixDecision.Rejected || decision.reason.isCurrentFix) {
            // A current fix from the GPS provider proves it is on, even if a provider callback was missed.
            providerEnabled = true
            latestFixNanos = sample.elapsedRealtimeNanos
            latestFixAccuracyM = sample.horizontalAccuracyM?.takeIf { it.isFinite() && it >= 0f }
        }
        when (decision) {
            is FixDecision.Accepted -> {
                val fix = decision.fix
                lastAcceptedFixNanos = fix.elapsedRealtimeNanos
                liveSpeed = LiveSpeed.Available(
                    smoothedMps = smoother.update(fix.speedMps, fix.elapsedRealtimeNanos),
                    rawMps = fix.speedMps,
                )
                engine.onAcceptedFix(fix)
            }

            is FixDecision.Rejected -> if (decision.reason.clearsLiveSpeed) {
                liveSpeed = LiveSpeed.Unavailable
                smoother.reset()
            }
        }
        checkpointIfDueLocked(now)
        publishLocked(now)
    }

    /** No usable GPS any more: forget live readings and make the next fix a fresh distance anchor. */
    private fun loseSignalLocked() {
        engine.onSignalLost()
        filter.reset()
        smoother.reset()
        liveSpeed = LiveSpeed.Unavailable
        latestFixNanos = null
        latestFixAccuracyM = null
        lastAcceptedFixNanos = null
        satellitesUsed = null
        satellitesInView = null
    }

    private fun checkpointIfDueLocked(now: Long) {
        if (engine.phase != TripPhase.RUNNING) return
        if (engine.activeDurationMillis(now) - lastCheckpointActiveMillis >= config.checkpointIntervalMillis) {
            writeCheckpointLocked(now)
        }
    }

    private fun writeCheckpointLocked(now: Long) {
        val checkpoint = engine.checkpoint(now, timeSource.epochMillis()) ?: return
        checkpoints.save(checkpoint, tripGeneration)
        lastCheckpointActiveMillis = checkpoint.stats.activeDurationMillis
    }

    private fun publishLocked(now: Long = nowNanos()) {
        val lastAccepted = lastAcceptedFixNanos
        if (liveSpeed is LiveSpeed.Available &&
            (lastAccepted == null || now - lastAccepted > config.liveSpeedTimeoutMillis * NANOS_PER_MILLI)
        ) {
            liveSpeed = LiveSpeed.Unavailable
            smoother.reset()
        }
        mutableState.value = buildStateLocked(now)
    }

    private fun buildStateLocked(now: Long): TrackingState {
        val fixAgeMillis = latestFixNanos?.let { max(0L, now - it) / NANOS_PER_MILLI }
        return TrackingState(
            permission = permission,
            gps = GpsStatus(
                providerEnabled = providerEnabled,
                quality = classifier.classify(providerEnabled, latestFixAccuracyM, fixAgeMillis, satellitesUsed),
                horizontalAccuracyM = latestFixAccuracyM,
                lastFixAgeMillis = fixAgeMillis,
                satellitesUsed = satellitesUsed,
                satellitesInView = satellitesInView,
            ),
            liveSpeed = liveSpeed,
            trip = engine.snapshot(now),
        )
    }

    private fun nowNanos(): Long = timeSource.elapsedRealtimeNanos()

    private companion object {
        const val NO_JOB = 0L
    }
}

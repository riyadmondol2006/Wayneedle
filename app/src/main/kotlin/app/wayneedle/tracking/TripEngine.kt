package app.wayneedle.tracking

import app.wayneedle.domain.model.TripCheckpoint
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripState
import app.wayneedle.domain.model.TripStats
import kotlin.math.max
import kotlin.math.min

/** Anti-drift rules for distance accumulation in [TripEngine]. */
data class DistanceConfig(
    val minNoiseFloorM: Double = MIN_NOISE_FLOOR_M,
    val accuracyNoiseFactor: Double = ACCURACY_NOISE_FACTOR,
    val stationarySpeedMps: Float = FixFilterConfig.STATIONARY_SPEED_MPS,
    val segmentToleranceMps: Double = SEGMENT_TOLERANCE_MPS,
    val segmentRelativeTolerance: Double = SEGMENT_RELATIVE_TOLERANCE,
    val gapMillis: Long = GAP_MILLIS,
) {
    companion object {
        /** Displacement below this is never counted, however good the accuracy. */
        const val MIN_NOISE_FLOOR_M: Double = 3.0

        /**
         * Noise floor = max([MIN_NOISE_FLOOR_M], factor × (accuracyA + accuracyB)), i.e. the mean of
         * the two 68 % radii. Shorter displacements keep the previous anchor, so slow but real movement
         * is still counted once it exceeds the floor, while jitter around a point never is.
         */
        const val ACCURACY_NOISE_FACTOR: Double = 0.5

        /**
         * A segment is counted only if its implied speed is at most
         * `v + SEGMENT_TOLERANCE_MPS + SEGMENT_RELATIVE_TOLERANCE × v + (accA + accB) / dt`, where v is the
         * higher reported speed at either end. Guards long gaps (tunnels), which the filter's
         * consistency check does not cover, against bogus jumps.
         */
        const val SEGMENT_TOLERANCE_MPS: Double = 5.0
        const val SEGMENT_RELATIVE_TOLERANCE: Double = 0.5

        /**
         * A segment longer than this spans a GPS gap (tunnel, garage, urban canyon). The fix filter did not
         * cross-check its end against its start, so the end could be a glitch: the segment is counted only
         * once the next fix agrees with it. A stationary fix at the end of a gap does not void the segment
         * (the car stopped at a light after the tunnel; the tunnel was still driven).
         */
        const val GAP_MILLIS: Long = FixFilterConfig.RELATIVE_CHECK_WINDOW_MILLIS
    }
}

/**
 * The trip state machine and statistics accumulator. Pure and deterministic: no clocks, no coroutines,
 * no I/O; every time is passed in (monotonic `elapsedRealtimeNanos` basis, wall clock only for display).
 * Not thread-safe; [TripTracker] serialises access.
 *
 * Phases: IDLE|FINISHED -start-> RUNNING -pause-> PAUSED -resume-> RUNNING; RUNNING|PAUSED -finish->
 * FINISHED; RUNNING|PAUSED -discard-> IDLE; FINISHED -dismissFinished-> IDLE; IDLE -restore-> PAUSED.
 * Invalid transitions return false and change nothing.
 *
 * Statistics change only while RUNNING:
 * * Active duration is the sum of the RUNNING intervals measured on the monotonic clock.
 * * Max speed is the highest speed *held* across two consecutive accepted fixes received while RUNNING
 *   (the lower of the pair), where the second fix was checked against the first
 *   ([Corroboration.PREVIOUS_FIX]). A single glitched reading therefore never becomes the trip's top
 *   speed, even one within physical acceleration limits; a real peak loses at most the difference to the
 *   neighbouring 1 Hz reading.
 * * Distance is summed between consecutive accepted fixes. The first fix after start or resume, and
 *   the first after [onSignalLost], is only an anchor. While the reported speed is 0 at both ends the
 *   anchor follows the fix without adding distance (a parked phone accumulates nothing); displacement
 *   below the accuracy noise floor keeps the anchor; implausible segments re-anchor without adding
 *   distance; a segment across a gap ([DistanceConfig.GAP_MILLIS]) waits for the next fix to confirm it.
 */
class TripEngine(private val config: DistanceConfig = DistanceConfig()) {
    private class Anchor(
        val latitude: Double,
        val longitude: Double,
        val horizontalAccuracyM: Float,
        val speedMps: Float,
        val elapsedRealtimeNanos: Long,
    )

    var phase: TripPhase = TripPhase.IDLE
        private set
    var tripId: String? = null
        private set
    var startedAtEpochMillis: Long? = null
        private set

    private var distanceMeters = 0.0
    private var maxSpeedMps = 0f
    private var closedActiveNanos = 0L
    private var runningSinceNanos = 0L
    private var anchor: Anchor? = null
    private var recovered = false

    /** Distance of a segment across a GPS gap, waiting for the next fix to confirm where the gap ended. */
    private var pendingGapMeters = 0.0

    /** Speed of the previous accepted fix while RUNNING; the top speed is taken over consecutive pairs. */
    private var previousFixSpeedMps: Float? = null

    /** RUNNING and no anchor yet since start/resume/signal loss: distance is not accumulating. */
    val awaitingFix: Boolean get() = phase == TripPhase.RUNNING && anchor == null

    fun start(newTripId: String, nowNanos: Long, nowEpochMillis: Long): Boolean {
        if (phase != TripPhase.IDLE && phase != TripPhase.FINISHED) return false
        resetStatistics()
        tripId = newTripId
        startedAtEpochMillis = nowEpochMillis
        phase = TripPhase.RUNNING
        runningSinceNanos = nowNanos
        return true
    }

    fun pause(nowNanos: Long): Boolean {
        if (phase != TripPhase.RUNNING) return false
        closeRunningInterval(nowNanos)
        phase = TripPhase.PAUSED
        return true
    }

    /** Resumes the same trip; distance waits for a fresh anchor. Clears [TripState.recovered]. */
    fun resume(nowNanos: Long): Boolean {
        if (phase != TripPhase.PAUSED) return false
        phase = TripPhase.RUNNING
        runningSinceNanos = nowNanos
        forgetAnchor()
        recovered = false
        return true
    }

    /** Freezes the statistics in the FINISHED state (kept for the summary until dismissed or restarted). */
    fun finish(nowNanos: Long): Boolean {
        if (phase != TripPhase.RUNNING && phase != TripPhase.PAUSED) return false
        if (phase == TripPhase.RUNNING) closeRunningInterval(nowNanos)
        phase = TripPhase.FINISHED
        recovered = false
        return true
    }

    fun discard(): Boolean {
        if (phase != TripPhase.RUNNING && phase != TripPhase.PAUSED) return false
        clear()
        return true
    }

    fun dismissFinished(): Boolean {
        if (phase != TripPhase.FINISHED) return false
        clear()
        return true
    }

    /**
     * Restores an interrupted trip as PAUSED with recovered = true. Only from IDLE and only for a sane
     * checkpoint (RUNNING/PAUSED phase, non-blank id, finite non-negative statistics).
     */
    fun restore(checkpoint: TripCheckpoint): Boolean {
        if (phase != TripPhase.IDLE || !checkpoint.isRestorable()) return false
        resetStatistics()
        tripId = checkpoint.tripId
        startedAtEpochMillis = checkpoint.startedAtEpochMillis
        distanceMeters = checkpoint.stats.distanceMeters
        maxSpeedMps = checkpoint.stats.maxSpeedMps
        closedActiveNanos = checkpoint.stats.activeDurationMillis * NANOS_PER_MILLI
        phase = TripPhase.PAUSED
        recovered = true
        return true
    }

    /**
     * Applies an accepted fix. Ignored unless RUNNING.
     *
     * @return metres added to the trip distance (0 when the fix only anchored or was filtered).
     */
    fun onAcceptedFix(fix: AcceptedFix): Double {
        if (phase != TripPhase.RUNNING) return 0.0
        updateTopSpeed(fix)
        val previous = anchor
        if (previous == null) {
            anchor = fix.toAnchor()
            return 0.0
        }
        if (fix.elapsedRealtimeNanos <= previous.elapsedRealtimeNanos) return 0.0
        val meters = GeoMath.haversineMeters(previous.latitude, previous.longitude, fix.latitude, fix.longitude)
        val accuracySum = previous.horizontalAccuracyM.toDouble() + fix.horizontalAccuracyM
        val noiseFloor = max(config.minNoiseFloorM, config.accuracyNoiseFactor * accuracySum)
        val intervalNanos = fix.elapsedRealtimeNanos - previous.elapsedRealtimeNanos
        val seconds = intervalNanos / NANOS_PER_SECOND
        val reportedSpeed = max(previous.speedMps, fix.speedMps).toDouble()
        val plausibleSpeed = reportedSpeed * (1.0 + config.segmentRelativeTolerance) +
            config.segmentToleranceMps + accuracySum / seconds
        val consistent = meters < noiseFloor || meters / seconds <= plausibleSpeed

        // This fix confirms (or refutes) the end of a preceding gap segment.
        var added = if (consistent) pendingGapMeters else 0.0
        pendingGapMeters = 0.0
        val acrossGap = intervalNanos > config.gapMillis * NANOS_PER_MILLI
        val stationary = fix.speedMps < config.stationarySpeedMps
        val parked = stationary && (previous.speedMps < config.stationarySpeedMps || !acrossGap)
        when {
            parked -> anchor = fix.toAnchor()

            // Keep the anchor so that slow movement adds up once it exceeds the floor.
            meters < noiseFloor -> Unit

            !consistent -> anchor = fix.toAnchor()

            acrossGap -> {
                pendingGapMeters = meters
                anchor = fix.toAnchor()
            }

            else -> {
                added += meters
                anchor = fix.toAnchor()
            }
        }
        distanceMeters += added
        return added
    }

    /** GPS became unavailable (provider off, permission lost): the next accepted fix is a fresh anchor. */
    fun onSignalLost() {
        forgetAnchor()
    }

    private fun updateTopSpeed(fix: AcceptedFix) {
        val previous = previousFixSpeedMps
        if (previous != null && fix.corroboration == Corroboration.PREVIOUS_FIX) {
            maxSpeedMps = max(maxSpeedMps, min(previous, fix.speedMps))
        }
        previousFixSpeedMps = fix.speedMps
    }

    private fun forgetAnchor() {
        anchor = null
        pendingGapMeters = 0.0
        previousFixSpeedMps = null
    }

    fun activeDurationMillis(nowNanos: Long): Long {
        val open = if (phase == TripPhase.RUNNING) max(0L, nowNanos - runningSinceNanos) else 0L
        return (closedActiveNanos + open) / NANOS_PER_MILLI
    }

    fun stats(nowNanos: Long): TripStats = TripStats(
        distanceMeters = distanceMeters,
        activeDurationMillis = activeDurationMillis(nowNanos),
        maxSpeedMps = maxSpeedMps,
    )

    fun snapshot(nowNanos: Long): TripState = if (phase == TripPhase.IDLE) {
        TripState()
    } else {
        TripState(
            phase = phase,
            tripId = tripId,
            startedAtEpochMillis = startedAtEpochMillis,
            stats = stats(nowNanos),
            awaitingFix = awaitingFix,
            recovered = recovered,
        )
    }

    /** The persisted form of the in-progress trip; null unless RUNNING or PAUSED. */
    fun checkpoint(nowNanos: Long, nowEpochMillis: Long): TripCheckpoint? {
        if (phase != TripPhase.RUNNING && phase != TripPhase.PAUSED) return null
        val id = tripId ?: return null
        val started = startedAtEpochMillis ?: return null
        return TripCheckpoint(
            tripId = id,
            phase = phase,
            startedAtEpochMillis = started,
            stats = stats(nowNanos),
            savedAtEpochMillis = nowEpochMillis,
        )
    }

    private fun closeRunningInterval(nowNanos: Long) {
        closedActiveNanos += max(0L, nowNanos - runningSinceNanos)
        forgetAnchor()
    }

    private fun resetStatistics() {
        distanceMeters = 0.0
        maxSpeedMps = 0f
        closedActiveNanos = 0L
        runningSinceNanos = 0L
        forgetAnchor()
        recovered = false
    }

    private fun clear() {
        resetStatistics()
        tripId = null
        startedAtEpochMillis = null
        phase = TripPhase.IDLE
    }

    private fun AcceptedFix.toAnchor() = Anchor(
        latitude,
        longitude,
        horizontalAccuracyM,
        speedMps,
        elapsedRealtimeNanos,
    )

    private companion object {
        fun TripCheckpoint.isRestorable(): Boolean =
            (phase == TripPhase.RUNNING || phase == TripPhase.PAUSED) &&
                tripId.isNotBlank() &&
                stats.distanceMeters.isFinite() && stats.distanceMeters >= 0.0 &&
                stats.maxSpeedMps.isFinite() && stats.maxSpeedMps >= 0f &&
                stats.activeDurationMillis >= 0L &&
                stats.activeDurationMillis <= Long.MAX_VALUE / NANOS_PER_MILLI
    }
}

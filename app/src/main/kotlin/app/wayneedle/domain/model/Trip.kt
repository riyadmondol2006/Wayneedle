package app.wayneedle.domain.model

enum class TripPhase {
    IDLE,
    RUNNING,
    PAUSED,
    FINISHED,
}

/**
 * Accumulated statistics of a trip. All values only ever change while the trip is [TripPhase.RUNNING].
 *
 * @property distanceMeters distance from accepted GPS fixes (never includes paused time/movement).
 * @property activeDurationMillis time spent RUNNING; paused time is excluded.
 * @property maxSpeedMps highest accepted GPS speed recorded while RUNNING.
 * @property movingDurationMillis the part of the active time spent actually moving: the time of the
 *   stretches that counted as distance, so stops at lights and a parked phone are left out. Null for trips
 *   recorded before it was measured.
 * @property ascentMeters total climb while moving, from GPS altitude with hysteresis; approximate (phone
 *   altitude is a few metres less precise than position). Null when the receiver gave no usable altitude.
 * @property descentMeters total descent, like [ascentMeters].
 */
data class TripStats(
    val distanceMeters: Double = 0.0,
    val activeDurationMillis: Long = 0L,
    val maxSpeedMps: Float = 0f,
    val movingDurationMillis: Long? = null,
    val ascentMeters: Double? = null,
    val descentMeters: Double? = null,
) {
    /** Total distance ÷ active duration (paused time left out). Zero until some active time has elapsed. */
    val averageSpeedMps: Float
        get() = if (activeDurationMillis <= 0L) 0f else (distanceMeters / (activeDurationMillis / 1_000.0)).toFloat()

    /** Total distance ÷ moving time: the average while actually under way. Null until something moved. */
    val movingAverageSpeedMps: Float?
        get() {
            val moving = movingDurationMillis ?: return null
            return if (moving <= 0L) null else (distanceMeters / (moving / 1_000.0)).toFloat()
        }

    /**
     * Wall-clock time from [startedAtEpochMillis] to [endEpochMillis], paused time included. Never less than
     * [activeDurationMillis], so a clock adjustment during the trip cannot make the total shorter than the
     * time that was actually recorded.
     */
    fun totalDurationMillis(startedAtEpochMillis: Long, endEpochMillis: Long): Long =
        (endEpochMillis - startedAtEpochMillis).coerceAtLeast(activeDurationMillis)

    companion object {
        val Empty = TripStats()
    }
}

/**
 * The trip as seen by the UI.
 *
 * @property tripId stable UUID string created at start; makes persistence idempotent. Null while IDLE.
 * @property awaitingFix RUNNING, but no fresh reliable fix has arrived since start/resume, so distance
 *   is not accumulating yet.
 * @property recovered true when restored from a checkpoint after the process died mid-trip.
 *   Recovered trips always come back PAUSED; the unobserved gap is never counted.
 */
data class TripState(
    val phase: TripPhase = TripPhase.IDLE,
    val tripId: String? = null,
    val startedAtEpochMillis: Long? = null,
    val stats: TripStats = TripStats.Empty,
    val awaitingFix: Boolean = false,
    val recovered: Boolean = false,
)

/** A finished trip as stored in Room. */
data class Trip(
    val id: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val stats: TripStats,
) {
    /** Start to finish, paused time included; [TripStats.activeDurationMillis] is the part spent recording. */
    val totalDurationMillis: Long
        get() = stats.totalDurationMillis(startedAtEpochMillis, endedAtEpochMillis)
}

/** Persisted snapshot of an in-progress trip, used to recover from process death. */
data class TripCheckpoint(
    val tripId: String,
    /** Only [TripPhase.RUNNING] or [TripPhase.PAUSED]. */
    val phase: TripPhase,
    val startedAtEpochMillis: Long,
    val stats: TripStats,
    val savedAtEpochMillis: Long,
)

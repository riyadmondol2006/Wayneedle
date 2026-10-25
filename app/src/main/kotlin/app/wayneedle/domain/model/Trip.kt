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
 */
data class TripStats(
    val distanceMeters: Double = 0.0,
    val activeDurationMillis: Long = 0L,
    val maxSpeedMps: Float = 0f,
) {
    /** Total distance ÷ active duration, as specified. Zero until some active time has elapsed. */
    val averageSpeedMps: Float
        get() = if (activeDurationMillis <= 0L) 0f else (distanceMeters / (activeDurationMillis / 1_000.0)).toFloat()

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
)

/** Persisted snapshot of an in-progress trip, used to recover from process death. */
data class TripCheckpoint(
    val tripId: String,
    /** Only [TripPhase.RUNNING] or [TripPhase.PAUSED]. */
    val phase: TripPhase,
    val startedAtEpochMillis: Long,
    val stats: TripStats,
    val savedAtEpochMillis: Long,
)

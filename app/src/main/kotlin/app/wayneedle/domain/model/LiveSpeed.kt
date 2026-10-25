package app.wayneedle.domain.model

/**
 * The speed shown on the speedometer. Works whether or not a trip is recording.
 *
 * There is deliberately no "0 km/h" fallback for missing data: [Unavailable] is an explicit state,
 * while a genuine GPS reading of standing still is [Available] with 0 m/s.
 */
sealed interface LiveSpeed {
    /** No reliable speed right now (no permission, GPS off, searching, stale or rejected fix). */
    data object Unavailable : LiveSpeed

    /**
     * @property smoothedMps lightly smoothed speed for display (low latency; snaps to 0 when stopped).
     * @property rawMps the latest accepted GPS speed, unsmoothed. Trip statistics use this one.
     */
    data class Available(val smoothedMps: Float, val rawMps: Float) : LiveSpeed
}

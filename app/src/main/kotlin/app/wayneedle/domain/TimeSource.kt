package app.wayneedle.domain

/** Injectable clock so time-dependent logic is deterministic in tests. */
interface TimeSource {
    /** Monotonic time (`SystemClock.elapsedRealtimeNanos` basis). Same basis as `LocationSample.elapsedRealtimeNanos`. */
    fun elapsedRealtimeNanos(): Long

    /** Wall-clock UTC milliseconds, for display and persistence only. */
    fun epochMillis(): Long
}

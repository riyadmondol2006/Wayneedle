package app.wayneedle.tracking

/** All tunables of [TripTracker]. The defaults are the production values. */
data class TrackerConfig(
    val fixFilter: FixFilterConfig = FixFilterConfig(),
    val smoother: SpeedSmootherConfig = SpeedSmootherConfig(),
    val signalQuality: SignalQualityConfig = SignalQualityConfig(),
    val distance: DistanceConfig = DistanceConfig(),
    val locationIntervalMillis: Long = LOCATION_INTERVAL_MILLIS,
    val tickIntervalMillis: Long = TICK_INTERVAL_MILLIS,
    val backgroundTickIntervalMillis: Long = BACKGROUND_TICK_INTERVAL_MILLIS,
    val liveSpeedTimeoutMillis: Long = LIVE_SPEED_TIMEOUT_MILLIS,
    val checkpointIntervalMillis: Long = CHECKPOINT_INTERVAL_MILLIS,
) {
    init {
        require(locationIntervalMillis > 0L) { "locationIntervalMillis must be positive" }
        require(tickIntervalMillis > 0L) { "tickIntervalMillis must be positive" }
        require(backgroundTickIntervalMillis > 0L) { "backgroundTickIntervalMillis must be positive" }
        require(liveSpeedTimeoutMillis > 0L) { "liveSpeedTimeoutMillis must be positive" }
        require(checkpointIntervalMillis > 0L) { "checkpointIntervalMillis must be positive" }
    }

    companion object {
        /** GPS update interval requested from the platform: 1 Hz, the native rate of phone GNSS chips. */
        const val LOCATION_INTERVAL_MILLIS: Long = 1_000L

        /** State is re-emitted at this rate while the UI is visible, so the duration ticks without fixes. */
        const val TICK_INTERVAL_MILLIS: Long = 1_000L

        /**
         * The same while only a background trip needs it (nobody is looking): one wake-up every 5 s instead of every
         * second. Fixes still publish as they arrive; checkpoints are due every [CHECKPOINT_INTERVAL_MILLIS] anyway.
         */
        const val BACKGROUND_TICK_INTERVAL_MILLIS: Long = 5_000L

        /**
         * Live speed turns Unavailable when the last accepted fix is older than this. With the 1 s tick the
         * downgrade happens 4–5 s after the last good fix (about four missed updates).
         */
        const val LIVE_SPEED_TIMEOUT_MILLIS: Long = SignalQualityConfig.STALE_AFTER_MILLIS

        /** A checkpoint is written after this much additional active (RUNNING) time, besides phase changes. */
        const val CHECKPOINT_INTERVAL_MILLIS: Long = 5_000L
    }
}

package app.wayneedle.tracking

import kotlin.math.abs
import kotlin.math.pow

/**
 * Tuning for [SpeedSmoother]. Weights are per [NOMINAL_INTERVAL_MILLIS] (one 1 Hz GPS fix) and are
 * rescaled for other intervals so behaviour does not depend on the fix rate.
 */
data class SpeedSmootherConfig(
    val minWeight: Float = MIN_WEIGHT,
    val maxWeight: Float = MAX_WEIGHT,
    val fullResponseDeltaMps: Float = FULL_RESPONSE_DELTA_MPS,
    val stationarySpeedMps: Float = FixFilterConfig.STATIONARY_SPEED_MPS,
    val resetAfterGapMillis: Long = RESET_AFTER_GAP_MILLIS,
) {
    companion object {
        /**
         * Weight of a new reading when it is close to the displayed value: half way per fix, which hides
         * ±0.3 m/s Doppler jitter yet settles within ~2 fixes (no multi-second lag).
         */
        const val MIN_WEIGHT: Float = 0.5f

        /** Weight for large changes: the display jumps straight to the new reading. */
        const val MAX_WEIGHT: Float = 1f

        /** A change of this size or more (14.4 km/h) uses [MAX_WEIGHT]; smaller changes interpolate. */
        const val FULL_RESPONSE_DELTA_MPS: Float = 4f

        /** After a gap this long the old value says nothing about now; restart from the raw reading. */
        const val RESET_AFTER_GAP_MILLIS: Long = 3_000L

        const val NOMINAL_INTERVAL_MILLIS: Long = 1_000L
    }
}

/**
 * Display smoothing for speed: an adaptive exponential moving average that reacts faster the larger the
 * change, so real acceleration shows immediately while small jitter is damped.
 *
 * Guarantees:
 * * No overshoot: each output lies between the previous output and the new reading.
 * * No lag to zero: a reading below the stationary threshold sets the output to exactly 0.
 * * The first reading (or the first after a gap/reset) is passed through unchanged.
 *
 * Only for display. Trip statistics use the accepted raw speed. Not thread-safe; the owner serialises.
 */
class SpeedSmoother(private val config: SpeedSmootherConfig = SpeedSmootherConfig()) {
    private var smoothedMps: Float? = null
    private var lastTimestampNanos: Long = 0L

    /** Feeds an accepted speed taken at [elapsedRealtimeNanos] and returns the value to display. */
    fun update(speedMps: Float, elapsedRealtimeNanos: Long): Float {
        val previous = smoothedMps
        val intervalNanos = elapsedRealtimeNanos - lastTimestampNanos
        lastTimestampNanos = elapsedRealtimeNanos
        val next = when {
            speedMps < config.stationarySpeedMps -> 0f

            previous == null -> speedMps

            intervalNanos <= 0L || intervalNanos > config.resetAfterGapMillis * NANOS_PER_MILLI -> speedMps

            else -> {
                val delta = speedMps - previous
                val responsiveness = (abs(delta) / config.fullResponseDeltaMps).coerceIn(0f, 1f)
                val weightPerFix = config.minWeight + (config.maxWeight - config.minWeight) * responsiveness
                val fixes = intervalNanos.toDouble() / (SpeedSmootherConfig.NOMINAL_INTERVAL_MILLIS * NANOS_PER_MILLI)
                val weight = (1.0 - (1.0 - weightPerFix).pow(fixes)).toFloat().coerceIn(0f, 1f)
                // The clamp makes "no overshoot" exact despite float rounding.
                (previous + weight * delta).coerceIn(minOf(previous, speedMps), maxOf(previous, speedMps))
            }
        }
        smoothedMps = next
        return next
    }

    /** Forgets history; the next reading is shown unsmoothed. */
    fun reset() {
        smoothedMps = null
    }
}

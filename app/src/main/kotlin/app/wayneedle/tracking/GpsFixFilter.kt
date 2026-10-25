package app.wayneedle.tracking

import app.wayneedle.domain.model.LocationSample
import kotlin.math.abs
import kotlin.math.max

/**
 * Thresholds used by [GpsFixFilter]. The defaults (see the companion constants) target a phone GNSS
 * receiver delivering about one fix per second; every value is in SI units unless its name says otherwise.
 */
data class FixFilterConfig(
    val maxHorizontalAccuracyM: Float = MAX_HORIZONTAL_ACCURACY_M,
    val maxSpeedAccuracyMps: Float = MAX_SPEED_ACCURACY_MPS,
    val maxFixAgeMillis: Long = MAX_FIX_AGE_MILLIS,
    val maxFutureSkewMillis: Long = MAX_FUTURE_SKEW_MILLIS,
    val maxSpeedMps: Float = MAX_SPEED_MPS,
    val maxAccelerationMps2: Float = MAX_ACCELERATION_MPS2,
    val minAccelerationIntervalMillis: Long = MIN_ACCELERATION_INTERVAL_MILLIS,
    val relativeCheckWindowMillis: Long = RELATIVE_CHECK_WINDOW_MILLIS,
    val displacementToleranceMps: Float = DISPLACEMENT_TOLERANCE_MPS,
    val displacementRelativeTolerance: Float = DISPLACEMENT_RELATIVE_TOLERANCE,
    val stationarySpeedMps: Float = STATIONARY_SPEED_MPS,
) {
    companion object {
        /**
         * Worst acceptable 68 % horizontal accuracy radius. Open-sky phone fixes are 2–10 m; beyond 25 m
         * the receiver is in a degraded mode (urban canyon, indoors, few satellites) where both the
         * position and the derived speed are unreliable.
         */
        const val MAX_HORIZONTAL_ACCURACY_M: Float = 25f

        /** Worst acceptable 68 % speed accuracy, applied only when the fix reports one (11 km/h). */
        const val MAX_SPEED_ACCURACY_MPS: Float = 3f

        /** A fix older than this (vs `TimeSource`) is a late/cached delivery, not the current speed. */
        const val MAX_FIX_AGE_MILLIS: Long = 5_000L

        /** Fix timestamps share the `elapsedRealtimeNanos` clock; more than this ahead is a clock fault. */
        const val MAX_FUTURE_SKEW_MILLIS: Long = 1_000L

        /** Physical cap: 150 m/s = 540 km/h, beyond any road or rail vehicle. */
        const val MAX_SPEED_MPS: Float = 150f

        /**
         * Largest plausible speed change per second vs the previous accepted fix. Hard braking of a car
         * is ~10 m/s² (1 g); sports-car launches stay below that. Anything above is a receiver spike.
         */
        const val MAX_ACCELERATION_MPS2: Float = 12f

        /**
         * Intervals shorter than this are evaluated as this long by the acceleration check, so normal
         * speed noise between two closely spaced fixes is not mistaken for violent acceleration.
         */
        const val MIN_ACCELERATION_INTERVAL_MILLIS: Long = 1_000L

        /**
         * Relative checks (acceleration, displacement) only compare against an accepted fix at most this
         * old. After a longer gap the reference no longer says anything about the present, and limiting
         * the window guarantees the filter cannot lock itself out behind one bad reference fix.
         */
        const val RELATIVE_CHECK_WINDOW_MILLIS: Long = 5_000L

        /** Absolute slack (m/s) allowed between reported speed and the speed implied by displacement. */
        const val DISPLACEMENT_TOLERANCE_MPS: Float = 5f

        /** Relative slack, as a fraction of the higher reported speed, for the displacement check. */
        const val DISPLACEMENT_RELATIVE_TOLERANCE: Float = 0.5f

        /** Accepted speeds below this (1.8 km/h) are GNSS noise of a stationary receiver and become 0. */
        const val STATIONARY_SPEED_MPS: Float = 0.5f
    }
}

/**
 * Why a fix was rejected. Checks run in declaration order and the first failing one is reported.
 *
 * Every rejection discards the whole fix: neither its speed nor its position is used. A fix whose
 * speed cannot be trusted is not trusted for distance either, because the anti-drift rule that stops a
 * parked phone from accumulating distance relies on the reported speed.
 *
 * @property isCurrentFix the fix is a well-formed, current reading. It still updates the signal
 *   indicator (accuracy, fix age) even though its speed is not used.
 * @property clearsLiveSpeed the receiver itself says it cannot measure speed right now, so the display
 *   switches to `LiveSpeed.Unavailable` immediately. Outliers (spikes) do not clear the display: the
 *   last accepted reading stays until it ages out, so a single glitch does not blank the speedometer.
 */
enum class RejectReason(val isCurrentFix: Boolean, val clearsLiveSpeed: Boolean) {
    /** Timestamp not after the newest fix already seen (duplicate or out-of-order delivery). */
    NON_INCREASING_TIMESTAMP(isCurrentFix = false, clearsLiveSpeed = false),

    /** Older than [FixFilterConfig.maxFixAgeMillis]. */
    STALE(isCurrentFix = false, clearsLiveSpeed = false),

    /** More than [FixFilterConfig.maxFutureSkewMillis] ahead of the monotonic clock. */
    TIMESTAMP_IN_FUTURE(isCurrentFix = false, clearsLiveSpeed = false),

    /** Latitude/longitude not finite or out of range. */
    INVALID_COORDINATES(isCurrentFix = false, clearsLiveSpeed = false),

    /** No horizontal accuracy (or a non-finite / negative one). */
    MISSING_ACCURACY(isCurrentFix = true, clearsLiveSpeed = true),

    /** Horizontal accuracy worse than [FixFilterConfig.maxHorizontalAccuracyM]. */
    POOR_ACCURACY(isCurrentFix = true, clearsLiveSpeed = true),

    /** The fix carries no speed. Never replaced by 0. */
    MISSING_SPEED(isCurrentFix = true, clearsLiveSpeed = true),

    /** Speed is NaN, infinite or negative. */
    INVALID_SPEED(isCurrentFix = true, clearsLiveSpeed = true),

    /** Reported speed accuracy worse than [FixFilterConfig.maxSpeedAccuracyMps] (or non-finite). */
    POOR_SPEED_ACCURACY(isCurrentFix = true, clearsLiveSpeed = true),

    /** Speed above the physical cap [FixFilterConfig.maxSpeedMps]. */
    SPEED_ABOVE_CAP(isCurrentFix = true, clearsLiveSpeed = false),

    /** Speed change vs the previous accepted fix exceeds [FixFilterConfig.maxAccelerationMps2]. */
    ACCELERATION_SPIKE(isCurrentFix = true, clearsLiveSpeed = false),

    /** Reported speeds and the displacement since the previous accepted fix grossly disagree. */
    SPEED_DISPLACEMENT_MISMATCH(isCurrentFix = true, clearsLiveSpeed = false),
}

/**
 * A fix that passed every check, with its validated (non-null) values.
 *
 * @property speedMps accepted ground speed; values below [FixFilterConfig.stationarySpeedMps] are 0.
 */
data class AcceptedFix(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Float,
    val speedMps: Float,
    val elapsedRealtimeNanos: Long,
)

sealed interface FixDecision {
    data class Accepted(val fix: AcceptedFix) : FixDecision

    data class Rejected(val reason: RejectReason) : FixDecision
}

/**
 * Decides whether a GPS fix is reliable enough to show its speed and to use it for trip statistics.
 * Stateful (it remembers the newest timestamp and the last accepted fix); not thread-safe, the owner
 * serialises calls. Never invents a value: a fix without a trustworthy speed is rejected, not zeroed.
 *
 * Checks, in order (see [RejectReason]):
 * 1. Timing: strictly increasing timestamp, not stale, not in the future.
 * 2. Well-formed coordinates.
 * 3. Receiver-reported quality: horizontal accuracy present and good enough; speed present, finite,
 *    non-negative; speed accuracy good enough when reported.
 * 4. Physics: speed below the cap.
 * 5. Consistency with the previous accepted fix, only if it is at most
 *    [FixFilterConfig.relativeCheckWindowMillis] old: acceleration, then speed vs displacement.
 *
 * The displacement check compares the speed implied by the distance between the two fixes with the
 * reported speeds. Its tolerance includes both accuracy radii divided by the interval, so at 1 Hz with
 * ~5 m accuracy it only catches gross disagreement (position jumps of tens of metres). Single-sample
 * speed glitches are caught by the speed-accuracy, cap and acceleration checks instead.
 */
class GpsFixFilter(private val config: FixFilterConfig = FixFilterConfig()) {
    private class Reference(
        val latitude: Double,
        val longitude: Double,
        val horizontalAccuracyM: Float,
        val rawSpeedMps: Float,
        val elapsedRealtimeNanos: Long,
    )

    private var newestTimestampNanos: Long? = null
    private var lastAccepted: Reference? = null

    fun evaluate(sample: LocationSample, nowElapsedRealtimeNanos: Long): FixDecision {
        val timestamp = sample.elapsedRealtimeNanos
        val newest = newestTimestampNanos
        if (newest != null && timestamp <= newest) return reject(RejectReason.NON_INCREASING_TIMESTAMP)
        val ageNanos = nowElapsedRealtimeNanos - timestamp
        if (ageNanos > config.maxFixAgeMillis * NANOS_PER_MILLI) return reject(RejectReason.STALE)
        if (-ageNanos > config.maxFutureSkewMillis * NANOS_PER_MILLI) return reject(RejectReason.TIMESTAMP_IN_FUTURE)
        newestTimestampNanos = timestamp

        if (!GeoMath.isValidCoordinate(sample.latitude, sample.longitude)) {
            return reject(RejectReason.INVALID_COORDINATES)
        }
        val accuracy = sample.horizontalAccuracyM
        if (accuracy == null || !accuracy.isFinite() || accuracy < 0f) return reject(RejectReason.MISSING_ACCURACY)
        if (accuracy > config.maxHorizontalAccuracyM) return reject(RejectReason.POOR_ACCURACY)
        val speed = sample.speedMps ?: return reject(RejectReason.MISSING_SPEED)
        if (!speed.isFinite() || speed < 0f) return reject(RejectReason.INVALID_SPEED)
        val speedAccuracy = sample.speedAccuracyMps
        if (speedAccuracy != null &&
            (!speedAccuracy.isFinite() || speedAccuracy < 0f || speedAccuracy > config.maxSpeedAccuracyMps)
        ) {
            return reject(RejectReason.POOR_SPEED_ACCURACY)
        }
        if (speed > config.maxSpeedMps) return reject(RejectReason.SPEED_ABOVE_CAP)

        val reference = lastAccepted?.takeIf {
            timestamp - it.elapsedRealtimeNanos <= config.relativeCheckWindowMillis * NANOS_PER_MILLI
        }
        if (reference != null) {
            val intervalNanos = timestamp - reference.elapsedRealtimeNanos
            val accelerationSeconds =
                max(intervalNanos, config.minAccelerationIntervalMillis * NANOS_PER_MILLI) / NANOS_PER_SECOND
            if (abs(speed - reference.rawSpeedMps) / accelerationSeconds > config.maxAccelerationMps2) {
                return reject(RejectReason.ACCELERATION_SPIKE)
            }
            if (isDisplacementInconsistent(reference, sample, accuracy, speed, intervalNanos / NANOS_PER_SECOND)) {
                return reject(RejectReason.SPEED_DISPLACEMENT_MISMATCH)
            }
        }

        lastAccepted = Reference(sample.latitude, sample.longitude, accuracy, speed, timestamp)
        return FixDecision.Accepted(
            AcceptedFix(
                latitude = sample.latitude,
                longitude = sample.longitude,
                horizontalAccuracyM = accuracy,
                speedMps = if (speed < config.stationarySpeedMps) 0f else speed,
                elapsedRealtimeNanos = timestamp,
            ),
        )
    }

    /** Forgets all history, e.g. after the GPS listener was re-registered or the signal was lost. */
    fun reset() {
        newestTimestampNanos = null
        lastAccepted = null
    }

    private fun isDisplacementInconsistent(
        reference: Reference,
        sample: LocationSample,
        accuracy: Float,
        speed: Float,
        intervalSeconds: Double,
    ): Boolean {
        val distance = GeoMath.haversineMeters(reference.latitude, reference.longitude, sample.latitude, sample.longitude)
        val impliedSpeed = distance / intervalSeconds
        val meanReportedSpeed = (reference.rawSpeedMps + speed) / 2.0
        val positionSlack = (reference.horizontalAccuracyM + accuracy) / intervalSeconds
        val tolerance = config.displacementToleranceMps +
            config.displacementRelativeTolerance * max(reference.rawSpeedMps, speed) +
            positionSlack
        return abs(impliedSpeed - meanReportedSpeed) > tolerance
    }

    private fun reject(reason: RejectReason) = FixDecision.Rejected(reason)
}

internal const val NANOS_PER_MILLI: Long = 1_000_000L
internal const val NANOS_PER_SECOND: Double = 1_000_000_000.0

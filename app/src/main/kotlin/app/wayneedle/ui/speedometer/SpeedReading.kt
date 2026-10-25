package app.wayneedle.ui.speedometer

import androidx.compose.runtime.Immutable
import app.wayneedle.domain.model.GpsStatus
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.SpeedUnit

/** Why there is no speed to show. Drives the wording of the "no speed" state. */
enum class SpeedUnavailableReason {
    NO_GPS_HARDWARE,
    PERMISSION_REQUIRED,
    APPROXIMATE_ONLY,
    GPS_OFF,

    /** Everything is in place but there is no fresh reliable fix yet. */
    SEARCHING,
}

/** What the gauge displays: a real reading, or an explicit "no speed" state. Never a made-up zero. */
@Immutable
sealed interface SpeedReading {
    /** @property valueInUnit speed in the user's display unit (km/h or mph); 0 is a genuine standstill. */
    data class Available(val valueInUnit: Float) : SpeedReading

    data class Unavailable(val reason: SpeedUnavailableReason) : SpeedReading
}

fun speedUnavailableReason(
    permission: LocationPermissionState,
    gps: GpsStatus,
    hasGpsHardware: Boolean,
): SpeedUnavailableReason = when {
    !hasGpsHardware -> SpeedUnavailableReason.NO_GPS_HARDWARE
    permission == LocationPermissionState.DENIED -> SpeedUnavailableReason.PERMISSION_REQUIRED
    permission == LocationPermissionState.GRANTED_APPROXIMATE_ONLY -> SpeedUnavailableReason.APPROXIMATE_ONLY
    !gps.providerEnabled -> SpeedUnavailableReason.GPS_OFF
    else -> SpeedUnavailableReason.SEARCHING
}

/** Maps the engine's live speed to the display unit. [LiveSpeed.Unavailable] always carries a reason. */
fun speedReading(
    liveSpeed: LiveSpeed,
    unit: SpeedUnit,
    permission: LocationPermissionState,
    gps: GpsStatus,
    hasGpsHardware: Boolean,
): SpeedReading = when (liveSpeed) {
    is LiveSpeed.Available -> {
        val value = unit.fromMetersPerSecond(liveSpeed.smoothedMps.toDouble()).toFloat()
        if (value.isFinite()) {
            SpeedReading.Available(value.coerceAtLeast(0f))
        } else {
            SpeedReading.Unavailable(speedUnavailableReason(permission, gps, hasGpsHardware))
        }
    }

    LiveSpeed.Unavailable -> SpeedReading.Unavailable(speedUnavailableReason(permission, gps, hasGpsHardware))
}

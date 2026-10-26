package app.wayneedle.ui.settings

import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.SpeedUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** The alert limit moves in steps of this many km/h or mph, the granularity of speed limit signs. */
const val SPEED_ALERT_STEP: Int = 5

private const val EPSILON = 1e-4

/** The limit as shown to the user: a whole number in [unit]. */
fun speedAlertLimitInUnit(limitMps: Float, unit: SpeedUnit): Int =
    unit.fromMetersPerSecond(limitMps.toDouble()).roundToInt()

/**
 * The limit after [steps] presses of the minus (negative) or plus (positive) button: snapped to the step grid
 * of the user's unit, kept within the accepted range, returned in m/s for storage.
 */
fun steppedSpeedAlertLimit(currentMps: Float, unit: SpeedUnit, steps: Int): Float {
    val current = unit.fromMetersPerSecond(currentMps.toDouble())
    val snapped = (current / SPEED_ALERT_STEP).roundToInt() * SPEED_ALERT_STEP
    // The range is stored as Float m/s, so 5 km/h comes back as 5.0000001: allow for that rounding.
    val lowest = ceil(unit.fromMetersPerSecond(AppSettings.MIN_SPEED_ALERT_MPS.toDouble()) / SPEED_ALERT_STEP - EPSILON)
        .toInt() * SPEED_ALERT_STEP
    val highest =
        floor(unit.fromMetersPerSecond(AppSettings.MAX_SPEED_ALERT_MPS.toDouble()) / SPEED_ALERT_STEP + EPSILON)
            .toInt() * SPEED_ALERT_STEP
    val next = (snapped + steps * SPEED_ALERT_STEP).coerceIn(lowest, highest)
    return unit.toMetersPerSecond(next.toDouble()).toFloat()
}

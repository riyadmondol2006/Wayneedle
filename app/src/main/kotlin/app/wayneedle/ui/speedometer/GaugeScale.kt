package app.wayneedle.ui.speedometer

import app.wayneedle.domain.model.SpeedUnit
import kotlin.math.ceil

/**
 * The dial of the speedometer: its range and tick spacing in the user's unit.
 *
 * The range starts at 0–240 km/h or 0–150 mph and only ever grows (in whole major steps) when the
 * speed exceeds it, so the scale does not jump back and forth around a boundary.
 *
 * @property max upper end of the dial, in [unit]; always a multiple of [majorStep].
 * @property majorStep distance between labelled ticks.
 * @property minorStep distance between small ticks; divides [majorStep].
 */
data class GaugeScale(
    val unit: SpeedUnit,
    val max: Int,
    val majorStep: Int,
    val minorStep: Int,
) {
    /** Position of [valueInUnit] on the dial, clamped to 0..1. */
    fun fractionOf(valueInUnit: Float): Float = (valueInUnit / max).coerceIn(0f, 1f)

    /** This scale, or a larger one when [valueInUnit] (in [unit]) does not fit. Changing unit rebuilds it. */
    fun extendedFor(unit: SpeedUnit, valueInUnit: Float): GaugeScale = when {
        unit != this.unit -> forRequiredMax(unit, valueInUnit)
        valueInUnit <= max -> this
        else -> forRequiredMax(unit, valueInUnit)
    }

    companion object {
        private const val KMH_BASE_MAX = 240
        private const val KMH_MAJOR_STEP = 40
        private const val KMH_MINOR_STEP = 10
        private const val MPH_BASE_MAX = 150
        private const val MPH_MAJOR_STEP = 25
        private const val MPH_MINOR_STEP = 5

        /** More labelled intervals than this crowd the dial, so the step doubles. */
        private const val MAX_MAJOR_INTERVALS = 8

        fun base(unit: SpeedUnit): GaugeScale = forRequiredMax(unit, 0f)

        fun forRequiredMax(unit: SpeedUnit, requiredMax: Float): GaugeScale {
            val baseMax = if (unit == SpeedUnit.KMH) KMH_BASE_MAX else MPH_BASE_MAX
            var major = if (unit == SpeedUnit.KMH) KMH_MAJOR_STEP else MPH_MAJOR_STEP
            var minor = if (unit == SpeedUnit.KMH) KMH_MINOR_STEP else MPH_MINOR_STEP
            val safeRequired = if (requiredMax.isFinite()) requiredMax else 0f
            var max = maxOf(baseMax, roundUpTo(safeRequired, major))
            while (max / major > MAX_MAJOR_INTERVALS) {
                major *= 2
                minor *= 2
                max = roundUpTo(max.toFloat(), major)
            }
            return GaugeScale(unit, max, major, minor)
        }

        private fun roundUpTo(value: Float, step: Int): Int = (ceil(value / step) * step).toInt()
    }
}

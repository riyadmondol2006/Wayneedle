package app.wayneedle.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import app.wayneedle.R
import app.wayneedle.domain.model.SpeedUnit
import kotlin.math.roundToInt

/** Screen-reader wording for numbers: units are spelled out ("54 kilometres per hour", not "54 km/h"). */

@Composable
fun spokenSpeed(metersPerSecond: Float, unit: SpeedUnit, formatter: UiFormatter): String {
    val rounded = unit.fromMetersPerSecond(metersPerSecond.coerceAtLeast(0f).toDouble()).roundToInt()
    return pluralStringResource(unit.speedSpokenRes, rounded, formatter.speed(metersPerSecond, unit))
}

@Composable
fun spokenDistance(meters: Double, unit: SpeedUnit, formatter: UiFormatter): String =
    pluralStringResource(
        unit.distanceSpokenRes,
        pluralQuantityOf(unit.fromMeters(meters.coerceAtLeast(0.0))),
        formatter.distance(meters, unit),
    )

/** "1 hour 2 minutes 3 seconds"; leading zero units are left out. */
@Composable
fun spokenDuration(millis: Long, formatter: UiFormatter): String {
    val (hours, minutes, seconds) = formatter.durationParts(millis)
    val hourCount = hours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val parts = buildList {
        if (hours > 0) {
            add(pluralStringResource(R.plurals.spoken_hours, hourCount, formatter.count(hourCount)))
        }
        if (minutes > 0 || hours > 0) {
            add(pluralStringResource(R.plurals.spoken_minutes, minutes.toInt(), formatter.count(minutes.toInt())))
        }
        add(pluralStringResource(R.plurals.spoken_seconds, seconds.toInt(), formatter.count(seconds.toInt())))
    }
    return parts.joinToString(" ")
}

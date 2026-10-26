package app.wayneedle.ui.format

import app.wayneedle.domain.model.SpeedUnit
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong

/** Shown instead of a number when there is no trustworthy value. Never a fake zero. */
const val PLACEHOLDER = "—"

private const val METERS_TO_FEET = 3.280839895
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/**
 * Locale-aware number, duration and date formatting. Pure JVM (no Android imports) so it is covered by
 * unit tests. Output never contains `NaN` or `Infinity`: non-finite input yields [PLACEHOLDER].
 *
 * Instances hold `java.text` formatters and are meant to be used from one thread (the UI thread); create
 * one per locale with `remember(locale)`.
 */
class UiFormatter(val locale: Locale = Locale.getDefault()) {
    private val integerFormat: NumberFormat = NumberFormat.getIntegerInstance(locale).apply {
        isGroupingUsed = false
    }
    private val twoDecimalFormat: NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    private val dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
    private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val longDateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    /** A whole number in the user's speed unit, e.g. `54`. Genuine zero is `0`. */
    fun speed(metersPerSecond: Float, unit: SpeedUnit): String =
        if (!metersPerSecond.isFinite()) {
            PLACEHOLDER
        } else {
            speedInUnit(unit.fromMetersPerSecond(metersPerSecond.coerceAtLeast(0f).toDouble()))
        }

    /** A speed already expressed in the display unit (used for the gauge scale labels). */
    fun speedInUnit(valueInUnit: Double): String =
        if (!valueInUnit.isFinite()) PLACEHOLDER else integerFormat.format(valueInUnit.coerceAtLeast(0.0).roundToLong())

    /** Distance with two decimals in km or mi, e.g. `12.34`; the unit label is added by the caller. */
    fun distance(meters: Double, unit: SpeedUnit): String =
        if (!meters.isFinite()) PLACEHOLDER else twoDecimalFormat.format(unit.fromMeters(meters.coerceAtLeast(0.0)))

    /** Whole GPS accuracy radius in metres (km/h users) or feet (mph users). */
    fun accuracy(meters: Float, unit: SpeedUnit): String =
        if (!meters.isFinite()) PLACEHOLDER else integerFormat.format(accuracyInUnit(meters, unit))

    /** The number [accuracy] shows, for choosing the matching plural form. */
    fun accuracyInUnit(meters: Float, unit: SpeedUnit): Long {
        if (!meters.isFinite()) return 0L
        val value = when (unit) {
            SpeedUnit.KMH -> meters.toDouble()
            SpeedUnit.MPH -> meters * METERS_TO_FEET
        }
        return value.coerceAtLeast(0.0).roundToLong()
    }

    /** `H:MM:SS`; hours are not capped (100 h is `100:00:00`). Digits follow the locale. */
    fun duration(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0L) / MILLIS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return String.format(locale, "%d:%02d:%02d", hours, minutes, seconds)
    }

    /** Hours, minutes and seconds (rounded down) for spoken descriptions such as "1 hour 2 minutes". */
    fun durationParts(millis: Long): Triple<Long, Long, Long> {
        val totalSeconds = millis.coerceAtLeast(0L) / MILLIS_PER_SECOND
        return Triple(
            totalSeconds / SECONDS_PER_HOUR,
            totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE,
            totalSeconds % SECONDS_PER_MINUTE,
        )
    }

    /** A whole count, e.g. a number of satellites. */
    fun count(value: Int): String = integerFormat.format(value.toLong())

    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun date(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun longDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        longDateFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** A calendar day written out in full, e.g. "Saturday, 25 October 2026". */
    fun fullDate(date: LocalDate): String = longDateFormatter.format(date)

    fun time(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        timeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}

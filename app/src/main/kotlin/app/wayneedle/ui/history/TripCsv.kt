package app.wayneedle.ui.history

import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.Trip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Trips as CSV (RFC 4180: comma-separated, CRLF line ends, quotes only where needed), oldest first, so the
 * user can keep or analyse their history elsewhere. Numbers always use a dot and no grouping, whatever the
 * phone's language, so spreadsheets and scripts read them the same everywhere. Times are ISO 8601 with the
 * UTC offset of [zone]. Distances and speeds are in the user's [unit]; the header names the unit.
 */
fun tripsToCsv(trips: List<Trip>, unit: SpeedUnit, zone: ZoneId): String {
    val distanceUnit = if (unit == SpeedUnit.KMH) "km" else "mi"
    val speedUnit = if (unit == SpeedUnit.KMH) "kmh" else "mph"
    val header = listOf(
        "started",
        "ended",
        "distance_$distanceUnit",
        "active_seconds",
        "total_seconds",
        "top_speed_$speedUnit",
        "average_speed_$speedUnit",
        "trip_id",
    )
    val rows = trips.sortedBy { it.startedAtEpochMillis }.map { trip ->
        val stats = trip.stats
        listOf(
            isoTime(trip.startedAtEpochMillis, zone),
            isoTime(trip.endedAtEpochMillis, zone),
            number(unit.fromMeters(stats.distanceMeters), DISTANCE_DECIMALS),
            (stats.activeDurationMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND).toString(),
            (trip.totalDurationMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND).toString(),
            number(unit.fromMetersPerSecond(stats.maxSpeedMps.toDouble()), SPEED_DECIMALS),
            number(unit.fromMetersPerSecond(stats.averageSpeedMps.toDouble()), SPEED_DECIMALS),
            trip.id,
        )
    }
    return (listOf(header) + rows).joinToString(separator = LINE_END, postfix = LINE_END) { fields ->
        fields.joinToString(",") { csvField(it) }
    }
}

/** A suggested file name such as `wayneedle-trips-2026-10-26.csv`. */
fun csvFileName(nowEpochMillis: Long, zone: ZoneId): String =
    "wayneedle-trips-${Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()}.csv"

private const val LINE_END = "\r\n"
private const val MILLIS_PER_SECOND = 1_000L
private const val DISTANCE_DECIMALS = 3
private const val SPEED_DECIMALS = 1

private fun isoTime(epochMillis: Long, zone: ZoneId): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone).withNano(0))

private fun number(value: Double, decimals: Int): String =
    if (value.isFinite()) String.format(Locale.ROOT, "%.${decimals}f", value.coerceAtLeast(0.0)) else ""

/** Quotes a field only if it contains a comma, quote or line break; doubles embedded quotes. */
private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

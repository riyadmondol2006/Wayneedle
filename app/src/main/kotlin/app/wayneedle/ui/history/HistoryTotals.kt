package app.wayneedle.ui.history

import androidx.compose.runtime.Immutable
import app.wayneedle.domain.model.Trip

/** Lifetime figures over every stored trip, shown above the History list. */
@Immutable
data class HistoryTotals(
    val tripCount: Int,
    val distanceMeters: Double,
    val activeDurationMillis: Long,
    val topSpeedMps: Float,
)

/** Totals over [trips], or null when there are none. Values that cannot be right (NaN, negative) count as 0. */
fun historyTotals(trips: List<Trip>): HistoryTotals? {
    if (trips.isEmpty()) return null
    var distance = 0.0
    var active = 0L
    var top = 0f
    for (trip in trips) {
        val stats = trip.stats
        if (stats.distanceMeters.isFinite() && stats.distanceMeters > 0.0) distance += stats.distanceMeters
        if (stats.activeDurationMillis > 0L) active = saturatingAdd(active, stats.activeDurationMillis)
        if (stats.maxSpeedMps.isFinite() && stats.maxSpeedMps > top) top = stats.maxSpeedMps
    }
    return HistoryTotals(trips.size, distance, active, top)
}

private fun saturatingAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

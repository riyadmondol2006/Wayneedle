package app.wayneedle.ui.history

import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripStats
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryTotalsAndCsvTest {
    private val start = 1_792_929_600_000L // 2026-10-25T12:00:00Z
    private val defaultLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    private fun trip(
        id: String,
        startOffsetMillis: Long,
        stats: TripStats,
        totalMillis: Long = stats.activeDurationMillis,
    ) = Trip(id, start + startOffsetMillis, start + startOffsetMillis + totalMillis, stats)

    @Test
    fun `totals add every trip up`() {
        val totals = historyTotals(
            listOf(
                trip("a", 0L, TripStats(10_000.0, 600_000L, 20f)),
                trip("b", 3_600_000L, TripStats(2_500.0, 300_000L, 31.5f)),
            ),
        )!!
        assertEquals(2, totals.tripCount)
        assertEquals(12_500.0, totals.distanceMeters, 1e-9)
        assertEquals(900_000L, totals.activeDurationMillis)
        assertEquals(31.5f, totals.topSpeedMps)
    }

    @Test
    fun `no trips means no totals, and corrupt values never poison them`() {
        assertNull(historyTotals(emptyList()))
        val totals = historyTotals(
            listOf(
                trip("ok", 0L, TripStats(1_000.0, 60_000L, 10f)),
                trip("bad", 1L, TripStats(Double.NaN, -5L, Float.NaN)),
            ),
        )!!
        assertEquals(1_000.0, totals.distanceMeters, 1e-9)
        assertEquals(60_000L, totals.activeDurationMillis)
        assertEquals(10f, totals.topSpeedMps)
    }

    @Test
    fun `csv has a header with units, one row per trip, oldest first`() {
        val csv = tripsToCsv(
            listOf(
                trip("newer", 3_600_000L, TripStats(5_000.0, 600_000L, 20f), totalMillis = 900_000L),
                trip("older", 0L, TripStats(12_345.6, 1_800_000L, 33.3f)),
            ),
            SpeedUnit.KMH,
            ZoneOffset.UTC,
        )
        val lines = csv.split("\r\n")
        assertEquals(
            "started,ended,distance_km,active_seconds,total_seconds,top_speed_kmh,average_speed_kmh," +
                "moving_seconds,moving_average_speed_kmh,ascent_m,descent_m,trip_id",
            lines[0],
        )
        // Trips recorded before moving time and elevation existed leave those columns empty.
        assertEquals("2026-10-25T12:00:00Z,2026-10-25T12:30:00Z,12.346,1800,1800,119.9,24.7,,,,,older", lines[1])
        assertEquals("2026-10-25T13:00:00Z,2026-10-25T13:15:00Z,5.000,600,900,72.0,30.0,,,,,newer", lines[2])
        assertEquals("", lines[3], "ends with a line break")
        assertEquals(4, lines.size)
    }

    @Test
    fun `numbers use a dot even when the phone is set to a decimal-comma language`() {
        Locale.setDefault(Locale.GERMANY)
        val csv = tripsToCsv(listOf(trip("t", 0L, TripStats(1_500.0, 60_000L, 10f))), SpeedUnit.MPH, ZoneOffset.UTC)
        val row = csv.split("\r\n")[1]
        assertEquals("2026-10-25T12:00:00Z,2026-10-25T12:01:00Z,0.932,60,60,22.4,55.9,,,,,t", row)
        assertEquals("distance_mi", csv.split("\r\n")[0].split(",")[2])
    }

    @Test
    fun `a field with a comma or quote is quoted, and corrupt numbers stay empty`() {
        val csv = tripsToCsv(
            listOf(trip("odd,\"id\"", 0L, TripStats(Double.NaN, 60_000L, Float.NaN))),
            SpeedUnit.KMH,
            ZoneOffset.UTC,
        )
        assertEquals("2026-10-25T12:00:00Z,2026-10-25T12:01:00Z,,60,60,,,,,,,\"odd,\"\"id\"\"\"", csv.split("\r\n")[1])
    }

    @Test
    fun `the suggested file name carries the local date`() {
        assertEquals("wayneedle-trips-2026-10-25.csv", csvFileName(start, ZoneOffset.UTC))
    }

    @Test
    fun `moving time, moving average and elevation are exported in the user's unit`() {
        val stats = TripStats(
            distanceMeters = 12_000.0,
            activeDurationMillis = 1_200_000L,
            maxSpeedMps = 20f,
            movingDurationMillis = 1_000_000L,
            ascentMeters = 100.0,
            descentMeters = 30.5,
        )
        val metric = tripsToCsv(listOf(trip("t", 0L, stats)), SpeedUnit.KMH, ZoneOffset.UTC).split("\r\n")[1]
        assertEquals("2026-10-25T12:00:00Z,2026-10-25T12:20:00Z,12.000,1200,1200,72.0,36.0,1000,43.2,100,31,t", metric)
        val imperial = tripsToCsv(listOf(trip("t", 0L, stats)), SpeedUnit.MPH, ZoneOffset.UTC).split("\r\n")
        assertEquals("ascent_ft", imperial[0].split(",")[9])
        assertEquals("328", imperial[1].split(",")[9])
    }
}

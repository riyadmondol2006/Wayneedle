package app.wayneedle.domain

import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripStats
import kotlin.test.Test
import kotlin.test.assertEquals

class TripStatsTest {
    private val stats = TripStats(distanceMeters = 5_000.0, activeDurationMillis = 600_000L, maxSpeedMps = 12f)

    @Test
    fun `total time is the wall-clock span and includes paused time`() {
        // Recorded for 10 minutes inside a span of 25 minutes: 15 minutes were paused.
        assertEquals(1_500_000L, stats.totalDurationMillis(startedAtEpochMillis = 1_000L, endEpochMillis = 1_501_000L))
    }

    @Test
    fun `total time equals active time for a trip that was never paused`() {
        assertEquals(600_000L, stats.totalDurationMillis(startedAtEpochMillis = 0L, endEpochMillis = 600_000L))
    }

    @Test
    fun `total time is never shorter than the active time, even if the clock was set back`() {
        assertEquals(600_000L, stats.totalDurationMillis(startedAtEpochMillis = 10_000L, endEpochMillis = 5_000L))
    }

    @Test
    fun `a saved trip reports its own total time`() {
        val trip = Trip("id", startedAtEpochMillis = 100L, endedAtEpochMillis = 900_100L, stats = stats)
        assertEquals(900_000L, trip.totalDurationMillis)
        assertEquals(600_000L, trip.stats.activeDurationMillis)
    }
}

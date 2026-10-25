package app.wayneedle.domain

import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TripStats
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedUnitTest {
    @Test
    fun `metres per second converts to km per hour by 3_6`() {
        assertEquals(0.0, SpeedUnit.KMH.fromMetersPerSecond(0.0), 0.0)
        assertEquals(36.0, SpeedUnit.KMH.fromMetersPerSecond(10.0), 1e-9)
        assertEquals(100.0, SpeedUnit.KMH.fromMetersPerSecond(100.0 / 3.6), 1e-9)
    }

    @Test
    fun `metres per second converts to mph`() {
        // 26.8224 m/s is exactly 60 mph.
        assertEquals(60.0, SpeedUnit.MPH.fromMetersPerSecond(26.8224), 1e-6)
    }

    @Test
    fun `distance converts to km and miles`() {
        assertEquals(1.5, SpeedUnit.KMH.fromMeters(1_500.0), 1e-9)
        assertEquals(1.0, SpeedUnit.MPH.fromMeters(1_609.344), 1e-9)
    }

    @Test
    fun `average speed is distance over active duration`() {
        val stats = TripStats(distanceMeters = 1_000.0, activeDurationMillis = 100_000L, maxSpeedMps = 20f)
        assertEquals(10f, stats.averageSpeedMps, 1e-4f)
    }

    @Test
    fun `average speed is zero before any active time`() {
        assertEquals(0f, TripStats(distanceMeters = 5.0, activeDurationMillis = 0L).averageSpeedMps)
    }
}

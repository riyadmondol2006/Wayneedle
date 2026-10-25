package app.wayneedle.testing

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixturesTest {
    @Test
    fun `aSample keeps its documented defaults`() {
        val sample = aSample()
        assertEquals(52.0, sample.latitude)
        assertEquals(4.0, sample.longitude)
        assertEquals(5f, sample.horizontalAccuracyM)
        assertEquals(10f, sample.speedMps)
        assertEquals(0.5f, sample.speedAccuracyMps)
        assertEquals(0L, sample.elapsedRealtimeNanos)
        assertEquals(1_700_000_000_000L, sample.epochMillis)
        assertEquals(2_000_000_000L, aSample(elapsedRealtimeMillis = 2_000L).elapsedRealtimeNanos)
    }

    @Test
    fun `position helpers move by the requested metres`() {
        val from = aSample()
        val north = aSample(latitude = latitudeAfterNorth(52.0, 100.0))
        val east = aSample(longitude = longitudeAfterEast(52.0, 4.0, 100.0))
        assertEquals(100.0, distanceMeters(from, north), 0.01)
        assertEquals(100.0, distanceMeters(from, east), 0.01)
        assertTrue(north.latitude > from.latitude)
        assertTrue(east.longitude > from.longitude)
    }

    @Test
    fun `cruise builds evenly spaced fixes with a consistent speed`() {
        val route = scriptedRoute { cruise(steps = 5, metersPerStep = 10.0) }
        assertEquals(5, route.size)
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L), route.map { it.elapsedRealtimeNanos / 1_000_000L })
        assertTrue(route.all { it.speedMps == 10f })
        route.zipWithNext().forEach { (a, b) -> assertEquals(10.0, distanceMeters(a, b), 0.01) }
        assertEquals(40.0, routeLengthMeters(route), 0.05)
    }

    @Test
    fun `cruise derives the speed from displacement and interval`() {
        val route = scriptedRoute { cruise(steps = 2, metersPerStep = 5.0, intervalMillis = 500L) }
        assertTrue(route.all { it.speedMps == 10f })
        assertEquals(500L, route[0].elapsedRealtimeNanos / 1_000_000L)
    }

    @Test
    fun `leg reports the given speed whatever the displacement is`() {
        val route = scriptedRoute { leg(steps = 3, metersPerStep = 1.0, speedMps = 40f) }
        assertTrue(route.all { it.speedMps == 40f })
        assertEquals(2.0, routeLengthMeters(route), 0.01)
    }

    @Test
    fun `fix does not move or advance the clock, and null speed means no speed`() {
        val route = scriptedRoute(startElapsedRealtimeMillis = 7_000L) {
            fix(speedMps = null, horizontalAccuracyM = null, speedAccuracyMps = null)
        }
        val only = route.single()
        assertNull(only.speedMps)
        assertNull(only.horizontalAccuracyM)
        assertNull(only.speedAccuracyMps)
        assertEquals(7_000L, only.elapsedRealtimeNanos / 1_000_000L)
        assertEquals(52.0, only.latitude)
    }

    @Test
    fun `stationary adds time but no distance`() {
        val route = scriptedRoute {
            fix()
            stationary(steps = 4)
        }
        assertEquals(5, route.size)
        assertEquals(0.0, routeLengthMeters(route), 1e-9)
        assertEquals(4_000L, route.last().elapsedRealtimeNanos / 1_000_000L)
    }

    @Test
    fun `silence advances the clock and can move the vehicle without fixes`() {
        val route = scriptedRoute {
            fix()
            silence(millis = 10_000L, travelMeters = 100.0)
            fix()
        }
        assertEquals(2, route.size)
        assertEquals(10_000L, route[1].elapsedRealtimeNanos / 1_000_000L)
        assertEquals(100.0, distanceMeters(route[0], route[1]), 0.01)
    }

    @Test
    fun `heading steers legs east and keeps the metres per step`() {
        val route = scriptedRoute(headingDegrees = 90.0) {
            fix()
            cruise(steps = 3, metersPerStep = 20.0)
        }
        assertTrue(route.last().longitude > route.first().longitude)
        assertTrue(abs(route.last().latitude - route.first().latitude) < 1e-9)
        assertEquals(60.0, routeLengthMeters(route), 0.05)
    }

    @Test
    fun `heading can change between legs`() {
        val route = scriptedRoute {
            fix()
            cruise(steps = 1, metersPerStep = 30.0)
            heading(90.0)
            cruise(steps = 1, metersPerStep = 40.0)
        }
        // 30 m north then 40 m east: the straight line between start and end is the 3-4-5 hypotenuse, 50 m.
        assertEquals(50.0, distanceMeters(route.first(), route.last()), 0.05)
    }

    @Test
    fun `route builder can be aligned with a fake clock`() {
        var builderNow = 0L
        val route = scriptedRoute(startElapsedRealtimeMillis = 3_000L) {
            cruise(steps = 2, metersPerStep = 10.0)
            builderNow = nowMillis
        }
        val clock = FakeTimeSource(elapsedRealtimeMillis = builderNow)
        assertEquals(route.last().elapsedRealtimeNanos, clock.elapsedRealtimeNanos())
    }

    @Test
    fun `fake time source advances monotonic and wall clock together`() {
        val clock = FakeTimeSource(elapsedRealtimeMillis = 10L, wallClockMillis = 1_000L)
        clock.advanceMillis(5L)
        assertEquals(15_000_000L, clock.elapsedRealtimeNanos())
        assertEquals(1_015L, clock.epochMillis())
    }
}

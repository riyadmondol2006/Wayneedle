package app.wayneedle.tracking

import app.wayneedle.testing.latitudeAfterNorth
import app.wayneedle.testing.longitudeAfterEast
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeoMathTest {
    private val r = GeoMath.EARTH_MEAN_RADIUS_M

    @Test
    fun `identical points are zero apart`() {
        assertEquals(0.0, GeoMath.haversineMeters(52.37, 4.89, 52.37, 4.89))
    }

    @Test
    fun `one degree along a meridian is a 360th of the circumference`() {
        assertEquals(2 * PI * r / 360.0, GeoMath.haversineMeters(10.0, 20.0, 11.0, 20.0), 1e-6)
    }

    @Test
    fun `one degree along the equator equals one degree of latitude`() {
        assertEquals(2 * PI * r / 360.0, GeoMath.haversineMeters(0.0, 0.0, 0.0, 1.0), 1e-6)
    }

    @Test
    fun `equator to pole is a quarter circumference`() {
        assertEquals(PI * r / 2.0, GeoMath.haversineMeters(0.0, 30.0, 90.0, 30.0), 1e-6)
    }

    @Test
    fun `antipodal points are half a circumference and not NaN`() {
        assertEquals(PI * r, GeoMath.haversineMeters(45.0, 10.0, -45.0, -170.0), 1e-3)
    }

    @Test
    fun `crossing the antimeridian takes the short way`() {
        val expected = 0.001 * 2 * PI * r / 360.0
        assertEquals(expected, GeoMath.haversineMeters(0.0, 179.9995, 0.0, -179.9995), 1e-6)
    }

    @Test
    fun `long distances agree with the spherical law of cosines`() {
        // Amsterdam to New York: well conditioned for the law of cosines, an independent formula.
        val (lat1, lon1, lat2, lon2) = listOf(52.3676, 4.9041, 40.7128, -74.0060).map(Math::toRadians)
        val expected = r * acos(sin(lat1) * sin(lat2) + cos(lat1) * cos(lat2) * cos(lon2 - lon1))
        val actual = GeoMath.haversineMeters(52.3676, 4.9041, 40.7128, -74.0060)
        assertEquals(expected, actual, 0.01)
        assertTrue(actual in 5_850_000.0..5_880_000.0, "Amsterdam–New York is about 5 865 km, got $actual")
    }

    @Test
    fun `short hops are accurate to the millimetre`() {
        val oneMetreNorth = latitudeAfterNorth(52.0, 1.0)
        assertEquals(1.0, GeoMath.haversineMeters(52.0, 4.0, oneMetreNorth, 4.0), 1e-3)
        val tenMetresEast = longitudeAfterEast(52.0, 4.0, 10.0)
        assertEquals(10.0, GeoMath.haversineMeters(52.0, 4.0, 52.0, tenMetresEast), 1e-3)
    }

    @Test
    fun `coordinate validation`() {
        assertTrue(GeoMath.isValidCoordinate(-90.0, 180.0))
        assertFalse(GeoMath.isValidCoordinate(Double.NaN, 0.0))
        assertFalse(GeoMath.isValidCoordinate(0.0, Double.POSITIVE_INFINITY))
        assertFalse(GeoMath.isValidCoordinate(90.01, 0.0))
        assertFalse(GeoMath.isValidCoordinate(0.0, -180.01))
    }
}

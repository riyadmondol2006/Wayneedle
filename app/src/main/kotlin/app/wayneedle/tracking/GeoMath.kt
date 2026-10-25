package app.wayneedle.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical-earth geodesy. Pure functions, all distances in metres, all angles in degrees. */
object GeoMath {
    /**
     * IUGG mean earth radius (R1). The spherical model differs from the WGS-84 ellipsoid by at most
     * ~0.5 %, which is far below GPS position noise for consecutive fixes metres apart.
     */
    const val EARTH_MEAN_RADIUS_M: Double = 6_371_008.8

    /**
     * Great-circle distance between two points using the haversine formula, which stays numerically
     * stable for the very short distances between consecutive GPS fixes (unlike the spherical law of
     * cosines) and handles the antimeridian and antipodal points.
     */
    fun haversineMeters(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double {
        val phi1 = Math.toRadians(latitude1)
        val phi2 = Math.toRadians(latitude2)
        val halfDeltaPhi = (phi2 - phi1) / 2.0
        val halfDeltaLambda = Math.toRadians(longitude2 - longitude1) / 2.0
        val sinHalfPhi = sin(halfDeltaPhi)
        val sinHalfLambda = sin(halfDeltaLambda)
        val h = sinHalfPhi * sinHalfPhi + cos(phi1) * cos(phi2) * sinHalfLambda * sinHalfLambda
        // Rounding can push h marginally above 1 for antipodal points; asin would return NaN.
        return 2.0 * EARTH_MEAN_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** True when the pair is a real WGS-84 coordinate (finite, latitude ±90°, longitude ±180°). */
    fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

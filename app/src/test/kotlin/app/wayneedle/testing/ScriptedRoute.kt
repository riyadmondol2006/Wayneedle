package app.wayneedle.testing

import app.wayneedle.domain.model.LocationSample
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_008.8

/** Great-circle (haversine) distance in metres between two fixes. */
fun distanceMeters(from: LocationSample, to: LocationSample): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}

/** Sum of the distances between consecutive fixes of [route]. */
fun routeLengthMeters(route: List<LocationSample>): Double =
    route.zipWithNext { a, b -> distanceMeters(a, b) }.sum()

/**
 * Builds a deterministic sequence of GPS fixes from simple specs, e.g. "30 steps of 10 m each, 1 s apart,
 * reported at 10 m/s", so engine tests describe a drive instead of hand-computing coordinates.
 *
 * Every step first advances the clock by its interval, then moves the position, then records a fix. Positions
 * move along [heading] using the same flat-earth helpers as [latitudeAfterNorth] / [longitudeAfterEast], which
 * is accurate to far below a millimetre at the distances tests use.
 *
 * ```
 * val route = scriptedRoute {
 *     fix(speedMps = 0f)                            // first fix, standing still
 *     cruise(steps = 20, metersPerStep = 10.0)      // 20 s at 10 m/s
 *     silence(millis = 8_000)                       // tunnel: no fixes
 *     leg(steps = 5, metersPerStep = 10.0, speedMps = 50f)   // reported speed disagrees with displacement
 * }
 * ```
 */
class RouteBuilder(
    private var latitude: Double,
    private var longitude: Double,
    private var elapsedRealtimeMillis: Long,
    private var headingDegrees: Double,
    private val defaultHorizontalAccuracyM: Float?,
    private val defaultSpeedAccuracyMps: Float?,
) {
    private val samples = mutableListOf<LocationSample>()

    /** Current simulated elapsed-realtime, e.g. to line a `FakeTimeSource` up with the last fix. */
    val nowMillis: Long get() = elapsedRealtimeMillis

    /** Turns subsequent legs to compass [degrees] (0 = north, 90 = east). */
    fun heading(degrees: Double): RouteBuilder = apply { headingDegrees = degrees }

    /** Records a fix at the current position and time, without moving or advancing the clock. */
    fun fix(
        speedMps: Float? = 0f,
        horizontalAccuracyM: Float? = defaultHorizontalAccuracyM,
        speedAccuracyMps: Float? = defaultSpeedAccuracyMps,
    ): RouteBuilder = apply {
        samples += aSample(
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyM = horizontalAccuracyM,
            speedMps = speedMps,
            speedAccuracyMps = speedAccuracyMps,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
        )
    }

    /**
     * [steps] fixes, each [intervalMillis] after the previous one and [metersPerStep] further along the
     * heading, each reporting [speedMps] (`null` = the fix carries no speed).
     */
    fun leg(
        steps: Int,
        metersPerStep: Double,
        speedMps: Float?,
        intervalMillis: Long = 1_000L,
        horizontalAccuracyM: Float? = defaultHorizontalAccuracyM,
        speedAccuracyMps: Float? = defaultSpeedAccuracyMps,
    ): RouteBuilder = apply {
        require(steps >= 0) { "steps must not be negative" }
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        val radians = Math.toRadians(headingDegrees)
        repeat(steps) {
            elapsedRealtimeMillis += intervalMillis
            latitude = latitudeAfterNorth(latitude, metersPerStep * cos(radians))
            longitude = longitudeAfterEast(latitude, longitude, metersPerStep * sin(radians))
            fix(speedMps, horizontalAccuracyM, speedAccuracyMps)
        }
    }

    /** Like [leg], but the reported speed is exactly what the displacement implies (a consistent, honest GPS). */
    fun cruise(
        steps: Int,
        metersPerStep: Double,
        intervalMillis: Long = 1_000L,
        horizontalAccuracyM: Float? = defaultHorizontalAccuracyM,
        speedAccuracyMps: Float? = defaultSpeedAccuracyMps,
    ): RouteBuilder = leg(
        steps = steps,
        metersPerStep = metersPerStep,
        speedMps = (metersPerStep * 1_000.0 / intervalMillis).toFloat(),
        intervalMillis = intervalMillis,
        horizontalAccuracyM = horizontalAccuracyM,
        speedAccuracyMps = speedAccuracyMps,
    )

    /** [steps] fixes at the same spot, reporting [speedMps]: a parked or waiting vehicle. */
    fun stationary(
        steps: Int,
        intervalMillis: Long = 1_000L,
        speedMps: Float? = 0f,
        horizontalAccuracyM: Float? = defaultHorizontalAccuracyM,
    ): RouteBuilder = leg(steps, metersPerStep = 0.0, speedMps, intervalMillis, horizontalAccuracyM)

    /** Time passes with no fixes (tunnel, pause, GPS outage) and, optionally, the vehicle keeps moving. */
    fun silence(millis: Long, travelMeters: Double = 0.0): RouteBuilder = apply {
        require(millis >= 0) { "millis must not be negative" }
        elapsedRealtimeMillis += millis
        val radians = Math.toRadians(headingDegrees)
        latitude = latitudeAfterNorth(latitude, travelMeters * cos(radians))
        longitude = longitudeAfterEast(latitude, longitude, travelMeters * sin(radians))
    }

    fun build(): List<LocationSample> = samples.toList()
}

/** See [RouteBuilder]. Defaults match [aSample]: 5 m / 0.5 m/s accuracy, a point in the Netherlands, heading north. */
fun scriptedRoute(
    startLatitude: Double = 52.0,
    startLongitude: Double = 4.0,
    startElapsedRealtimeMillis: Long = 0L,
    headingDegrees: Double = 0.0,
    horizontalAccuracyM: Float? = 5f,
    speedAccuracyMps: Float? = 0.5f,
    script: RouteBuilder.() -> Unit,
): List<LocationSample> = RouteBuilder(
    latitude = startLatitude,
    longitude = startLongitude,
    elapsedRealtimeMillis = startElapsedRealtimeMillis,
    headingDegrees = headingDegrees,
    defaultHorizontalAccuracyM = horizontalAccuracyM,
    defaultSpeedAccuracyMps = speedAccuracyMps,
).apply(script).build()

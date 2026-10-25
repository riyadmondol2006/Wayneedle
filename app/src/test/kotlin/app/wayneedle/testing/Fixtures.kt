package app.wayneedle.testing

import app.wayneedle.domain.TimeSource
import app.wayneedle.domain.model.LocationSample
import kotlin.math.cos

private const val NANOS_PER_MILLI = 1_000_000L
private const val METERS_PER_DEGREE_LATITUDE = 111_194.93

/** Builds a good-quality fix by default; override only what a test cares about. */
fun aSample(
    latitude: Double = 52.0,
    longitude: Double = 4.0,
    horizontalAccuracyM: Float? = 5f,
    speedMps: Float? = 10f,
    speedAccuracyMps: Float? = 0.5f,
    elapsedRealtimeMillis: Long = 0L,
    epochMillis: Long = 1_700_000_000_000L + elapsedRealtimeMillis,
): LocationSample = LocationSample(
    latitude = latitude,
    longitude = longitude,
    horizontalAccuracyM = horizontalAccuracyM,
    speedMps = speedMps,
    speedAccuracyMps = speedAccuracyMps,
    elapsedRealtimeNanos = elapsedRealtimeMillis * NANOS_PER_MILLI,
    epochMillis = epochMillis,
)

/** Latitude after moving [meters] due north from [latitude] (positive = north). */
fun latitudeAfterNorth(latitude: Double, meters: Double): Double = latitude + meters / METERS_PER_DEGREE_LATITUDE

/** Longitude after moving [meters] due east at [latitude] (positive = east). */
fun longitudeAfterEast(latitude: Double, longitude: Double, meters: Double): Double =
    longitude + meters / (METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(latitude)))

/** Manually advanced clock. */
class FakeTimeSource(
    var elapsedRealtimeMillis: Long = 0L,
    var wallClockMillis: Long = 1_700_000_000_000L,
) : TimeSource {
    override fun elapsedRealtimeNanos(): Long = elapsedRealtimeMillis * NANOS_PER_MILLI

    override fun epochMillis(): Long = wallClockMillis + elapsedRealtimeMillis

    fun advanceMillis(delta: Long) {
        elapsedRealtimeMillis += delta
    }
}

package app.wayneedle.tracking

import app.wayneedle.domain.LocationSourceEvent
import app.wayneedle.domain.model.LocationSample

/**
 * Pure mapping from the values of an `android.location.Location` to a [LocationSample], kept out of
 * the platform adapter so it can be unit-tested on the JVM. An optional value is copied only when the
 * platform says it is present (`hasSpeed()` etc.); it is never defaulted to 0.
 */
internal fun locationSampleOf(
    latitude: Double,
    longitude: Double,
    hasAccuracy: Boolean,
    accuracyM: Float,
    hasSpeed: Boolean,
    speedMps: Float,
    hasSpeedAccuracy: Boolean,
    speedAccuracyMps: Float,
    elapsedRealtimeNanos: Long,
    epochMillis: Long,
): LocationSample = LocationSample(
    latitude = latitude,
    longitude = longitude,
    horizontalAccuracyM = if (hasAccuracy) accuracyM else null,
    speedMps = if (hasSpeed) speedMps else null,
    speedAccuracyMps = if (hasSpeedAccuracy) speedAccuracyMps else null,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    epochMillis = epochMillis,
)

/** Satellite counts from a GNSS status: [satelliteCount] in view, those flagged by [usedInFix] used. */
internal fun satellitesOf(satelliteCount: Int, usedInFix: (index: Int) -> Boolean): LocationSourceEvent.Satellites {
    val inView = satelliteCount.coerceAtLeast(0)
    return LocationSourceEvent.Satellites(used = (0 until inView).count(usedInFix), inView = inView)
}

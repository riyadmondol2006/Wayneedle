package app.wayneedle.domain.model

/** Coarse GPS signal quality shown by the UI indicator. Derived from fix accuracy and age. */
enum class GpsSignalQuality {
    /** No usable fix (provider off, still searching, or last fix is stale). */
    NONE,
    POOR,
    FAIR,
    GOOD,
    EXCELLENT,
}

/**
 * Snapshot of the GPS receiver as seen by the app.
 *
 * @property providerEnabled whether the platform GPS provider is switched on.
 * @property horizontalAccuracyM accuracy of the latest fix, or null before the first fix.
 * @property lastFixAgeMillis age of the latest fix, or null before the first fix.
 * @property satellitesUsed satellites used in the latest fix, or null if unknown.
 * @property satellitesInView satellites currently visible, or null if unknown.
 * @property speedAccuracyMps 68 % accuracy of the latest accepted fix's speed, or null if unknown.
 * @property altitudeM altitude of the latest accepted fix (WGS-84 ellipsoid height), or null if unknown.
 * @property verticalAccuracyM 68 % accuracy of [altitudeM], or null if unknown.
 * @property bearingDeg course over ground while moving, degrees clockwise from true north; null when not
 *   moving, not credible, or no current speed.
 */
data class GpsStatus(
    val providerEnabled: Boolean = true,
    val quality: GpsSignalQuality = GpsSignalQuality.NONE,
    val horizontalAccuracyM: Float? = null,
    val lastFixAgeMillis: Long? = null,
    val satellitesUsed: Int? = null,
    val satellitesInView: Int? = null,
    val speedAccuracyMps: Float? = null,
    val altitudeM: Double? = null,
    val verticalAccuracyM: Float? = null,
    val bearingDeg: Float? = null,
) {
    companion object {
        val Initial = GpsStatus()
    }
}

/** Runtime location permission as relevant to a GPS speedometer. */
enum class LocationPermissionState {
    /** ACCESS_FINE_LOCATION granted: the GPS provider can be used. */
    GRANTED_PRECISE,

    /** Only ACCESS_COARSE_LOCATION granted: the GPS provider is NOT available, speed cannot be measured. */
    GRANTED_APPROXIMATE_ONLY,

    DENIED,
    ;

    val canMeasureSpeed: Boolean get() = this == GRANTED_PRECISE
}

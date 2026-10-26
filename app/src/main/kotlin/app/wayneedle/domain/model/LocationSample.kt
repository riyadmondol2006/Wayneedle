package app.wayneedle.domain.model

/**
 * A single GPS fix, decoupled from `android.location.Location` so the tracking engine stays pure
 * Kotlin and can be unit-tested on the JVM.
 *
 * Optional values are `null` when the platform reports them as unavailable (e.g. `hasSpeed()` is
 * false). The engine must never invent a value for a `null` field.
 *
 * @property horizontalAccuracyM 68 % confidence radius in metres, or null if unknown.
 * @property speedMps ground speed in metres/second ([android.location.Location.getSpeed]), or null
 *   if the fix carries no speed.
 * @property speedAccuracyMps 68 % confidence of [speedMps] in metres/second, or null if unknown.
 * @property elapsedRealtimeNanos monotonic timestamp (`SystemClock.elapsedRealtimeNanos` basis).
 *   Use this for freshness checks and time deltas; never compare it to wall-clock time.
 * @property epochMillis wall-clock UTC time of the fix, for display/persistence only.
 * @property altitudeM height in metres (WGS-84 ellipsoid, as the receiver reports it), or null if unknown.
 *   Differences between fixes are meaningful; the absolute value is not height above sea level.
 * @property verticalAccuracyM 68 % confidence of [altitudeM] in metres, or null if unknown.
 * @property bearingDeg course over ground in degrees clockwise from true north, or null if unknown.
 *   Only meaningful while moving.
 * @property bearingAccuracyDeg 68 % confidence of [bearingDeg] in degrees, or null if unknown.
 */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Float?,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    val elapsedRealtimeNanos: Long,
    val epochMillis: Long,
    val altitudeM: Double? = null,
    val verticalAccuracyM: Float? = null,
    val bearingDeg: Float? = null,
    val bearingAccuracyDeg: Float? = null,
)

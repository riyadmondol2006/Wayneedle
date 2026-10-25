package app.wayneedle.domain.model

/**
 * User-selectable unit system. Speed and distance units always change together:
 * [KMH] pairs with kilometres, [MPH] pairs with miles.
 *
 * All domain values are stored and computed in SI units (metres, metres/second, milliseconds);
 * conversion happens only at the presentation edge via this type.
 */
enum class SpeedUnit {
    KMH,
    MPH,
    ;

    /** Converts metres/second into this unit's speed (km/h or mph). */
    fun fromMetersPerSecond(metersPerSecond: Double): Double = when (this) {
        KMH -> metersPerSecond * MPS_TO_KMH
        MPH -> metersPerSecond * MPS_TO_MPH
    }

    /** Converts metres into this unit's distance (km or mi). */
    fun fromMeters(meters: Double): Double = when (this) {
        KMH -> meters / METERS_PER_KILOMETER
        MPH -> meters / METERS_PER_MILE
    }

    companion object {
        /** Exact: 1 m/s = 3.6 km/h. */
        const val MPS_TO_KMH: Double = 3.6

        /** 1 m/s in mph (3600 s/h ÷ 1609.344 m/mi). */
        const val MPS_TO_MPH: Double = 2.2369362920544025

        const val METERS_PER_KILOMETER: Double = 1_000.0
        const val METERS_PER_MILE: Double = 1_609.344
    }
}

package app.wayneedle.tracking

import app.wayneedle.domain.model.GpsSignalQuality

/** Thresholds for [GpsSignalClassifier]. Accuracy values are 68 % horizontal radii in metres. */
data class SignalQualityConfig(
    val excellentAccuracyM: Float = EXCELLENT_ACCURACY_M,
    val goodAccuracyM: Float = GOOD_ACCURACY_M,
    val fairAccuracyM: Float = FAIR_ACCURACY_M,
    val degradedAfterMillis: Long = DEGRADED_AFTER_MILLIS,
    val staleAfterMillis: Long = STALE_AFTER_MILLIS,
    val minSatellitesForFair: Int = MIN_SATELLITES_FOR_FAIR,
    val minSatellitesForGood: Int = MIN_SATELLITES_FOR_GOOD,
) {
    companion object {
        /** Open sky with a dual-frequency receiver (Pixel 6 Pro typically reports 2–5 m). */
        const val EXCELLENT_ACCURACY_M: Float = 5f
        const val GOOD_ACCURACY_M: Float = 10f

        /**
         * Same as [FixFilterConfig.MAX_HORIZONTAL_ACCURACY_M]: FAIR and better mean "speed is being
         * measured", POOR means fixes arrive but are too inaccurate to be used.
         */
        const val FAIR_ACCURACY_M: Float = FixFilterConfig.MAX_HORIZONTAL_ACCURACY_M

        /** A fix older than this (missed ~2 updates) is shown one level lower. */
        const val DEGRADED_AFTER_MILLIS: Long = 2_000L

        /** No fix for longer than this means NONE; matches the live-speed timeout. */
        const val STALE_AFTER_MILLIS: Long = 4_000L

        /** Fewer satellites cannot give a 3-D fix: at most POOR. */
        const val MIN_SATELLITES_FOR_FAIR: Int = 4

        /** A minimal constellation geometry: at most FAIR below this. */
        const val MIN_SATELLITES_FOR_GOOD: Int = 6
    }
}

/** Maps the receiver state to the coarse [GpsSignalQuality] shown by the UI indicator. Pure. */
class GpsSignalClassifier(private val config: SignalQualityConfig = SignalQualityConfig()) {
    /**
     * @param horizontalAccuracyM accuracy of the latest current fix, null if unknown.
     * @param fixAgeMillis age of that fix, null if there is none.
     * @param satellitesUsed satellites used in the fix, null if unknown (then not considered).
     */
    fun classify(
        providerEnabled: Boolean,
        horizontalAccuracyM: Float?,
        fixAgeMillis: Long?,
        satellitesUsed: Int?,
    ): GpsSignalQuality {
        if (!providerEnabled || fixAgeMillis == null || fixAgeMillis > config.staleAfterMillis) {
            return GpsSignalQuality.NONE
        }
        var quality = when {
            horizontalAccuracyM == null || !horizontalAccuracyM.isFinite() || horizontalAccuracyM < 0f ->
                GpsSignalQuality.POOR
            horizontalAccuracyM <= config.excellentAccuracyM -> GpsSignalQuality.EXCELLENT
            horizontalAccuracyM <= config.goodAccuracyM -> GpsSignalQuality.GOOD
            horizontalAccuracyM <= config.fairAccuracyM -> GpsSignalQuality.FAIR
            else -> GpsSignalQuality.POOR
        }
        if (satellitesUsed != null) {
            quality = when {
                satellitesUsed < config.minSatellitesForFair -> minOf(quality, GpsSignalQuality.POOR)
                satellitesUsed < config.minSatellitesForGood -> minOf(quality, GpsSignalQuality.FAIR)
                else -> quality
            }
        }
        if (fixAgeMillis > config.degradedAfterMillis && quality > GpsSignalQuality.POOR) {
            quality = GpsSignalQuality.entries[quality.ordinal - 1]
        }
        return quality
    }
}

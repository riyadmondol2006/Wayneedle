package app.wayneedle.alert

/**
 * Decides when the speed alert is on and when to vibrate.
 *
 * * On as soon as the displayed speed is above the limit; off only once it is [HYSTERESIS_MPS] below it, so
 *   hovering around the limit neither flickers nor buzzes repeatedly.
 * * One vibration per crossing: [update] returns true only when the alert switches on.
 * * Unknown speed (no reliable fix, tunnel) changes nothing: the alert neither fires nor re-arms, so a GPS
 *   gap while speeding does not buzz again when the signal returns.
 * * Switching the alert off, or raising the limit above the current speed, re-arms it at once.
 *
 * Pure and single-threaded; the owner serialises calls.
 */
class SpeedAlertMonitor {
    var isOverLimit: Boolean = false
        private set

    /** @return true when the user should be alerted now (the speed has just gone above the limit). */
    fun update(speedMps: Float?, enabled: Boolean, limitMps: Float): Boolean {
        if (!enabled || !limitMps.isFinite()) {
            isOverLimit = false
            return false
        }
        if (speedMps == null || !speedMps.isFinite()) return false
        if (!isOverLimit && speedMps > limitMps) {
            isOverLimit = true
            return true
        }
        if (isOverLimit && speedMps < limitMps - HYSTERESIS_MPS) isOverLimit = false
        return false
    }

    companion object {
        /** 3.6 km/h (2.2 mph): below the limit by this much before the alert re-arms. */
        const val HYSTERESIS_MPS: Float = 1f
    }
}

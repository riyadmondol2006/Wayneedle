package app.wayneedle.domain.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppSettings(
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You dynamic colour (Android 12+); falls back to the brand palette elsewhere. */
    val dynamicColor: Boolean = true,
    /** Keep the display on while Wayneedle is in the foreground. */
    val keepScreenOn: Boolean = true,
    /** Warn (alert colour and one vibration per crossing) when the live speed goes above [speedAlertMps]. */
    val speedAlertEnabled: Boolean = false,
    /** The alert limit in m/s, unit-independent; the UI shows and edits it in the selected unit. */
    val speedAlertMps: Float = DEFAULT_SPEED_ALERT_MPS,
    /** Beep as well as vibrate when the speed alert fires (follows the phone's ring mode). */
    val speedAlertSound: Boolean = false,
    /** Once a day, ask GitHub whether a newer release exists and offer it. */
    val updateCheckEnabled: Boolean = true,
) {
    companion object {
        val Default = AppSettings()

        /** 120 km/h. */
        const val DEFAULT_SPEED_ALERT_MPS: Float = 120f / 3.6f

        /** Accepted limits: 5 km/h to 300 km/h; anything else stored is replaced by the default. */
        const val MIN_SPEED_ALERT_MPS: Float = 5f / 3.6f
        const val MAX_SPEED_ALERT_MPS: Float = 300f / 3.6f
    }
}

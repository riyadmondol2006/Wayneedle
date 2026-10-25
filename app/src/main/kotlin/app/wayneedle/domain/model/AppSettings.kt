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
) {
    companion object {
        val Default = AppSettings()
    }
}

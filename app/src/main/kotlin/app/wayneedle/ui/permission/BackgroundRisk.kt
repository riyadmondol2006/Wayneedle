package app.wayneedle.ui.permission

import android.annotation.SuppressLint
import android.os.Build
import android.os.PowerManager

/** Phone settings that can stop a trip from recording while the screen is off, each with a fix in Settings. */
enum class BackgroundRisk {
    /** The user set the app's battery use to "Restricted": Android may stop the app in the background. */
    APP_BATTERY_RESTRICTED,

    /** Battery Saver is on in a mode that switches GPS off (or throttles it) while the screen is off. */
    BATTERY_SAVER_STOPS_GPS,
}

/**
 * Pure decision behind the dashboard warnings. Both settings exist from Android 9 (API 28); older versions
 * report nothing. Battery Saver's "foreground only" location mode is harmless: a location foreground service
 * counts as foreground.
 */
fun backgroundRisks(
    sdkInt: Int,
    backgroundRestricted: Boolean,
    powerSaveMode: Boolean,
    locationPowerSaveMode: Int,
): Set<BackgroundRisk> {
    if (sdkInt < Build.VERSION_CODES.P) return emptySet()
    return buildSet {
        if (backgroundRestricted) add(BackgroundRisk.APP_BATTERY_RESTRICTED)
        if (powerSaveMode && locationPowerSaveMode in GPS_STOPPING_MODES) add(BackgroundRisk.BATTERY_SAVER_STOPS_GPS)
    }
}

// Inlined int constants, only ever compared with a mode read on API 28+ (backgroundRisks returns early below):
// safe on every API level. THROTTLE (API 29) simply never occurs on API 28.
@SuppressLint("InlinedApi")
private val GPS_STOPPING_MODES = setOf(
    PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
    PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
    PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF,
)

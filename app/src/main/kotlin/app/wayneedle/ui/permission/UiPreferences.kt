package app.wayneedle.ui.permission

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers whether the app has already shown the location and notification permission dialogs.
 * Android reports `shouldShowRequestPermissionRationale == false` both before the first request and after
 * "don't ask again"; this flag is what tells those two cases apart. Stays on the device (and is excluded
 * from backup by the app's data extraction rules).
 */
internal class UiPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var locationRequested: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_REQUESTED, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCATION_REQUESTED, value) }

    /** `shouldShowRequestPermissionRationale` was true at least once since precise location was last granted. */
    var locationRationaleSeen: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_RATIONALE_SEEN, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCATION_RATIONALE_SEEN, value) }

    var notificationsRequested: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_REQUESTED, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS_REQUESTED, value) }

    private companion object {
        const val FILE = "wayneedle_ui"
        const val KEY_LOCATION_REQUESTED = "location_requested"
        const val KEY_NOTIFICATIONS_REQUESTED = "notifications_requested"
        const val KEY_LOCATION_RATIONALE_SEEN = "location_rationale_seen"
    }
}

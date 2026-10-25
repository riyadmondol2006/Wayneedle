package app.wayneedle.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Preference keys. The names are persisted: never rename one. Enums are stored by `name`, which keeps the
 * file readable and lets [DataStoreSettingsRepository] fall back to the default for a value written by a
 * future or older version instead of failing.
 */
internal object SettingsKeys {
    val SpeedUnit = stringPreferencesKey("speed_unit")
    val ThemeMode = stringPreferencesKey("theme_mode")
    val DynamicColor = booleanPreferencesKey("dynamic_color")
    val KeepScreenOn = booleanPreferencesKey("keep_screen_on")
}

package app.wayneedle.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * [SettingsRepository] on Preferences DataStore.
 *
 * Reads are total: a missing key, an unknown or legacy enum string, or a value of the wrong stored type
 * yields the [AppSettings.Default] value for that setting, and an `IOException` (which includes
 * `CorruptionException`) from the file yields all defaults, so [settings] never throws. Corrupt files are
 * additionally replaced by the DataStore's corruption handler (see [settingsDataStore]).
 *
 * Writes go through `DataStore.edit`, which is atomic and serialised, so concurrent setters never lose
 * each other's updates. A write that fails with an `IOException` (e.g. disk full) is dropped: the previous
 * value stays in effect and [settings] keeps reporting it, rather than crashing the caller.
 */
class DataStoreSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {
    override val settings: Flow<AppSettings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map(Preferences::toAppSettings)
        .distinctUntilChanged()

    override suspend fun setSpeedUnit(unit: SpeedUnit) {
        write { it[SettingsKeys.SpeedUnit] = unit.name }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        write { it[SettingsKeys.ThemeMode] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        write { it[SettingsKeys.DynamicColor] = enabled }
    }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        write { it[SettingsKeys.KeepScreenOn] = enabled }
    }

    private suspend fun write(transform: suspend (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(transform)
        } catch (e: IOException) {
            // Intentionally swallowed, see the class KDoc.
        }
    }
}

private fun Preferences.toAppSettings(): AppSettings {
    val defaults = AppSettings.Default
    return AppSettings(
        speedUnit = enumOrDefault(SettingsKeys.SpeedUnit, defaults.speedUnit),
        themeMode = enumOrDefault(SettingsKeys.ThemeMode, defaults.themeMode),
        dynamicColor = readOrNull(SettingsKeys.DynamicColor) ?: defaults.dynamicColor,
        keepScreenOn = readOrNull(SettingsKeys.KeepScreenOn) ?: defaults.keepScreenOn,
    )
}

/** Reads [key], treating a value stored under a different type as absent instead of throwing. */
private inline fun <reified T : Any> Preferences.readOrNull(key: Preferences.Key<T>): T? = asMap()[key] as? T

private inline fun <reified E : Enum<E>> Preferences.enumOrDefault(
    key: Preferences.Key<String>,
    default: E,
): E {
    val stored = readOrNull(key) ?: return default
    return enumValues<E>().firstOrNull { it.name == stored } ?: default
}

package app.wayneedle.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import java.io.File

internal const val SETTINGS_DATASTORE_NAME = "settings"

/** A corrupt settings file is replaced by an empty one, i.e. every setting falls back to its default. */
internal fun settingsCorruptionHandler(): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { emptyPreferences() }

private val Context.settingsDataStoreDelegate: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_DATASTORE_NAME,
    corruptionHandler = settingsCorruptionHandler(),
)

/**
 * The app's settings DataStore (`files/datastore/settings.preferences_pb`). The `preferencesDataStore`
 * delegate guarantees one instance per process, so calling this repeatedly is safe, whereas creating two
 * DataStores for the same file throws.
 */
fun settingsDataStore(context: Context): DataStore<Preferences> = context.applicationContext.settingsDataStoreDelegate

/**
 * Builds a DataStore on an arbitrary [file] with the same corruption handling as production. For tests;
 * the caller must keep at most one active instance per file and cancel [scope] when done.
 */
fun createSettingsDataStore(scope: CoroutineScope, file: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = settingsCorruptionHandler(),
        scope = scope,
        produceFile = { file },
    )

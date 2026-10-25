package app.wayneedle.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DataStoreSettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cancelScopes() {
        scopes.forEach { it.cancel() }
    }

    /** DataStore releases its file only once the scope's job has completed, so wait for that before reopening. */
    private suspend fun CoroutineScope.closeAndJoin() = coroutineContext.job.cancelAndJoin()

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private fun settingsFile(): File = File(temporaryFolder.root, "settings.preferences_pb")

    private fun repository(file: File = settingsFile()) =
        DataStoreSettingsRepository(createSettingsDataStore(newScope(), file))

    /** A DataStore with NO corruption handler, to exercise the repository's own safety net. */
    private fun unprotectedDataStore(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = newScope(), produceFile = { file })

    private fun File.writeGarbage() = writeBytes(ByteArray(64) { 0xFF.toByte() })

    @Test
    fun `an empty store reports the defaults`() = runTest {
        assertEquals(AppSettings.Default, repository().settings.first())
    }

    @Test
    fun `each setting round-trips`() = runTest {
        val repository = repository()

        repository.setSpeedUnit(SpeedUnit.MPH)
        repository.setThemeMode(ThemeMode.DARK)
        repository.setDynamicColor(false)
        repository.setKeepScreenOn(false)

        assertEquals(
            AppSettings(
                speedUnit = SpeedUnit.MPH,
                themeMode = ThemeMode.DARK,
                dynamicColor = false,
                keepScreenOn = false,
            ),
            repository.settings.first(),
        )
    }

    @Test
    fun `every enum value round-trips`() = runTest {
        val repository = repository()

        SpeedUnit.entries.forEach {
            repository.setSpeedUnit(it)
            assertEquals(it, repository.settings.first().speedUnit)
        }
        ThemeMode.entries.forEach {
            repository.setThemeMode(it)
            assertEquals(it, repository.settings.first().themeMode)
        }
    }

    @Test
    fun `setting one value leaves the others at their defaults`() = runTest {
        val repository = repository()

        repository.setThemeMode(ThemeMode.LIGHT)

        assertEquals(AppSettings.Default.copy(themeMode = ThemeMode.LIGHT), repository.settings.first())
    }

    @Test
    fun `values persist across a new DataStore instance on the same file`() = runTest {
        val file = settingsFile()
        val firstScope = newScope()
        val first = DataStoreSettingsRepository(createSettingsDataStore(firstScope, file))
        first.setSpeedUnit(SpeedUnit.MPH)
        first.setKeepScreenOn(false)
        firstScope.closeAndJoin()

        val second = repository(file)

        assertEquals(AppSettings.Default.copy(speedUnit = SpeedUnit.MPH, keepScreenOn = false), second.settings.first())
    }

    @Test
    fun `the settings flow emits changes and suppresses duplicates`() = runTest {
        val repository = repository()

        repository.settings.test {
            assertEquals(AppSettings.Default, awaitItem())
            repository.setSpeedUnit(SpeedUnit.MPH)
            assertEquals(AppSettings.Default.copy(speedUnit = SpeedUnit.MPH), awaitItem())
            repository.setSpeedUnit(SpeedUnit.MPH)
            expectNoEvents()
            repository.setDynamicColor(false)
            assertEquals(AppSettings.Default.copy(speedUnit = SpeedUnit.MPH, dynamicColor = false), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unknown or legacy enum strings fall back to the defaults`() = runTest {
        val dataStore = createSettingsDataStore(newScope(), settingsFile())
        dataStore.edit {
            it[SettingsKeys.SpeedUnit] = "FURLONGS_PER_FORTNIGHT"
            it[SettingsKeys.ThemeMode] = "AMOLED_BLACK"
        }

        assertEquals(AppSettings.Default, DataStoreSettingsRepository(dataStore).settings.first())
    }

    @Test
    fun `empty and differently cased enum strings are treated as unknown`() = runTest {
        val dataStore = createSettingsDataStore(newScope(), settingsFile())
        dataStore.edit {
            it[SettingsKeys.SpeedUnit] = "mph"
            it[SettingsKeys.ThemeMode] = ""
        }

        assertEquals(AppSettings.Default, DataStoreSettingsRepository(dataStore).settings.first())
    }

    @Test
    fun `one bad value does not discard the valid ones`() = runTest {
        val dataStore = createSettingsDataStore(newScope(), settingsFile())
        dataStore.edit {
            it[SettingsKeys.SpeedUnit] = "MPH"
            it[SettingsKeys.ThemeMode] = "NEON"
            it[SettingsKeys.KeepScreenOn] = false
        }

        assertEquals(
            AppSettings.Default.copy(speedUnit = SpeedUnit.MPH, keepScreenOn = false),
            DataStoreSettingsRepository(dataStore).settings.first(),
        )
    }

    @Test
    fun `a value stored under the wrong type falls back to the default`() = runTest {
        val dataStore = createSettingsDataStore(newScope(), settingsFile())
        dataStore.edit {
            it[intPreferencesKey("speed_unit")] = 7
            it[stringPreferencesKey("dynamic_color")] = "yes"
        }

        assertEquals(AppSettings.Default, DataStoreSettingsRepository(dataStore).settings.first())
    }

    @Test
    fun `a corrupt file is replaced by the corruption handler and settings are usable`() = runTest {
        val file = settingsFile().also { it.writeGarbage() }
        val repository = repository(file)

        assertEquals(AppSettings.Default, repository.settings.first())

        repository.setSpeedUnit(SpeedUnit.MPH)
        assertEquals(AppSettings.Default.copy(speedUnit = SpeedUnit.MPH), repository.settings.first())
    }

    @Test
    fun `without a corruption handler the repository still never throws on a corrupt file`() = runTest {
        val file = settingsFile().also { it.writeGarbage() }
        val repository = DataStoreSettingsRepository(unprotectedDataStore(file))

        assertEquals(AppSettings.Default, repository.settings.first())
        // A write into the still-corrupt file fails with an IOException subtype and is swallowed.
        repository.setThemeMode(ThemeMode.DARK)
        assertEquals(AppSettings.Default, repository.settings.first())
    }

    @Test
    fun `an IOException from the store yields defaults and failed writes are dropped`() = runTest {
        val repository = DataStoreSettingsRepository(FailingDataStore(IOException("disk unreadable")))

        assertEquals(AppSettings.Default, repository.settings.first())
        repository.setSpeedUnit(SpeedUnit.MPH)
        repository.setThemeMode(ThemeMode.DARK)
        repository.setDynamicColor(false)
        repository.setKeepScreenOn(false)
    }

    @Test
    fun `an unexpected non-IO failure is not hidden`() = runTest {
        val repository = DataStoreSettingsRepository(FailingDataStore(IllegalStateException("bug")))

        assertFailsWith<IllegalStateException> { repository.settings.first() }
    }

    @Test
    fun `concurrent setters never lose each others updates`() = runTest {
        val repository = repository()
        val changed = AppSettings(
            speedUnit = SpeedUnit.MPH,
            themeMode = ThemeMode.DARK,
            dynamicColor = !AppSettings.Default.dynamicColor,
            keepScreenOn = !AppSettings.Default.keepScreenOn,
        )
        assertNotEquals(AppSettings.Default.speedUnit, changed.speedUnit)

        repeat(40) { round ->
            repository.setSpeedUnit(AppSettings.Default.speedUnit)
            repository.setThemeMode(AppSettings.Default.themeMode)
            repository.setDynamicColor(AppSettings.Default.dynamicColor)
            repository.setKeepScreenOn(AppSettings.Default.keepScreenOn)

            withContext(Dispatchers.Default) {
                listOf(
                    async { repository.setSpeedUnit(changed.speedUnit) },
                    async { repository.setThemeMode(changed.themeMode) },
                    async { repository.setDynamicColor(changed.dynamicColor) },
                    async { repository.setKeepScreenOn(changed.keepScreenOn) },
                ).awaitAll()
            }

            assertEquals(changed, repository.settings.first(), "round $round")
        }
    }

    @Test
    fun `many concurrent writes to one key leave a value that survives a reopen`() = runTest {
        val file = settingsFile()
        val scope = newScope()
        val writer = DataStoreSettingsRepository(createSettingsDataStore(scope, file))

        withContext(Dispatchers.Default) {
            (0 until 100).map { i ->
                async { writer.setSpeedUnit(SpeedUnit.entries[i % SpeedUnit.entries.size]) }
            }.awaitAll()
        }
        val live = writer.settings.first()
        scope.closeAndJoin()

        assertEquals(live, repository(file).settings.first())
    }
}

/** A DataStore whose reads and writes always fail with [error]. */
private class FailingDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
}

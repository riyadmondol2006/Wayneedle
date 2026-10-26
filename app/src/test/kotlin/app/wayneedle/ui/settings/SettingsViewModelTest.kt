package app.wayneedle.ui.settings

import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import app.wayneedle.testing.FakeTimeSource
import app.wayneedle.ui.AppViewModel
import app.wayneedle.ui.MainDispatcherRule
import app.wayneedle.ui.keepActive
import app.wayneedle.ui.preview.FakeSettingsRepository
import app.wayneedle.ui.preview.FakeTrackingController
import app.wayneedle.ui.preview.FakeTripRepository
import app.wayneedle.ui.preview.PreviewData
import app.wayneedle.update.ReleaseInfo
import app.wayneedle.update.UpdateCheckMemory
import app.wayneedle.update.UpdateCheckResult
import app.wayneedle.update.UpdateChecker
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val trips = FakeTripRepository(PreviewData.trips)

    @Test
    fun `state combines settings and the trip count`() = runTest {
        val vm = SettingsViewModel(settings, trips)
        keepActive(vm.uiState)
        assertEquals(PreviewData.trips.size, vm.uiState.value.tripCount)
        assertEquals(SpeedUnit.KMH, vm.uiState.value.settings.speedUnit)
    }

    @Test
    fun `each setting is written to the repository`() = runTest {
        val vm = SettingsViewModel(settings, trips)
        keepActive(vm.uiState)

        vm.setSpeedUnit(SpeedUnit.MPH)
        vm.setThemeMode(ThemeMode.DARK)
        vm.setDynamicColor(false)
        vm.setKeepScreenOn(false)

        val current = vm.uiState.value.settings
        assertEquals(SpeedUnit.MPH, current.speedUnit)
        assertEquals(ThemeMode.DARK, current.themeMode)
        assertFalse(current.dynamicColor)
        assertFalse(current.keepScreenOn)
    }

    @Test
    fun `delete all asks first, then removes every trip`() = runTest {
        val vm = SettingsViewModel(settings, trips)
        keepActive(vm.uiState)

        vm.requestDeleteAll()
        assertTrue(vm.uiState.value.confirmingDeleteAll)
        assertEquals(PreviewData.trips.size, vm.uiState.value.tripCount)

        vm.confirmDeleteAll()
        assertFalse(vm.uiState.value.confirmingDeleteAll)
        assertEquals(0, vm.uiState.value.tripCount)
    }

    @Test
    fun `cancelling delete all keeps the trips`() = runTest {
        val vm = SettingsViewModel(settings, trips)
        keepActive(vm.uiState)
        vm.requestDeleteAll()
        vm.cancelDeleteAll()
        assertFalse(vm.uiState.value.confirmingDeleteAll)
        assertEquals(PreviewData.trips.size, vm.uiState.value.tripCount)
    }

    @Test
    fun `app view model exposes settings once loaded`() = runTest {
        val vm = AppViewModel(settings, FakeTrackingController(), FakeTimeSource())
        assertNull(vm.settings.value)
        keepActive(vm.settings)
        settings.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, vm.settings.value?.themeMode)
    }

    @Test
    fun `a failed delete all is reported instead of crashing`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        repository.deleteFailure = IOException("disk full")
        val vm = SettingsViewModel(FakeSettingsRepository(), repository)
        keepActive(vm.uiState)
        vm.requestDeleteAll()
        vm.confirmDeleteAll()
        assertTrue(vm.uiState.value.deleteFailed)
        assertEquals(PreviewData.trips.size, vm.uiState.value.tripCount)
        vm.dismissDeleteError()
        assertFalse(vm.uiState.value.deleteFailed)
    }

    @Test
    fun `an unreadable database does not crash settings`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        repository.readFailure = IOException("database disk image is malformed")
        val vm = SettingsViewModel(FakeSettingsRepository(), repository)
        keepActive(vm.uiState)
        assertEquals(0, vm.uiState.value.tripCount)
    }

    private class Memory : UpdateCheckMemory {
        override var lastCheckEpochMillis = 0L
        override var skippedVersion: String? = null
    }

    @Test
    fun `check now reports an update, the latest version, or no connection`() = runTest {
        val release = ReleaseInfo("1.0.9", "https://github.com/riyadmondol2006/Wayneedle/releases/tag/v1.0.9", "")
        var answer: ReleaseInfo? = release
        var offline = false
        val checker = UpdateChecker(
            source = {
                if (offline) throw IOException("offline")
                answer
            },
            currentVersion = "1.0.5",
            memory = Memory(),
            nowEpochMillis = { 0L },
        )
        val vm = SettingsViewModel(FakeSettingsRepository(), FakeTripRepository(), checker)
        keepActive(vm.uiState)

        vm.checkForUpdatesNow()
        assertEquals(ManualUpdateCheck.Done(UpdateCheckResult.Available(release)), vm.uiState.value.manualUpdateCheck)
        vm.dismissUpdateCheck()
        assertEquals(ManualUpdateCheck.Idle, vm.uiState.value.manualUpdateCheck)

        answer = null
        vm.checkForUpdatesNow()
        assertEquals(ManualUpdateCheck.Done(UpdateCheckResult.UpToDate), vm.uiState.value.manualUpdateCheck)

        offline = true
        vm.checkForUpdatesNow()
        assertEquals(ManualUpdateCheck.Done(UpdateCheckResult.Failed), vm.uiState.value.manualUpdateCheck)
    }

    @Test
    fun `the update check can be switched off`() = runTest {
        val repository = FakeSettingsRepository()
        val vm = SettingsViewModel(repository, FakeTripRepository())
        keepActive(vm.uiState)
        assertTrue(vm.uiState.value.settings.updateCheckEnabled, "on by default")
        vm.setUpdateCheckEnabled(false)
        assertFalse(vm.uiState.value.settings.updateCheckEnabled)
    }

    @Test
    fun `the daily check pops up once, and skipping keeps it quiet`() = runTest {
        val release = ReleaseInfo("2.0.0", "https://github.com/riyadmondol2006/Wayneedle/releases/tag/v2.0.0", "")
        val memory = Memory()
        val checker = UpdateChecker({ release }, "1.0.0", memory, nowEpochMillis = { 1_000L })
        val vm = AppViewModel(FakeSettingsRepository(), FakeTrackingController(), FakeTimeSource(), checker)
        vm.checkForUpdatesIfDue(enabled = false)
        assertEquals(null, vm.availableUpdate.value, "switched off")
        vm.checkForUpdatesIfDue(enabled = true)
        assertEquals(release, vm.availableUpdate.value)
        vm.skipUpdate()
        assertEquals(null, vm.availableUpdate.value)
        assertEquals("2.0.0", memory.skippedVersion)
    }
}

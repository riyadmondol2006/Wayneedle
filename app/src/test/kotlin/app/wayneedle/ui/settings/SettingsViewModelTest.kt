package app.wayneedle.ui.settings

import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import app.wayneedle.ui.AppViewModel
import app.wayneedle.ui.MainDispatcherRule
import app.wayneedle.ui.keepActive
import app.wayneedle.ui.preview.FakeSettingsRepository
import app.wayneedle.ui.preview.FakeTripRepository
import app.wayneedle.ui.preview.PreviewData
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
        val vm = AppViewModel(settings)
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
}

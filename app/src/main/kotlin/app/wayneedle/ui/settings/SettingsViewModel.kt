package app.wayneedle.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TripRepository
import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import app.wayneedle.ui.STOP_TIMEOUT_MILLIS
import app.wayneedle.ui.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val settings: AppSettings = AppSettings.Default,
    val tripCount: Int = 0,
    val confirmingDeleteAll: Boolean = false,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
) : ViewModel() {
    private val confirmingDeleteAll = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        tripRepository.observeTrips().map { it.size },
        confirmingDeleteAll,
    ) { settings, count, confirming ->
        SettingsUiState(settings, count, confirming)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

    fun setSpeedUnit(unit: SpeedUnit) {
        viewModelScope.launch { settingsRepository.setSpeedUnit(unit) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepScreenOn(enabled) }
    }

    fun requestDeleteAll() = confirmingDeleteAll.update { true }

    fun cancelDeleteAll() = confirmingDeleteAll.update { false }

    fun confirmDeleteAll() {
        confirmingDeleteAll.update { false }
        viewModelScope.launch { tripRepository.deleteAllTrips() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                SettingsViewModel(container.settingsRepository, container.tripRepository)
            }
        }
    }
}

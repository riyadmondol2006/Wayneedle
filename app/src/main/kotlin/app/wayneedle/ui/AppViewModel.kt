package app.wayneedle.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TimeSource
import app.wayneedle.domain.TrackingController
import app.wayneedle.domain.model.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Activity-wide settings used for theming and keep-screen-on. `null` until the first value is loaded.
 *
 * [keepAwake] says whether "keep screen on" should hold the display right now (see [KeepAwakePolicy]); it is
 * re-evaluated on every tracking update and every [RECHECK_MILLIS], so the hold also expires while nothing moves.
 */
class AppViewModel(
    settingsRepository: SettingsRepository,
    controller: TrackingController,
    private val timeSource: TimeSource,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .map<AppSettings, AppSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val policy = KeepAwakePolicy()

    private val recheck = flow {
        while (true) {
            emit(Unit)
            delay(RECHECK_MILLIS)
        }
    }

    val keepAwake: StateFlow<Boolean> = combine(controller.state, recheck) { state, _ ->
        policy.update(state, timeSource.elapsedRealtimeNanos())
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    companion object {
        private const val RECHECK_MILLIS = 15_000L

        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                AppViewModel(container.settingsRepository, container.trackingController, container.timeSource)
            }
        }
    }
}

internal const val STOP_TIMEOUT_MILLIS = 5_000L

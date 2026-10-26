package app.wayneedle.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wayneedle.BuildConfig
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TimeSource
import app.wayneedle.domain.TrackingController
import app.wayneedle.domain.model.AppSettings
import app.wayneedle.update.ReleaseInfo
import app.wayneedle.update.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val updateChecker: UpdateChecker? = null,
) : ViewModel() {
    private val mutableUpdate = MutableStateFlow<ReleaseInfo?>(null)

    /** A newer release found by the automatic check, shown as a popup until dismissed. */
    val availableUpdate: StateFlow<ReleaseInfo?> = mutableUpdate.asStateFlow()
    private var updateCheckStarted = false

    /** Runs the daily check once per process when allowed; the checker itself enforces "at most daily". */
    fun checkForUpdatesIfDue(enabled: Boolean) {
        val checker = updateChecker ?: return
        if (!enabled || updateCheckStarted) return
        updateCheckStarted = true
        viewModelScope.launch {
            checker.checkIfDue()?.let { release -> mutableUpdate.value = release }
        }
    }

    fun dismissUpdate() {
        mutableUpdate.value = null
    }

    /** "Skip this version": no popup for it again. */
    fun skipUpdate() {
        mutableUpdate.value?.let { updateChecker?.skip(it) }
        mutableUpdate.value = null
    }

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
                AppViewModel(
                    container.settingsRepository,
                    container.trackingController,
                    container.timeSource,
                    // Debug builds report a development version; offering them releases would only be noise.
                    container.updateChecker.takeUnless { BuildConfig.DEBUG },
                )
            }
        }
    }
}

internal const val STOP_TIMEOUT_MILLIS = 5_000L

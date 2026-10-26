package app.wayneedle.ui.tripdetail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TripRepository
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.Trip
import app.wayneedle.ui.STOP_TIMEOUT_MILLIS
import app.wayneedle.ui.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
sealed interface TripDetailUiState {
    data object Loading : TripDetailUiState

    /** @property deleteFailed a confirmed delete did not go through; the trip is still here. */
    data class Loaded(
        val trip: Trip,
        val unit: SpeedUnit,
        val confirmingDelete: Boolean,
        val deleteFailed: Boolean = false,
    ) : TripDetailUiState

    /** The trip does not exist (it was deleted elsewhere, or the id is stale). */
    data object NotFound : TripDetailUiState

    /** The user deleted this trip from the screen; the UI should navigate back. */
    data object Deleted : TripDetailUiState
}

class TripDetailViewModel(
    private val tripId: String,
    private val tripRepository: TripRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private data class Local(
        val confirmingDelete: Boolean = false,
        val deleting: Boolean = false,
        val deleted: Boolean = false,
        val deleteFailed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<TripDetailUiState> = combine(
        tripRepository.observeTrip(tripId).catch { emit(null) },
        settingsRepository.settings.map { it.speedUnit }.distinctUntilChanged(),
        local,
    ) { trip, unit, local ->
        when {
            local.deleted -> TripDetailUiState.Deleted

            // The repository reported the row gone before the delete call returned.
            trip == null && local.deleting -> TripDetailUiState.Deleted

            trip == null -> TripDetailUiState.NotFound

            else -> TripDetailUiState.Loaded(trip, unit, local.confirmingDelete, local.deleteFailed)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TripDetailUiState.Loading)

    fun requestDelete() = local.update { it.copy(confirmingDelete = true) }

    fun cancelDelete() = local.update { it.copy(confirmingDelete = false) }

    fun dismissDeleteError() = local.update { it.copy(deleteFailed = false) }

    /**
     * Deletes the trip, and only then reports [TripDetailUiState.Deleted] (the screen closes). If deleting
     * fails the trip stays on screen with [TripDetailUiState.Loaded.deleteFailed]. A second confirmation while
     * deleting is ignored. Runs non-cancellable: leaving the screen clears this view model.
     */
    fun confirmDelete() {
        if (local.value.deleting || local.value.deleted) return
        local.update { it.copy(confirmingDelete = false, deleting = true, deleteFailed = false) }
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { tripRepository.deleteTrip(tripId) }
                local.update { it.copy(deleting = false, deleted = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                local.update { it.copy(deleting = false, deleteFailed = true) }
            }
        }
    }

    companion object {
        fun factory(tripId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                TripDetailViewModel(tripId, container.tripRepository, container.settingsRepository)
            }
        }
    }
}

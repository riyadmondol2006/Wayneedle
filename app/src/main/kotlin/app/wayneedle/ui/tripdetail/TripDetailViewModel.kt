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

    data class Loaded(
        val trip: Trip,
        val unit: SpeedUnit,
        val confirmingDelete: Boolean,
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
    private data class Local(val confirmingDelete: Boolean = false, val deleted: Boolean = false)

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<TripDetailUiState> = combine(
        tripRepository.observeTrip(tripId),
        settingsRepository.settings.map { it.speedUnit }.distinctUntilChanged(),
        local,
    ) { trip, unit, local ->
        when {
            local.deleted -> TripDetailUiState.Deleted
            trip == null -> TripDetailUiState.NotFound
            else -> TripDetailUiState.Loaded(trip, unit, local.confirmingDelete)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TripDetailUiState.Loading)

    fun requestDelete() = local.update { it.copy(confirmingDelete = true) }

    fun cancelDelete() = local.update { it.copy(confirmingDelete = false) }

    /**
     * Marks the trip deleted up front, so the repository's "trip is gone" emission is never mistaken for a
     * missing trip, and reverts if deleting fails. Runs non-cancellable: leaving the screen clears this view model.
     */
    fun confirmDelete() {
        local.update { Local(confirmingDelete = false, deleted = true) }
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { tripRepository.deleteTrip(tripId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                local.update { it.copy(deleted = false) }
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

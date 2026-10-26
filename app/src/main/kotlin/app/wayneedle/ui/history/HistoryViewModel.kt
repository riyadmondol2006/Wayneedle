package app.wayneedle.ui.history

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TripRepository
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.Trip
import app.wayneedle.ui.STOP_TIMEOUT_MILLIS
import app.wayneedle.ui.appContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** Trips that started on the same calendar [date], newest first. */
@Immutable
data class TripDay(val date: LocalDate, val trips: List<Trip>)

/**
 * @property loadFailed the trips could not be read (storage error); not the same as having none.
 * @property deleteFailed a confirmed delete did not go through; shown until acknowledged.
 */
@Immutable
data class HistoryUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val days: List<TripDay> = emptyList(),
    val unit: SpeedUnit = SpeedUnit.KMH,
    /** The trip the user asked to delete and has not yet confirmed. */
    val pendingDelete: Trip? = null,
    val deleteFailed: Boolean = false,
) {
    val isEmpty: Boolean get() = !isLoading && !loadFailed && days.isEmpty()
}

/** Groups [trips] by the local calendar day they started, newest day first and newest trip first. */
fun groupTripsByDay(trips: List<Trip>, zone: ZoneId): List<TripDay> = trips
    .groupBy { Instant.ofEpochMilli(it.startedAtEpochMillis).atZone(zone).toLocalDate() }
    .map { (date, dayTrips) -> TripDay(date, dayTrips.sortedByDescending { it.startedAtEpochMillis }) }
    .sortedByDescending { it.date }

class HistoryViewModel(
    private val tripRepository: TripRepository,
    settingsRepository: SettingsRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val pendingDeleteId = MutableStateFlow<String?>(null)
    private val deleteFailed = MutableStateFlow(false)

    /** Null when the trips cannot be read; a storage error must not crash the screen or look like "no trips". */
    private val trips: Flow<List<Trip>?> = tripRepository.observeTrips()
        .map<List<Trip>, List<Trip>?> { it }
        .catch { emit(null) }

    val uiState: StateFlow<HistoryUiState> = combine(
        trips,
        settingsRepository.settings.map { it.speedUnit }.distinctUntilChanged(),
        pendingDeleteId,
        deleteFailed,
    ) { trips, unit, pendingId, failed ->
        HistoryUiState(
            isLoading = false,
            loadFailed = trips == null,
            days = groupTripsByDay(trips.orEmpty(), zone),
            unit = unit,
            pendingDelete = trips?.firstOrNull { it.id == pendingId },
            deleteFailed = failed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HistoryUiState())

    fun requestDelete(tripId: String) = pendingDeleteId.update { tripId }

    fun cancelDelete() = pendingDeleteId.update { null }

    fun confirmDelete() {
        val id = pendingDeleteId.value ?: return
        pendingDeleteId.update { null }
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { tripRepository.deleteTrip(id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                deleteFailed.value = true
            }
        }
    }

    fun dismissDeleteError() = deleteFailed.update { false }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                HistoryViewModel(container.tripRepository, container.settingsRepository)
            }
        }
    }
}

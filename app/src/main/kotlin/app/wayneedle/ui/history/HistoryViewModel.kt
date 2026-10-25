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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Trips that started on the same calendar [date], newest first. */
@Immutable
data class TripDay(val date: LocalDate, val trips: List<Trip>)

@Immutable
data class HistoryUiState(
    val isLoading: Boolean = true,
    val days: List<TripDay> = emptyList(),
    val unit: SpeedUnit = SpeedUnit.KMH,
    /** The trip the user asked to delete and has not yet confirmed. */
    val pendingDelete: Trip? = null,
) {
    val isEmpty: Boolean get() = !isLoading && days.isEmpty()
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

    val uiState: StateFlow<HistoryUiState> = combine(
        tripRepository.observeTrips(),
        settingsRepository.settings.map { it.speedUnit }.distinctUntilChanged(),
        pendingDeleteId,
    ) { trips, unit, pendingId ->
        HistoryUiState(
            isLoading = false,
            days = groupTripsByDay(trips, zone),
            unit = unit,
            pendingDelete = trips.firstOrNull { it.id == pendingId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HistoryUiState())

    fun requestDelete(tripId: String) = pendingDeleteId.update { tripId }

    fun cancelDelete() = pendingDeleteId.update { null }

    fun confirmDelete() {
        val id = pendingDeleteId.value ?: return
        pendingDeleteId.update { null }
        viewModelScope.launch { tripRepository.deleteTrip(id) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                HistoryViewModel(container.tripRepository, container.settingsRepository)
            }
        }
    }
}

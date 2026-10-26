package app.wayneedle.ui.history

import app.cash.turbine.test
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripStats
import app.wayneedle.ui.MainDispatcherRule
import app.wayneedle.ui.keepActive
import app.wayneedle.ui.preview.FakeSettingsRepository
import app.wayneedle.ui.preview.FakeTripRepository
import app.wayneedle.ui.preview.PreviewData
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val utc = ZoneOffset.UTC
    private val settings = FakeSettingsRepository()

    private fun trip(id: String, startMillis: Long) =
        Trip(id, startMillis, startMillis + 60_000, TripStats(1_000.0, 60_000, 20f))

    @Test
    fun `trips are grouped by local day, newest day and newest trip first`() {
        val day1Morning = trip("a", PreviewData.NOW - 28 * 3_600_000L)
        val day1Evening = trip("b", PreviewData.NOW - 26 * 3_600_000L)
        val day2 = trip("c", PreviewData.NOW)
        val groups = groupTripsByDay(listOf(day1Morning, day2, day1Evening), utc)

        assertEquals(listOf(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 24)), groups.map { it.date })
        assertEquals(listOf("c"), groups[0].trips.map { it.id })
        assertEquals(listOf("b", "a"), groups[1].trips.map { it.id })
    }

    @Test
    fun `the same instant can fall on different days in different zones`() {
        val lateEvening = trip("late", PreviewData.NOW + 11 * 3_600_000L) // 23:00 UTC
        assertEquals(LocalDate.of(2026, 10, 25), groupTripsByDay(listOf(lateEvening), utc).single().date)
        assertEquals(
            LocalDate.of(2026, 10, 26),
            groupTripsByDay(listOf(lateEvening), ZoneOffset.ofHours(2)).single().date,
        )
    }

    @Test
    fun `no trips groups to nothing`() {
        assertEquals(emptyList(), groupTripsByDay(emptyList(), utc))
    }

    @Test
    fun `loading becomes empty state when there are no trips`() = runTest {
        val vm = HistoryViewModel(FakeTripRepository(), settings, utc)
        vm.uiState.test {
            val first = awaitItem()
            if (first.isLoading) {
                assertTrue(awaitItem().isEmpty)
            } else {
                assertTrue(first.isEmpty)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `trips show up grouped with the selected unit`() = runTest {
        val vm = HistoryViewModel(FakeTripRepository(PreviewData.trips), settings, utc)
        keepActive(vm.uiState)
        settings.setSpeedUnit(SpeedUnit.MPH)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isEmpty)
        assertEquals(SpeedUnit.MPH, state.unit)
        assertEquals(PreviewData.trips.size, state.days.sumOf { it.trips.size })
    }

    @Test
    fun `deleting asks first and only removes the trip after confirmation`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        val vm = HistoryViewModel(repository, settings, utc)
        keepActive(vm.uiState)

        vm.requestDelete("trip-2")
        assertEquals("trip-2", vm.uiState.value.pendingDelete?.id)
        assertEquals(PreviewData.trips.size, vm.uiState.value.days.sumOf { it.trips.size })

        vm.confirmDelete()
        assertNull(vm.uiState.value.pendingDelete)
        assertEquals(PreviewData.trips.size - 1, vm.uiState.value.days.sumOf { it.trips.size })
        assertNull(repository.getTrip("trip-2"))
    }

    @Test
    fun `cancelling a delete keeps the trip`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        val vm = HistoryViewModel(repository, settings, utc)
        keepActive(vm.uiState)

        vm.requestDelete("trip-3")
        vm.cancelDelete()

        assertNull(vm.uiState.value.pendingDelete)
        assertEquals(PreviewData.trips.size, vm.uiState.value.days.sumOf { it.trips.size })
    }

    @Test
    fun `a pending delete disappears if the trip vanishes`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        val vm = HistoryViewModel(repository, settings, utc)
        keepActive(vm.uiState)
        vm.requestDelete("trip-4")

        repository.deleteTrip("trip-4")

        assertNull(vm.uiState.value.pendingDelete)
    }

    @Test
    fun `confirm without a pending request does nothing`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        val vm = HistoryViewModel(repository, settings, utc)
        keepActive(vm.uiState)
        vm.confirmDelete()
        assertEquals(PreviewData.trips.size, vm.uiState.value.days.sumOf { it.trips.size })
    }

    @Test
    fun `an unreadable database shows an error, not an empty history, and does not crash`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        repository.readFailure = IOException("database disk image is malformed")
        val vm = HistoryViewModel(repository, settings, utc)
        keepActive(vm.uiState)
        val state = vm.uiState.value
        assertTrue(state.loadFailed)
        assertFalse(state.isEmpty, "an error must not read as an empty history")
        assertFalse(state.isLoading)
    }

    @Test
    fun `a failed delete is reported and the trip stays`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        repository.deleteFailure = IOException("disk full")
        val vm = HistoryViewModel(repository, settings, utc)
        keepActive(vm.uiState)
        val id = PreviewData.trips.first().id
        vm.requestDelete(id)
        vm.confirmDelete()
        assertTrue(vm.uiState.value.deleteFailed)
        assertTrue(vm.uiState.value.days.flatMap { it.trips }.any { it.id == id })
        vm.dismissDeleteError()
        assertFalse(vm.uiState.value.deleteFailed)
    }
}

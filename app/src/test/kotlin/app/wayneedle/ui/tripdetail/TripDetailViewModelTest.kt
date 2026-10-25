package app.wayneedle.ui.tripdetail

import app.cash.turbine.test
import app.wayneedle.domain.model.SpeedUnit
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
import kotlin.test.assertIs
import kotlin.test.assertNull

class TripDetailViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val trip = PreviewData.trips[1]

    @Test
    fun `an existing trip is loaded with the selected unit`() = runTest {
        settings.setSpeedUnit(SpeedUnit.MPH)
        val vm = TripDetailViewModel(trip.id, FakeTripRepository(PreviewData.trips), settings)
        keepActive(vm.uiState)

        val loaded = assertIs<TripDetailUiState.Loaded>(vm.uiState.value)
        assertEquals(trip, loaded.trip)
        assertEquals(SpeedUnit.MPH, loaded.unit)
        assertEquals(false, loaded.confirmingDelete)
    }

    @Test
    fun `an unknown trip is reported as not found`() = runTest {
        val vm = TripDetailViewModel("missing", FakeTripRepository(PreviewData.trips), settings)
        keepActive(vm.uiState)
        assertEquals(TripDetailUiState.NotFound, vm.uiState.value)
    }

    @Test
    fun `a trip deleted elsewhere turns into not found`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        val vm = TripDetailViewModel(trip.id, repository, settings)
        keepActive(vm.uiState)
        assertIs<TripDetailUiState.Loaded>(vm.uiState.value)

        repository.deleteTrip(trip.id)

        assertEquals(TripDetailUiState.NotFound, vm.uiState.value)
    }

    @Test
    fun `delete asks for confirmation first`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        val vm = TripDetailViewModel(trip.id, repository, settings)
        keepActive(vm.uiState)

        vm.requestDelete()
        assertEquals(true, assertIs<TripDetailUiState.Loaded>(vm.uiState.value).confirmingDelete)
        assertEquals(trip, repository.getTrip(trip.id))

        vm.cancelDelete()
        assertEquals(false, assertIs<TripDetailUiState.Loaded>(vm.uiState.value).confirmingDelete)
        assertEquals(trip, repository.getTrip(trip.id))
    }

    @Test
    fun `confirmed delete removes the trip and ends in Deleted without flashing not found`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        val vm = TripDetailViewModel(trip.id, repository, settings)
        vm.uiState.test {
            assertIs<TripDetailUiState.Loaded>(awaitItem())
            vm.requestDelete()
            assertEquals(true, assertIs<TripDetailUiState.Loaded>(awaitItem()).confirmingDelete)

            vm.confirmDelete()

            assertEquals(TripDetailUiState.Deleted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(repository.getTrip(trip.id))
    }

    @Test
    fun `a failed delete returns to the trip`() = runTest {
        val repository = FakeTripRepository(PreviewData.trips)
        repository.deleteFailure = IOException("locked")
        val vm = TripDetailViewModel(trip.id, repository, settings)
        keepActive(vm.uiState)

        vm.requestDelete()
        vm.confirmDelete()

        assertIs<TripDetailUiState.Loaded>(vm.uiState.value)
        assertEquals(trip, repository.getTrip(trip.id))
    }
}

package app.wayneedle.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.wayneedle.R
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.ui.history.HistoryScreen
import app.wayneedle.ui.history.HistoryUiState
import app.wayneedle.ui.history.groupTripsByDay
import app.wayneedle.ui.preview.PreviewData
import app.wayneedle.ui.tripdetail.TripDetailScreen
import app.wayneedle.ui.tripdetail.TripDetailUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class HistoryAndDetailScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun showHistory(state: HistoryUiState, fontScale: Float = 1f) =
        rule.setWayneedleContent(fontScale = fontScale) {
            HistoryScreen(
                state = state,
                onOpenTrip = { calls += "open:$it" },
                onRequestDelete = { calls += "delete:$it" },
                onConfirmDelete = { calls += "confirm" },
                onCancelDelete = { calls += "cancel" },
            )
        }

    private val withTrips = HistoryUiState(
        isLoading = false,
        days = groupTripsByDay(PreviewData.trips, ZoneId.systemDefault()),
        unit = SpeedUnit.KMH,
    )

    @Test
    fun emptyHistoryExplainsWhatToDo() {
        showHistory(HistoryUiState(isLoading = false))
        rule.onNodeWithText(string(R.string.history_empty_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.history_empty_body)).assertIsDisplayed()
    }

    @Test
    fun tripsShowDistanceAndOpenTheirDetails() {
        showHistory(withTrips)
        rule.onNodeWithText(localizedDecimal(27.80), substring = true, useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodes(hasClickAction()).onFirst().performClick()
        assertEquals(1, calls.size)
        assertEquals("open:trip-running", calls.single())
    }

    @Test
    fun theDeleteButtonAsksForConfirmation() {
        showHistory(withTrips)
        rule.onAllNodesWithContentDescription(
            string(R.string.action_delete_trip_from, ""),
            substring = true,
        ).onFirst().performClick()
        assertEquals(1, calls.size)
        assertEquals(true, calls.single().startsWith("delete:"))
    }

    @Test
    fun pendingDeletePromptsBeforeRemovingTheTrip() {
        showHistory(withTrips.copy(pendingDelete = PreviewData.trips[1]))
        rule.onNodeWithText(string(R.string.dialog_delete_trip_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_cancel)).performClick()
        rule.onDialogNodeWithText(string(R.string.action_delete)).assertIsDisplayed()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun historyStaysUsableAtLargeFontScale() {
        showHistory(withTrips, fontScale = 2f)
        rule.onNodeWithText(localizedDecimal(27.80), substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    // ---- trip details -----------------------------------------------------------------------------------------------

    private fun showDetail(state: TripDetailUiState) = rule.setWayneedleContent {
        TripDetailScreen(
            state = state,
            onBack = { calls += "back" },
            onRequestDelete = { calls += "requestDelete" },
            onConfirmDelete = { calls += "confirmDelete" },
            onCancelDelete = { calls += "cancelDelete" },
        )
    }

    @Test
    fun detailsShowEveryStatisticAndStartAndEndTimes() {
        showDetail(TripDetailUiState.Loaded(PreviewData.trips[1], SpeedUnit.KMH, confirmingDelete = false))
        rule.onNodeWithText(string(R.string.stat_distance), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.stat_duration), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.stat_top), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.stat_average), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.detail_started), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.detail_finished), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun backAndDeleteAreWiredUp() {
        showDetail(TripDetailUiState.Loaded(PreviewData.trips[1], SpeedUnit.KMH, confirmingDelete = false))
        rule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        rule.onNodeWithContentDescription(string(R.string.action_delete)).performClick()
        assertEquals(listOf("back", "requestDelete"), calls)
    }

    @Test
    fun confirmingDeleteShowsADestructiveDialog() {
        showDetail(TripDetailUiState.Loaded(PreviewData.trips[1], SpeedUnit.KMH, confirmingDelete = true))
        rule.onNodeWithText(string(R.string.dialog_delete_trip_title)).assertIsDisplayed()
        rule.onDialogNodeWithText(string(R.string.action_delete)).performClick()
        assertEquals(listOf("confirmDelete"), calls)
    }

    @Test
    fun aMissingTripShowsAFriendlyMessage() {
        showDetail(TripDetailUiState.NotFound)
        rule.onNodeWithText(string(R.string.trip_not_found_title)).assertIsDisplayed()
    }
}

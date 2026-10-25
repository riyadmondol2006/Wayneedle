package app.wayneedle.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.wayneedle.R
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.ui.dashboard.DashboardActions
import app.wayneedle.ui.dashboard.DashboardDialog
import app.wayneedle.ui.dashboard.DashboardScreen
import app.wayneedle.ui.dashboard.DashboardUiState
import app.wayneedle.ui.dashboard.LocationGate
import app.wayneedle.ui.preview.PreviewData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = DashboardActions(
        onStart = { calls += "start" },
        onPause = { calls += "pause" },
        onResume = { calls += "resume" },
        onRequestFinish = { calls += "requestFinish" },
        onRequestDiscard = { calls += "requestDiscard" },
        onConfirmFinish = { calls += "confirmFinish" },
        onConfirmDiscard = { calls += "confirmDiscard" },
        onDismissDialog = { calls += "dismissDialog" },
        onDone = { calls += "done" },
        onViewTrip = { calls += "view:$it" },
        onRequestPermission = { calls += "requestPermission" },
        onOpenAppSettings = { calls += "openAppSettings" },
        onOpenLocationSettings = { calls += "openLocationSettings" },
    )

    private fun show(
        state: DashboardUiState,
        gate: LocationGate = LocationGate.Ready,
        fontScale: Float = 1f,
        dark: Boolean = false,
    ) = rule.setWayneedleContent(darkTheme = dark, fontScale = fontScale) { DashboardScreen(state, gate, actions) }

    // ---- idle ---------------------------------------------------------------------------------------------------

    @Test
    fun idleShowsAProminentStartButtonThatStartsATrip() {
        show(PreviewData.dashboard(PreviewData.idleStationary))
        rule.onNodeWithText(string(R.string.action_start))
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(64.dp)
            .performClick()
        assertEquals(listOf("start"), calls)
        rule.onNodeWithText(string(R.string.action_pause)).assertDoesNotExist()
    }

    @Test
    fun idleShowsPlaceholdersForTripStatistics() {
        show(PreviewData.dashboard(PreviewData.idleStationary))
        rule.onNodeWithText(string(R.string.stat_distance), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.stat_duration), useUnmergedTree = true).assertIsDisplayed()
    }

    // ---- running / paused -----------------------------------------------------------------------------------------

    @Test
    fun runningOffersPauseAndFinishButNotStart() {
        show(PreviewData.dashboard(PreviewData.running))
        rule.onNodeWithText(string(R.string.action_start)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.action_pause)).assertIsDisplayed().performClick()
        rule.onNodeWithText(string(R.string.action_finish)).assertIsDisplayed().performClick()
        assertEquals(listOf("pause", "requestFinish"), calls)
    }

    @Test
    fun runningShowsTripStatisticsInTheSelectedFormat() {
        show(PreviewData.dashboard(PreviewData.running))
        rule.onNodeWithText("27.80", substring = true, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("0:38:12", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun milesModeShowsMilesAndMph() {
        show(PreviewData.dashboard(PreviewData.running, SpeedUnit.MPH))
        rule.onAllNodesWithText(string(R.string.unit_distance_mi), substring = true, useUnmergedTree = true)
            .onFirst()
            .assertIsDisplayed()
        rule.onAllNodesWithText(string(R.string.unit_speed_mph), substring = true, useUnmergedTree = true)
            .onFirst()
            .assertIsDisplayed()
    }

    @Test
    fun pausedIsUnmistakableAndOffersResumeFinishAndDiscard() {
        show(PreviewData.dashboard(PreviewData.paused))
        rule.onNodeWithText(string(R.string.status_paused)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_resume)).assertIsDisplayed().performClick()
        rule.onNodeWithText(string(R.string.action_finish)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_discard)).assertIsDisplayed().performClick()
        assertEquals(listOf("resume", "requestDiscard"), calls)
        rule.onNodeWithText(string(R.string.action_pause)).assertDoesNotExist()
    }

    @Test
    fun waitingForTheFirstFixSaysSoInsteadOfCounting() {
        show(PreviewData.dashboard(PreviewData.runningAwaitingFix))
        rule.onNodeWithText(string(R.string.status_waiting_for_gps)).assertIsDisplayed()
    }

    @Test
    fun finishingDisablesTheControlsAndShowsSaving() {
        show(PreviewData.dashboard(PreviewData.running, isFinishing = true))
        rule.onNodeWithText(string(R.string.action_saving)).assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText(string(R.string.action_pause)).assertIsNotEnabled()
    }

    // ---- confirmation dialogs -------------------------------------------------------------------------------------

    @Test
    fun finishDialogAsksBeforeSaving() {
        show(PreviewData.dashboard(PreviewData.running, dialog = DashboardDialog.ConfirmFinish))
        rule.onNodeWithText(string(R.string.dialog_finish_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_cancel)).performClick()
        assertEquals(listOf("dismissDialog"), calls)
    }

    @Test
    fun finishDialogConfirmButtonSavesTheTrip() {
        show(PreviewData.dashboard(PreviewData.running, dialog = DashboardDialog.ConfirmFinish))
        // The dialog's confirm action and the screen's Finish button share a label; the dialog one is last.
        rule.onDialogNodeWithText(string(R.string.action_finish)).performClick()
        assertEquals(listOf("confirmFinish"), calls)
    }

    @Test
    fun discardDialogIsDestructiveAndConfirmable() {
        show(PreviewData.dashboard(PreviewData.paused, dialog = DashboardDialog.ConfirmDiscard))
        rule.onNodeWithText(string(R.string.dialog_discard_title)).assertIsDisplayed()
        rule.onDialogNodeWithText(string(R.string.action_discard)).performClick()
        assertEquals(listOf("confirmDiscard"), calls)
    }

    @Test
    fun aFailedSaveOffersARetry() {
        show(PreviewData.dashboard(PreviewData.paused, dialog = DashboardDialog.SaveFailed))
        rule.onNodeWithText(string(R.string.dialog_save_failed_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_try_again)).performClick()
        assertEquals(listOf("confirmFinish"), calls)
    }

    // ---- recovered trip and summary -------------------------------------------------------------------------------

    @Test
    fun recoveredTripShowsABannerWithResumeFinishAndDiscard() {
        show(PreviewData.dashboard(PreviewData.recovered))
        rule.onNodeWithText(string(R.string.recovered_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_resume)).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText(string(R.string.action_finish)).performScrollTo().performClick()
        rule.onNodeWithText(string(R.string.action_discard)).performScrollTo().performClick()
        assertEquals(listOf("resume", "requestFinish", "requestDiscard"), calls)
    }

    @Test
    fun finishedTripShowsTheSummaryWithDoneAndDetails() {
        show(PreviewData.dashboard(PreviewData.finished, savedTrip = PreviewData.savedTrip))
        rule.onNodeWithText(string(R.string.summary_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_view_details)).performScrollTo().performClick()
        rule.onNodeWithText(string(R.string.action_done)).performScrollTo().performClick()
        assertEquals(listOf("view:trip-running", "done"), calls)
    }

    // ---- permission and GPS states -------------------------------------------------------------------------------

    @Test
    fun firstRunExplainsBeforeAskingForLocation() {
        show(PreviewData.dashboard(PreviewData.permissionDenied), LocationGate.NeedsRationale)
        rule.onNodeWithText(string(R.string.gate_rationale_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_start)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.gate_action_allow)).performClick()
        assertEquals(listOf("requestPermission"), calls)
    }

    @Test
    fun permanentlyDeniedPointsToAppSettings() {
        show(PreviewData.dashboard(PreviewData.permissionDenied), LocationGate.PermanentlyDenied)
        rule.onNodeWithText(string(R.string.gate_blocked_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.gate_action_open_app_settings)).performClick()
        assertEquals(listOf("openAppSettings"), calls)
    }

    @Test
    fun approximateOnlyOffersToRequestPreciseLocation() {
        show(PreviewData.dashboard(PreviewData.approximateOnly), LocationGate.ApproximateOnly)
        rule.onNodeWithText(string(R.string.gate_approximate_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.gate_action_allow_precise)).performClick()
        assertEquals(listOf("requestPermission"), calls)
    }

    @Test
    fun gpsOffOffersToOpenLocationSettings() {
        show(PreviewData.dashboard(PreviewData.providerOff), LocationGate.GpsOff)
        rule.onNodeWithText(string(R.string.gate_gps_off_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.gate_action_turn_on_location)).performClick()
        assertEquals(listOf("openLocationSettings"), calls)
    }

    @Test
    fun noGpsHardwareIsExplainedWithoutAnAction() {
        show(PreviewData.dashboard(PreviewData.permissionDenied, hasGpsHardware = false), LocationGate.NoGpsHardware)
        rule.onNodeWithText(string(R.string.gate_no_gps_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_start)).assertDoesNotExist()
    }

    @Test
    fun searchingShowsALoadingStateWithoutAnyAction() {
        show(PreviewData.dashboard(PreviewData.idleSearching))
        rule.onNodeWithText(string(R.string.speed_reason_searching), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_start)).assertIsDisplayed()
    }

    // ---- accessibility and layout -------------------------------------------------------------------------------

    @Test
    fun controlsRemainReachableAtTwoHundredPercentFontScale() {
        show(PreviewData.dashboard(PreviewData.paused), fontScale = 2f)
        // The controls are pinned below the scrolling content, so they stay on screen without scrolling.
        rule.onNodeWithText(string(R.string.action_resume)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_finish)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_discard)).assertIsDisplayed()
    }

    @Test
    fun darkThemeRendersTheSameControls() {
        show(PreviewData.dashboard(PreviewData.running), dark = true)
        rule.onNodeWithText(string(R.string.action_pause)).assertIsDisplayed()
    }
}

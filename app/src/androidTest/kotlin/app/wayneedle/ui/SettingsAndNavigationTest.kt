package app.wayneedle.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.wayneedle.R
import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import app.wayneedle.ui.components.WayneedleNavigationBar
import app.wayneedle.ui.navigation.Destination
import app.wayneedle.ui.settings.SettingsCallbacks
import app.wayneedle.ui.settings.SettingsScreen
import app.wayneedle.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAndNavigationTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private val callbacks = SettingsCallbacks(
        onSpeedUnit = { calls += "unit:$it" },
        onThemeMode = { calls += "theme:$it" },
        onDynamicColor = { calls += "dynamic:$it" },
        onKeepScreenOn = { calls += "keepOn:$it" },
        onRequestDeleteAll = { calls += "requestDeleteAll" },
        onConfirmDeleteAll = { calls += "confirmDeleteAll" },
        onCancelDeleteAll = { calls += "cancelDeleteAll" },
    )

    private fun showSettings(
        state: SettingsUiState = SettingsUiState(AppSettings(), tripCount = 3),
        dynamicColorSupported: Boolean = true,
        fontScale: Float = 1f,
    ) = rule.setWayneedleContent(fontScale = fontScale) {
        SettingsScreen(state, callbacks, dynamicColorSupported, versionName = "9.9.9")
    }

    @Test
    fun unitSelectionIsOneChoiceAndReportsChanges() {
        showSettings()
        rule.onNodeWithText(string(R.string.settings_unit_kmh)).assertIsSelected()
        rule.onNodeWithText(string(R.string.settings_unit_mph)).performClick()
        assertEquals(listOf("unit:${SpeedUnit.MPH}"), calls)
    }

    @Test
    fun themeChoicesCoverSystemLightAndDark() {
        showSettings(SettingsUiState(AppSettings(themeMode = ThemeMode.DARK), tripCount = 0))
        rule.onNodeWithText(string(R.string.settings_theme_dark)).assertIsSelected()
        rule.onNodeWithText(string(R.string.settings_theme_light)).performClick()
        assertEquals(listOf("theme:${ThemeMode.LIGHT}"), calls)
    }

    @Test
    fun dynamicColourAndKeepScreenOnAreSwitches() {
        showSettings()
        rule.onNodeWithText(string(R.string.settings_dynamic_color)).performScrollTo().performClick()
        rule.onNodeWithText(string(R.string.settings_keep_screen_on)).performScrollTo().assertIsOn().performClick()
        assertEquals(listOf("dynamic:false", "keepOn:false"), calls)
    }

    @Test
    fun dynamicColourIsDisabledWhereUnsupported() {
        showSettings(dynamicColorSupported = false)
        rule.onNodeWithText(string(R.string.settings_dynamic_color)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(string(R.string.settings_dynamic_color_unsupported)).assertIsDisplayed()
    }

    @Test
    fun deleteAllNeedsTripsAndAsksForConfirmation() {
        showSettings()
        rule.onNodeWithText(string(R.string.action_delete_all_trips)).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("requestDeleteAll"), calls)
    }

    @Test
    fun deleteAllIsDisabledWithoutTrips() {
        showSettings(SettingsUiState(AppSettings(), tripCount = 0))
        rule.onNodeWithText(string(R.string.action_delete_all_trips)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun deleteAllConfirmationDialogIsDestructive() {
        showSettings(SettingsUiState(AppSettings(), tripCount = 3, confirmingDeleteAll = true))
        rule.onNodeWithText(string(R.string.dialog_delete_all_title)).assertIsDisplayed()
        rule.onDialogNodeWithText(string(R.string.action_delete_all)).performClick()
        assertEquals(listOf("confirmDeleteAll"), calls)
    }

    @Test
    fun aboutShowsVersionLicenceAndTheOfflinePromise() {
        showSettings()
        rule.onNodeWithText("9.9.9", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_about_license)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_about_offline)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun settingsScrollAtLargeFontScale() {
        showSettings(fontScale = 2f)
        rule.onNodeWithText(string(R.string.settings_about_offline)).performScrollTo().assertIsDisplayed()
    }

    // ---- navigation bar ---------------------------------------------------------------------------------------------

    @Test
    fun navigationBarListsTheThreeTopLevelDestinationsAndReportsSelection() {
        var selected: String? = null
        // Hosted like the app does (bottom slot, wrap-content height). Putting the bar straight into the test
        // Surface would propagate its full-screen minimum constraints to every item and push two tabs off screen.
        rule.setWayneedleContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                WayneedleNavigationBar(
                    items = Destination.topLevel,
                    selectedRoute = Destination.History.route,
                    onSelect = { selected = it.route },
                )
            }
        }
        rule.onNodeWithText(string(R.string.nav_dashboard)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.nav_history)).assertIsDisplayed().assertIsSelected()
        rule.onNodeWithText(string(R.string.nav_settings)).assertIsDisplayed().performClick()
        assertEquals(Destination.Settings.route, selected)
    }
}

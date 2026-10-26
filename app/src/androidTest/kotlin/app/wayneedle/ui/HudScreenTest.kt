package app.wayneedle.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.wayneedle.R
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.ui.hud.HudScreen
import app.wayneedle.ui.preview.PreviewData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HudScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(mirrored: Boolean = false, controls: Boolean = true, overLimit: Boolean = false) {
        val gauge = PreviewData.dashboard(PreviewData.idleMoving).gauge.copy(overLimit = overLimit) // 54 km/h
        rule.setWayneedleContent {
            HudScreen(
                gauge = gauge,
                unit = SpeedUnit.KMH,
                mirrored = mirrored,
                controlsVisible = controls,
                onToggleControls = { calls += "toggle" },
                onMirroredChange = { calls += "mirror:$it" },
                onExit = { calls += "exit" },
            )
        }
    }

    @Test
    fun theSpeedFillsTheScreenAndTheControlsWork() {
        show()
        rule.onNodeWithText("54").assertIsDisplayed()
        rule.onNodeWithContentDescription(string(R.string.hud_mirror)).performClick()
        rule.onNodeWithContentDescription(string(R.string.hud_exit)).performClick()
        assertEquals(listOf("mirror:true", "exit"), calls)
    }

    @Test
    fun aTapAnywhereTogglesTheControlsAndTheyCanBeHidden() {
        show(controls = false)
        rule.onNodeWithContentDescription(string(R.string.hud_exit)).assertDoesNotExist()
        rule.onNodeWithText("54").performClick()
        assertEquals(listOf("toggle"), calls)
    }

    @Test
    fun mirroredAndOverTheLimitStillShowTheSpeed() {
        show(mirrored = true, overLimit = true)
        rule.onNodeWithText("54").assertIsDisplayed()
    }
}

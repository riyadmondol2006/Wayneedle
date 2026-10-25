package app.wayneedle.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.wayneedle.R
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.ui.preview.PreviewData
import app.wayneedle.ui.speedometer.GAUGE_HEIGHT_FRACTION
import app.wayneedle.ui.speedometer.GPS_INDICATOR_TAG
import app.wayneedle.ui.speedometer.GaugeScale
import app.wayneedle.ui.speedometer.GaugeStatus
import app.wayneedle.ui.speedometer.GaugeStatusTone
import app.wayneedle.ui.speedometer.GpsSignalIndicator
import app.wayneedle.ui.speedometer.SPEEDOMETER_TAG
import app.wayneedle.ui.speedometer.SpeedReading
import app.wayneedle.ui.speedometer.SpeedUnavailableReason
import app.wayneedle.ui.speedometer.Speedometer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeedometerTest {
    @get:Rule
    val rule = createComposeRule()

    private fun hasState(expected: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

    @Test
    fun availableSpeedIsExposedAsOneAccessibilityNodeInTheUsersUnit() {
        rule.setWayneedleContent {
            Speedometer(SpeedReading.Available(54f), GaugeScale.base(SpeedUnit.KMH))
        }
        rule.onNodeWithTag(SPEEDOMETER_TAG).assertIsDisplayed()
        rule.onNodeWithTag(SPEEDOMETER_TAG)
            .assert(hasState(plural(R.plurals.spoken_speed_kmh, 54, "54")))
    }

    @Test
    fun milesPerHourAreSpokenAsMilesPerHour() {
        rule.setWayneedleContent {
            Speedometer(SpeedReading.Available(60f), GaugeScale.base(SpeedUnit.MPH))
        }
        rule.onNodeWithTag(SPEEDOMETER_TAG).assert(hasState(plural(R.plurals.spoken_speed_mph, 60, "60")))
    }

    @Test
    fun aGenuineZeroIsShownAsZeroNotAsUnavailable() {
        rule.setWayneedleContent {
            Speedometer(SpeedReading.Available(0f), GaugeScale.base(SpeedUnit.KMH))
        }
        rule.onNodeWithTag(SPEEDOMETER_TAG).assert(hasState(plural(R.plurals.spoken_speed_kmh, 0, "0")))
        rule.onNodeWithText("0", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun unavailableSpeedShowsADashAndTheReasonNeverZero() {
        rule.setWayneedleContent {
            Speedometer(
                SpeedReading.Unavailable(SpeedUnavailableReason.GPS_OFF),
                GaugeScale.base(SpeedUnit.KMH),
            )
        }
        rule.onNodeWithText("—", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.speed_reason_gps_off), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theStatusPillIsAPoliteLiveRegion() {
        rule.setWayneedleContent {
            Speedometer(
                reading = SpeedReading.Available(0f),
                scale = GaugeScale.base(SpeedUnit.KMH),
                status = GaugeStatus("Paused", GaugeStatusTone.CAUTION, prominent = true),
            )
        }
        rule.onNodeWithText("Paused")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun theGaugeStaysInsideItsWidthAtLargeFontScale() {
        rule.setWayneedleContent(fontScale = 2f) {
            Speedometer(SpeedReading.Available(112f), GaugeScale.base(SpeedUnit.KMH))
        }
        rule.onNodeWithTag(SPEEDOMETER_TAG).assertIsDisplayed()
        assertTrue(GAUGE_HEIGHT_FRACTION in 0.5f..1f)
    }

    @Test
    fun gpsIndicatorDescribesQualityAccuracyAndSatellitesInOneSentence() {
        rule.setWayneedleContent {
            GpsSignalIndicator(PreviewData.goodGps, SpeedUnit.KMH)
        }
        val description = rule.onNodeWithTag(GPS_INDICATOR_TAG).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description, description.contains(string(R.string.gps_quality_excellent)))
        assertTrue(description, description.contains("4"))
        assertTrue(description, description.contains("14"))
    }

    @Test
    fun gpsOffIsReportedAsOff() {
        rule.setWayneedleContent {
            GpsSignalIndicator(PreviewData.gpsOff, SpeedUnit.KMH)
        }
        rule.onNodeWithText(string(R.string.gps_off), useUnmergedTree = true).assertIsDisplayed()
    }
}

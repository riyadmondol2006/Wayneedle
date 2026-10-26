package app.wayneedle.ui.settings

import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.SpeedUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedAlertLimitTest {
    private fun kmh(value: Double) = SpeedUnit.KMH.toMetersPerSecond(value).toFloat()

    private fun mph(value: Double) = SpeedUnit.MPH.toMetersPerSecond(value).toFloat()

    @Test
    fun `the default limit reads 120 km h and 75 mph`() {
        assertEquals(120, speedAlertLimitInUnit(AppSettings.DEFAULT_SPEED_ALERT_MPS, SpeedUnit.KMH))
        assertEquals(75, speedAlertLimitInUnit(AppSettings.DEFAULT_SPEED_ALERT_MPS, SpeedUnit.MPH))
    }

    @Test
    fun `plus and minus move by five in the user's unit`() {
        assertEquals(125, speedAlertLimitInUnit(steppedSpeedAlertLimit(kmh(120.0), SpeedUnit.KMH, 1), SpeedUnit.KMH))
        assertEquals(115, speedAlertLimitInUnit(steppedSpeedAlertLimit(kmh(120.0), SpeedUnit.KMH, -1), SpeedUnit.KMH))
        assertEquals(70, speedAlertLimitInUnit(steppedSpeedAlertLimit(mph(65.0), SpeedUnit.MPH, 1), SpeedUnit.MPH))
    }

    @Test
    fun `a limit set in the other unit snaps to this unit's grid`() {
        // 120 km/h is 74.6 mph: one press of plus in mph gives 80, not 79.6.
        assertEquals(80, speedAlertLimitInUnit(steppedSpeedAlertLimit(kmh(120.0), SpeedUnit.MPH, 1), SpeedUnit.MPH))
    }

    @Test
    fun `mashing the buttons stops at the accepted range`() {
        val lowest = steppedSpeedAlertLimit(kmh(30.0), SpeedUnit.KMH, -100)
        assertEquals(5, speedAlertLimitInUnit(lowest, SpeedUnit.KMH))
        val highest = steppedSpeedAlertLimit(kmh(30.0), SpeedUnit.KMH, 1_000)
        assertEquals(300, speedAlertLimitInUnit(highest, SpeedUnit.KMH))
        assertEquals(185, speedAlertLimitInUnit(steppedSpeedAlertLimit(mph(30.0), SpeedUnit.MPH, 1_000), SpeedUnit.MPH))
    }
}

package app.wayneedle.ui.speedometer

import app.wayneedle.domain.model.GpsStatus
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.SpeedUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SpeedReadingTest {
    private val precise = LocationPermissionState.GRANTED_PRECISE
    private val gpsOn = GpsStatus(providerEnabled = true)

    @Test
    fun `reason follows the order hardware permission approximate provider searching`() {
        assertEquals(SpeedUnavailableReason.NO_GPS_HARDWARE, speedUnavailableReason(LocationPermissionState.DENIED, gpsOn, hasGpsHardware = false))
        assertEquals(SpeedUnavailableReason.PERMISSION_REQUIRED, speedUnavailableReason(LocationPermissionState.DENIED, gpsOn, true))
        assertEquals(
            SpeedUnavailableReason.APPROXIMATE_ONLY,
            speedUnavailableReason(LocationPermissionState.GRANTED_APPROXIMATE_ONLY, gpsOn, true),
        )
        assertEquals(SpeedUnavailableReason.GPS_OFF, speedUnavailableReason(precise, GpsStatus(providerEnabled = false), true))
        assertEquals(SpeedUnavailableReason.SEARCHING, speedUnavailableReason(precise, gpsOn, true))
    }

    @Test
    fun `available speed is converted to the display unit`() {
        val kmh = speedReading(LiveSpeed.Available(10f, 10f), SpeedUnit.KMH, precise, gpsOn, true)
        assertEquals(36f, assertIs<SpeedReading.Available>(kmh).valueInUnit, 1e-4f)
        val mph = speedReading(LiveSpeed.Available(26.8224f, 26.8224f), SpeedUnit.MPH, precise, gpsOn, true)
        assertEquals(60f, assertIs<SpeedReading.Available>(mph).valueInUnit, 1e-3f)
    }

    @Test
    fun `a genuine zero stays available and is never replaced by the unavailable state`() {
        val reading = speedReading(LiveSpeed.Available(0f, 0f), SpeedUnit.KMH, precise, gpsOn, true)
        assertEquals(SpeedReading.Available(0f), reading)
    }

    @Test
    fun `unavailable speed carries the reason and never becomes zero`() {
        val reading = speedReading(LiveSpeed.Unavailable, SpeedUnit.KMH, precise, GpsStatus(providerEnabled = false), true)
        assertEquals(SpeedReading.Unavailable(SpeedUnavailableReason.GPS_OFF), reading)
    }

    @Test
    fun `a non finite speed is treated as unavailable`() {
        val reading = speedReading(LiveSpeed.Available(Float.NaN, Float.NaN), SpeedUnit.KMH, precise, gpsOn, true)
        assertEquals(SpeedReading.Unavailable(SpeedUnavailableReason.SEARCHING), reading)
    }
}

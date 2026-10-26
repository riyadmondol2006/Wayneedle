package app.wayneedle.ui.permission

import android.os.PowerManager
import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundRisksTest {
    private fun risks(
        sdk: Int = 37,
        restricted: Boolean = false,
        saver: Boolean = false,
        mode: Int = PowerManager.LOCATION_MODE_NO_CHANGE,
    ) = backgroundRisks(sdk, restricted, saver, mode)

    @Test
    fun `nothing to warn about on a normally configured phone`() {
        assertEquals(emptySet(), risks())
    }

    @Test
    fun `restricted battery use is reported`() {
        assertEquals(setOf(BackgroundRisk.APP_BATTERY_RESTRICTED), risks(restricted = true))
    }

    @Test
    fun `battery saver is reported only in modes that stop GPS with the screen off`() {
        val stopping = listOf(
            PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
            PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
            PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF,
        )
        for (mode in stopping) {
            assertEquals(setOf(BackgroundRisk.BATTERY_SAVER_STOPS_GPS), risks(saver = true, mode = mode), "mode $mode")
        }
        // Foreground-only keeps location for foreground services, i.e. a recording trip.
        assertEquals(emptySet(), risks(saver = true, mode = PowerManager.LOCATION_MODE_FOREGROUND_ONLY))
        assertEquals(emptySet(), risks(saver = true, mode = PowerManager.LOCATION_MODE_NO_CHANGE))
        // The mode is irrelevant while Battery Saver is off.
        assertEquals(emptySet(), risks(saver = false, mode = PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF))
    }

    @Test
    fun `both can apply at once, and nothing is claimed before Android 9`() {
        assertEquals(
            setOf(BackgroundRisk.APP_BATTERY_RESTRICTED, BackgroundRisk.BATTERY_SAVER_STOPS_GPS),
            risks(restricted = true, saver = true, mode = PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF),
        )
        assertEquals(emptySet(), risks(sdk = 27, restricted = true, saver = true, mode = 1))
    }
}

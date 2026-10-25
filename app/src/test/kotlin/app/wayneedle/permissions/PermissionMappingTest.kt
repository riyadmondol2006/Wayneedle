package app.wayneedle.permissions

import app.wayneedle.domain.model.LocationPermissionState
import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionMappingTest {
    @Test
    fun `fine location is precise`() {
        assertEquals(LocationPermissionState.GRANTED_PRECISE, mapLocationPermission(fineGranted = true, coarseGranted = true))
        assertEquals(LocationPermissionState.GRANTED_PRECISE, mapLocationPermission(fineGranted = true, coarseGranted = false))
    }

    @Test
    fun `coarse only is approximate and cannot measure speed`() {
        val state = mapLocationPermission(fineGranted = false, coarseGranted = true)
        assertEquals(LocationPermissionState.GRANTED_APPROXIMATE_ONLY, state)
        assertEquals(false, state.canMeasureSpeed)
    }

    @Test
    fun `neither grant is denied`() {
        assertEquals(LocationPermissionState.DENIED, mapLocationPermission(fineGranted = false, coarseGranted = false))
    }

    @Test
    fun `notification visibility`() {
        val none = 0
        val low = 2
        assertEquals(TripNotificationVisibility.VISIBLE, classifyNotificationVisibility(appEnabled = true, channelImportance = low))
        assertEquals(TripNotificationVisibility.VISIBLE, classifyNotificationVisibility(appEnabled = true, channelImportance = null))
        assertEquals(TripNotificationVisibility.APP_BLOCKED, classifyNotificationVisibility(appEnabled = false, channelImportance = low))
        assertEquals(TripNotificationVisibility.APP_BLOCKED, classifyNotificationVisibility(appEnabled = false, channelImportance = null))
        assertEquals(TripNotificationVisibility.CHANNEL_BLOCKED, classifyNotificationVisibility(appEnabled = true, channelImportance = none))
    }
}

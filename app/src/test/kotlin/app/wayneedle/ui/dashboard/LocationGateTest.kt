package app.wayneedle.ui.dashboard

import app.wayneedle.domain.model.LocationPermissionState
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationGateTest {
    private fun gate(
        permission: LocationPermissionState,
        providerEnabled: Boolean = true,
        hasGps: Boolean = true,
        asked: Boolean = false,
        rationale: Boolean = false,
    ) = resolveLocationGate(permission, providerEnabled, hasGps, asked, rationale)

    @Test
    fun `no GPS hardware wins over everything`() {
        assertEquals(LocationGate.NoGpsHardware, gate(LocationPermissionState.GRANTED_PRECISE, hasGps = false))
        assertEquals(LocationGate.NoGpsHardware, gate(LocationPermissionState.DENIED, hasGps = false))
    }

    @Test
    fun `first run explains before asking`() {
        assertEquals(LocationGate.NeedsRationale, gate(LocationPermissionState.DENIED, asked = false))
    }

    @Test
    fun `denied once but the system will ask again`() {
        assertEquals(LocationGate.CanRequestAgain, gate(LocationPermissionState.DENIED, asked = true, rationale = true))
    }

    @Test
    fun `denied with do not ask again needs the settings screen`() {
        assertEquals(
            LocationGate.PermanentlyDenied,
            gate(LocationPermissionState.DENIED, asked = true, rationale = false),
        )
    }

    @Test
    fun `approximate only asks for precise location`() {
        assertEquals(LocationGate.ApproximateOnly, gate(LocationPermissionState.GRANTED_APPROXIMATE_ONLY, asked = true))
    }

    @Test
    fun `provider off is reported only once permission is fine`() {
        assertEquals(LocationGate.GpsOff, gate(LocationPermissionState.GRANTED_PRECISE, providerEnabled = false))
        assertEquals(LocationGate.NeedsRationale, gate(LocationPermissionState.DENIED, providerEnabled = false))
    }

    @Test
    fun `everything in place is ready`() {
        assertEquals(LocationGate.Ready, gate(LocationPermissionState.GRANTED_PRECISE, asked = true))
    }
}

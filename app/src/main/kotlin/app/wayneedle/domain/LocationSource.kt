package app.wayneedle.domain

import app.wayneedle.domain.model.LocationSample
import kotlinx.coroutines.flow.Flow

sealed interface LocationSourceEvent {
    data class Fix(val sample: LocationSample) : LocationSourceEvent

    data class ProviderEnabledChanged(val enabled: Boolean) : LocationSourceEvent

    data class Satellites(val used: Int, val inView: Int) : LocationSourceEvent

    /** Permission was revoked or never granted. The flow completes after emitting this. */
    data object PermissionDenied : LocationSourceEvent
}

/**
 * The ONLY gateway to the platform GPS. Backed by `LocationManager` (GPS provider, no Play Services).
 *
 * Ownership rule: exactly one collector exists at any time, owned by `TrackingController`.
 * No other component (UI, service, notification) may subscribe to location updates.
 */
interface LocationSource {
    /**
     * Cold flow. Collecting registers one platform listener; cancelling the collection unregisters it.
     * On collection the current provider-enabled state is emitted first.
     *
     * @param minIntervalMillis requested update interval (≈1 s for a live speedometer).
     */
    fun events(minIntervalMillis: Long = 1_000L): Flow<LocationSourceEvent>
}

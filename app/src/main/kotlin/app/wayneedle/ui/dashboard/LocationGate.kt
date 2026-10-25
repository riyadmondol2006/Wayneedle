package app.wayneedle.ui.dashboard

import app.wayneedle.domain.model.LocationPermissionState

/**
 * What stands between the user and a working speedometer, in the order it should be resolved.
 * [Ready] means permission, GPS hardware and the GPS provider are all in place (a fix may still be pending).
 */
sealed interface LocationGate {
    data object Ready : LocationGate

    /** The device has no GPS receiver at all. Nothing the user can do. */
    data object NoGpsHardware : LocationGate

    /** First run: explain why location is needed before the system dialog appears. */
    data object NeedsRationale : LocationGate

    /** Denied before, but Android will still show the dialog if asked again. */
    data object CanRequestAgain : LocationGate

    /** Denied with "don't ask again": only the system settings can fix it. */
    data object PermanentlyDenied : LocationGate

    /** Only approximate location was granted; speed needs precise (GPS) location. */
    data object ApproximateOnly : LocationGate

    /** Permission is fine but the GPS provider is switched off. */
    data object GpsOff : LocationGate
}

/**
 * @param askedBefore whether the app has already shown the location permission dialog at least once.
 * @param shouldShowRationale `Activity.shouldShowRequestPermissionRationale` for fine location; false both
 *   before the first request and after "don't ask again", which is why [askedBefore] is tracked.
 */
fun resolveLocationGate(
    permission: LocationPermissionState,
    providerEnabled: Boolean,
    hasGpsHardware: Boolean,
    askedBefore: Boolean,
    shouldShowRationale: Boolean,
): LocationGate = when {
    !hasGpsHardware -> LocationGate.NoGpsHardware
    permission == LocationPermissionState.DENIED -> when {
        !askedBefore -> LocationGate.NeedsRationale
        shouldShowRationale -> LocationGate.CanRequestAgain
        else -> LocationGate.PermanentlyDenied
    }
    permission == LocationPermissionState.GRANTED_APPROXIMATE_ONLY -> LocationGate.ApproximateOnly
    !providerEnabled -> LocationGate.GpsOff
    else -> LocationGate.Ready
}

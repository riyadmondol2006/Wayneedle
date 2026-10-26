package app.wayneedle.domain.model

/**
 * Everything the UI and the notification need, in one immutable snapshot.
 *
 * @property backgroundRecordingUnavailable Android refused the foreground service for the active trip: it
 *   records only while the app is on screen. Cleared when a later Start/Resume succeeds or the trip ends.
 * @property uiVisible the app is on screen (as told by `setUiVisible`); background features such as the speed
 *   alert of the foreground service only act while it is not.
 */
data class TrackingState(
    val permission: LocationPermissionState = LocationPermissionState.DENIED,
    val gps: GpsStatus = GpsStatus.Initial,
    val liveSpeed: LiveSpeed = LiveSpeed.Unavailable,
    val trip: TripState = TripState(),
    val backgroundRecordingUnavailable: Boolean = false,
    val uiVisible: Boolean = false,
) {
    companion object {
        val Initial = TrackingState()
    }
}

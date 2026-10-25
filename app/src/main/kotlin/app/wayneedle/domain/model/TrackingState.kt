package app.wayneedle.domain.model

/** Everything the UI and the notification need, in one immutable snapshot. */
data class TrackingState(
    val permission: LocationPermissionState = LocationPermissionState.DENIED,
    val gps: GpsStatus = GpsStatus.Initial,
    val liveSpeed: LiveSpeed = LiveSpeed.Unavailable,
    val trip: TripState = TripState(),
) {
    companion object {
        val Initial = TrackingState()
    }
}

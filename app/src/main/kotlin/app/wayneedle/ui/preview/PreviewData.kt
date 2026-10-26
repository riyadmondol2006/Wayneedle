package app.wayneedle.ui.preview

import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.GpsSignalQuality
import app.wayneedle.domain.model.GpsStatus
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripState
import app.wayneedle.domain.model.TripStats
import app.wayneedle.ui.dashboard.DashboardDialog
import app.wayneedle.ui.dashboard.DashboardUiState
import app.wayneedle.ui.dashboard.buildUiState
import app.wayneedle.ui.speedometer.GaugeScale

/** Sample states for previews, UI tests and view-model tests. All values are plausible, never real data. */
object PreviewData {
    /** 2026-10-25 12:00 UTC. */
    const val NOW = 1_792_929_600_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    val goodGps = GpsStatus(
        providerEnabled = true,
        quality = GpsSignalQuality.EXCELLENT,
        horizontalAccuracyM = 4f,
        lastFixAgeMillis = 800,
        satellitesUsed = 14,
        satellitesInView = 24,
    )

    val fairGps = goodGps.copy(
        quality = GpsSignalQuality.FAIR,
        horizontalAccuracyM = 19f,
        satellitesUsed = 6,
        satellitesInView = 11,
    )

    val searchingGps = GpsStatus(providerEnabled = true, quality = GpsSignalQuality.NONE)

    val gpsOff = GpsStatus(providerEnabled = false, quality = GpsSignalQuality.NONE)

    /** Paused time inside the sample trips, so their total time is longer than their active time. */
    private const val EARLIER_PAUSE_MILLIS = 300_000L

    private val runningStats =
        TripStats(
            distanceMeters = 27_800.0,
            activeDurationMillis = 2_292_000L,
            maxSpeedMps = 31.4f,
            movingDurationMillis = 2_040_000L,
            ascentMeters = 124.0,
            descentMeters = 97.0,
        )

    fun live(speedMps: Float) = LiveSpeed.Available(smoothedMps = speedMps, rawMps = speedMps)

    val idleSearching = TrackingState(
        permission = LocationPermissionState.GRANTED_PRECISE,
        gps = searchingGps,
    )

    val idleStationary = TrackingState(
        permission = LocationPermissionState.GRANTED_PRECISE,
        gps = goodGps,
        liveSpeed = live(0f),
    )

    val idleMoving = idleStationary.copy(liveSpeed = live(15f))

    val running = TrackingState(
        permission = LocationPermissionState.GRANTED_PRECISE,
        gps = goodGps,
        liveSpeed = live(15.0f),
        trip = TripState(
            phase = TripPhase.RUNNING,
            tripId = "trip-running",
            startedAtEpochMillis = NOW - 2_292_000L - EARLIER_PAUSE_MILLIS,
            stats = runningStats,
        ),
    )

    val runningAwaitingFix = running.copy(
        gps = searchingGps,
        liveSpeed = LiveSpeed.Unavailable,
        trip = running.trip.copy(stats = TripStats(), awaitingFix = true),
    )

    val paused = running.copy(
        liveSpeed = live(0f),
        trip = running.trip.copy(phase = TripPhase.PAUSED),
    )

    val recovered = paused.copy(
        gps = searchingGps,
        liveSpeed = LiveSpeed.Unavailable,
        trip = paused.trip.copy(recovered = true),
    )

    val finished = running.copy(
        liveSpeed = live(0f),
        trip = running.trip.copy(phase = TripPhase.FINISHED),
    )

    val permissionDenied = TrackingState(permission = LocationPermissionState.DENIED)

    val approximateOnly = TrackingState(permission = LocationPermissionState.GRANTED_APPROXIMATE_ONLY)

    val providerOff = TrackingState(permission = LocationPermissionState.GRANTED_PRECISE, gps = gpsOff)

    val settingsMph = AppSettings(speedUnit = SpeedUnit.MPH)

    fun dashboard(
        state: TrackingState,
        unit: SpeedUnit = SpeedUnit.KMH,
        hasGpsHardware: Boolean = true,
        dialog: DashboardDialog? = null,
        isFinishing: Boolean = false,
        savedTrip: Trip? = null,
    ): DashboardUiState {
        val live =
            (state.liveSpeed as? LiveSpeed.Available)?.let {
                unit.fromMetersPerSecond(it.smoothedMps.toDouble()).toFloat()
            }
                ?: 0f
        return buildUiState(
            state = state,
            unit = unit,
            scale = GaugeScale.base(unit).extendedFor(unit, live),
            dialog = dialog,
            isFinishing = isFinishing,
            savedTrip = savedTrip,
            hasGpsHardware = hasGpsHardware,
            nowEpochMillis = NOW,
        )
    }

    val savedTrip = Trip(
        id = "trip-running",
        startedAtEpochMillis = NOW - 2_292_000L - EARLIER_PAUSE_MILLIS,
        endedAtEpochMillis = NOW,
        stats = runningStats,
    )

    val trips: List<Trip> = listOf(
        savedTrip,
        Trip("trip-2", NOW - 5 * HOUR, NOW - 4 * HOUR - 1_800_000L, TripStats(48_210.0, 5_400_000L, 33.1f)),
        Trip("trip-3", NOW - DAY - 3 * HOUR, NOW - DAY - 2 * HOUR, TripStats(3_940.0, 1_320_000L, 11.8f)),
        Trip("trip-4", NOW - 4 * DAY, NOW - 4 * DAY + 2_700_000L, TripStats(21_075.5, 2_700_000L, 24.6f)),
    )
}

package app.wayneedle.data.db

import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripCheckpoint
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripStats

internal fun aTrip(
    id: String = "trip-1",
    startedAtEpochMillis: Long = 1_700_000_000_000L,
    durationMillis: Long = 600_000L,
    distanceMeters: Double = 5_000.0,
    maxSpeedMps: Float = 25f,
): Trip = Trip(
    id = id,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = startedAtEpochMillis + durationMillis,
    stats = TripStats(
        distanceMeters = distanceMeters,
        activeDurationMillis = durationMillis,
        maxSpeedMps = maxSpeedMps,
    ),
)

internal fun aCheckpoint(tripId: String = "trip-1"): TripCheckpoint = TripCheckpoint(
    tripId = tripId,
    phase = TripPhase.RUNNING,
    startedAtEpochMillis = 1_700_000_000_000L,
    stats = TripStats(distanceMeters = 1_234.5, activeDurationMillis = 90_000L, maxSpeedMps = 18.5f),
    savedAtEpochMillis = 1_700_000_090_000L,
)

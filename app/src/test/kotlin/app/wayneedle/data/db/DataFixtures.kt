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

internal fun aCheckpoint(
    tripId: String = "trip-1",
    phase: TripPhase = TripPhase.RUNNING,
    startedAtEpochMillis: Long = 1_700_000_000_000L,
    stats: TripStats = TripStats(distanceMeters = 1_234.5, activeDurationMillis = 90_000L, maxSpeedMps = 18.5f),
    savedAtEpochMillis: Long = 1_700_000_090_000L,
): TripCheckpoint = TripCheckpoint(
    tripId = tripId,
    phase = phase,
    startedAtEpochMillis = startedAtEpochMillis,
    stats = stats,
    savedAtEpochMillis = savedAtEpochMillis,
)

internal fun aTripEntity(
    id: String = "trip-1",
    startedAtEpochMillis: Long = 1_700_000_000_000L,
    endedAtEpochMillis: Long = 1_700_000_600_000L,
    distanceMeters: Double = 5_000.0,
    activeDurationMillis: Long = 600_000L,
    maxSpeedMps: Float = 25f,
): TripEntity = TripEntity(
    id = id,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    distanceMeters = distanceMeters,
    activeDurationMillis = activeDurationMillis,
    maxSpeedMps = maxSpeedMps,
)

internal fun aCheckpointEntity(
    tripId: String = "trip-1",
    phase: String = "RUNNING",
    startedAtEpochMillis: Long = 1_700_000_000_000L,
    distanceMeters: Double = 1_234.5,
    activeDurationMillis: Long = 90_000L,
    maxSpeedMps: Float = 18.5f,
    savedAtEpochMillis: Long = 1_700_000_090_000L,
): TripCheckpointEntity = TripCheckpointEntity(
    tripId = tripId,
    phase = phase,
    startedAtEpochMillis = startedAtEpochMillis,
    distanceMeters = distanceMeters,
    activeDurationMillis = activeDurationMillis,
    maxSpeedMps = maxSpeedMps,
    savedAtEpochMillis = savedAtEpochMillis,
)

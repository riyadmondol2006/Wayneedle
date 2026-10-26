package app.wayneedle.data.db

import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripCheckpoint
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripStats

/*
 * Validation policy.
 *
 * Trips (history): lenient in both directions. A non-finite or negative measurement becomes zero, a
 * negative timestamp becomes zero, and an end time before the start time is raised to the start time.
 * The optional measurements of schema 2 (moving time, ascent, descent) become null ("not measured") instead.
 * One bad field must never hide or crash the whole history. Only a blank id cannot be repaired: writing
 * one is a programming error (IllegalArgumentException), reading one skips the row (null).
 *
 * Checkpoints (crash recovery): lenient when writing (same repairs, so a slightly odd live value never
 * loses the checkpoint) but strict when reading. A stored row with a blank id, an unknown phase, a
 * non-finite/negative measurement or a negative timestamp is corrupt, and recovering from guessed
 * numbers would silently produce a wrong trip, so it is rejected (null) and the store clears it.
 */

private val CheckpointPhases = setOf(TripPhase.RUNNING, TripPhase.PAUSED)

private fun Double.orZeroIfInvalid(): Double = if (isFinite() && this > 0.0) this else 0.0

private fun Float.orZeroIfInvalid(): Float = if (isFinite() && this > 0f) this else 0f

private fun Long.orZeroIfNegative(): Long = if (this > 0L) this else 0L

private fun Double.isValidMeasurement(): Boolean = isFinite() && this >= 0.0

private fun Double?.orNullIfInvalid(): Double? = this?.takeIf { it.isValidMeasurement() }

private fun Long?.orNullIfNegative(): Long? = this?.takeIf { it >= 0L }

private fun Float.isValidMeasurement(): Boolean = isFinite() && this >= 0f

internal fun Trip.toEntity(): TripEntity {
    require(id.isNotBlank()) { "Trip id must not be blank" }
    return cleanedTripEntity(
        id = id,
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        distanceMeters = stats.distanceMeters,
        activeDurationMillis = stats.activeDurationMillis,
        maxSpeedMps = stats.maxSpeedMps,
        movingDurationMillis = stats.movingDurationMillis,
        ascentMeters = stats.ascentMeters,
        descentMeters = stats.descentMeters,
    )
}

/** Returns null only for an unusable row (blank id); every other defect is repaired, see the policy above. */
internal fun TripEntity.toDomainOrNull(): Trip? {
    if (id.isBlank()) return null
    val clean = cleanedTripEntity(
        id = id,
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        distanceMeters = distanceMeters,
        activeDurationMillis = activeDurationMillis,
        maxSpeedMps = maxSpeedMps,
        movingDurationMillis = movingDurationMillis,
        ascentMeters = ascentMeters,
        descentMeters = descentMeters,
    )
    return Trip(
        id = clean.id,
        startedAtEpochMillis = clean.startedAtEpochMillis,
        endedAtEpochMillis = clean.endedAtEpochMillis,
        stats = TripStats(
            distanceMeters = clean.distanceMeters,
            activeDurationMillis = clean.activeDurationMillis,
            maxSpeedMps = clean.maxSpeedMps,
            movingDurationMillis = clean.movingDurationMillis,
            ascentMeters = clean.ascentMeters,
            descentMeters = clean.descentMeters,
        ),
    )
}

private fun cleanedTripEntity(
    id: String,
    startedAtEpochMillis: Long,
    endedAtEpochMillis: Long,
    distanceMeters: Double,
    activeDurationMillis: Long,
    maxSpeedMps: Float,
    movingDurationMillis: Long?,
    ascentMeters: Double?,
    descentMeters: Double?,
): TripEntity {
    val started = startedAtEpochMillis.orZeroIfNegative()
    return TripEntity(
        id = id,
        startedAtEpochMillis = started,
        endedAtEpochMillis = maxOf(endedAtEpochMillis.orZeroIfNegative(), started),
        distanceMeters = distanceMeters.orZeroIfInvalid(),
        activeDurationMillis = activeDurationMillis.orZeroIfNegative(),
        maxSpeedMps = maxSpeedMps.orZeroIfInvalid(),
        movingDurationMillis = movingDurationMillis.orNullIfNegative(),
        ascentMeters = ascentMeters.orNullIfInvalid(),
        descentMeters = descentMeters.orNullIfInvalid(),
    )
}

/**
 * @throws IllegalArgumentException if the id is blank or the phase is not RUNNING/PAUSED; a checkpoint
 *   only exists for an in-progress trip, so anything else is a caller bug.
 */
internal fun TripCheckpoint.toEntity(): TripCheckpointEntity {
    require(tripId.isNotBlank()) { "Checkpoint trip id must not be blank" }
    require(phase in CheckpointPhases) { "Checkpoint phase must be RUNNING or PAUSED, was $phase" }
    return TripCheckpointEntity(
        tripId = tripId,
        phase = phase.name,
        startedAtEpochMillis = startedAtEpochMillis.orZeroIfNegative(),
        distanceMeters = stats.distanceMeters.orZeroIfInvalid(),
        activeDurationMillis = stats.activeDurationMillis.orZeroIfNegative(),
        maxSpeedMps = stats.maxSpeedMps.orZeroIfInvalid(),
        savedAtEpochMillis = savedAtEpochMillis.orZeroIfNegative(),
        movingDurationMillis = stats.movingDurationMillis.orNullIfNegative(),
        ascentMeters = stats.ascentMeters.orNullIfInvalid(),
        descentMeters = stats.descentMeters.orNullIfInvalid(),
    )
}

/** Returns null for any corrupt row, see the policy above. */
internal fun TripCheckpointEntity.toDomainOrNull(): TripCheckpoint? {
    val parsedPhase = TripPhase.entries.firstOrNull { it.name == phase }?.takeIf { it in CheckpointPhases }
        ?: return null
    val valid = tripId.isNotBlank() &&
        startedAtEpochMillis >= 0L &&
        savedAtEpochMillis >= 0L &&
        distanceMeters.isValidMeasurement() &&
        activeDurationMillis >= 0L &&
        maxSpeedMps.isValidMeasurement() &&
        (movingDurationMillis == null || movingDurationMillis >= 0L) &&
        (ascentMeters == null || ascentMeters.isValidMeasurement()) &&
        (descentMeters == null || descentMeters.isValidMeasurement())
    if (!valid) return null
    return TripCheckpoint(
        tripId = tripId,
        phase = parsedPhase,
        startedAtEpochMillis = startedAtEpochMillis,
        stats = TripStats(
            distanceMeters = distanceMeters,
            activeDurationMillis = activeDurationMillis,
            maxSpeedMps = maxSpeedMps,
            movingDurationMillis = movingDurationMillis,
            ascentMeters = ascentMeters,
            descentMeters = descentMeters,
        ),
        savedAtEpochMillis = savedAtEpochMillis,
    )
}

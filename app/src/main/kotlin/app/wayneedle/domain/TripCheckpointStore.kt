package app.wayneedle.domain

import app.wayneedle.domain.model.TripCheckpoint

/** Durable single-slot store for the in-progress trip. At most one checkpoint exists. */
interface TripCheckpointStore {
    suspend fun save(checkpoint: TripCheckpoint)

    suspend fun load(): TripCheckpoint?

    suspend fun clear()
}

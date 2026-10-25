package app.wayneedle.data.db

import app.wayneedle.domain.TripCheckpointStore
import app.wayneedle.domain.model.TripCheckpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Room-backed single-slot [TripCheckpointStore].
 *
 * All operations are serialised by a [Mutex] (fair, so they run in call order). That matters for [load]:
 * it may clear a corrupt row, and without the lock a concurrent [save] could be wiped by that clear.
 * Use one instance per database.
 *
 * * [save] replaces the slot. It throws `IllegalArgumentException` for a checkpoint that cannot exist
 *   (blank id, phase other than RUNNING/PAUSED) and lets database write failures propagate to the caller.
 * * [load] never throws. A corrupt or undecodable row yields null and is cleared.
 * * [clear] is idempotent.
 */
class RoomTripCheckpointStore(private val dao: TripCheckpointDao) : TripCheckpointStore {
    private val mutex = Mutex()

    override suspend fun save(checkpoint: TripCheckpoint) {
        val entity = checkpoint.toEntity()
        mutex.withLock { dao.upsert(entity) }
    }

    override suspend fun load(): TripCheckpoint? = mutex.withLock {
        val entity = try {
            dao.get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The row could not even be decoded (e.g. a NULL in a NOT NULL column): same as corrupt.
            discardQuietly()
            return@withLock null
        }
        val checkpoint = entity?.toDomainOrNull()
        if (entity != null && checkpoint == null) discardQuietly()
        checkpoint
    }

    override suspend fun clear() {
        mutex.withLock { dao.clear() }
    }

    private suspend fun discardQuietly() {
        try {
            dao.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: a store that cannot even delete is also one that cannot hold a trip.
        }
    }
}

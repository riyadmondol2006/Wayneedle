package app.wayneedle.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield

/** In-memory [TripDao] mirroring the real queries: keyed by id, newest start first, id as tie-break. */
internal class FakeTripDao : TripDao {
    private val rows = MutableStateFlow<Map<String, TripEntity>>(emptyMap())

    var upsertCalls = 0
        private set

    val storedCount: Int get() = rows.value.size

    override fun observeAll(): Flow<List<TripEntity>> = rows.map { map ->
        map.values.sortedWith(compareByDescending<TripEntity> { it.startedAtEpochMillis }.thenBy { it.id })
    }

    override fun observeById(id: String): Flow<TripEntity?> = rows.map { it[id] }

    override suspend fun getById(id: String): TripEntity? = rows.value[id]

    override suspend fun upsert(trip: TripEntity) {
        upsertCalls++
        rows.value = rows.value + (trip.id to trip)
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }
}

/**
 * In-memory single-slot [TripCheckpointDao]. [get] and [upsert] yield between steps so that concurrent
 * callers really interleave, which is what the store's locking has to survive.
 */
internal class FakeTripCheckpointDao : TripCheckpointDao {
    @Volatile
    var stored: TripCheckpointEntity? = null

    /** When set, [get] throws this, simulating a row that cannot be decoded. */
    @Volatile
    var failOnGet: Throwable? = null

    @Volatile
    var failOnClear: Throwable? = null

    @Volatile
    var clearCalls = 0

    override suspend fun upsert(checkpoint: TripCheckpointEntity) {
        yield()
        stored = checkpoint
    }

    override suspend fun get(): TripCheckpointEntity? {
        val snapshot = stored
        yield()
        failOnGet?.let { throw it }
        return snapshot
    }

    override suspend fun clear() {
        clearCalls++
        yield()
        failOnClear?.let { throw it }
        stored = null
    }
}

package app.wayneedle.data.db

import app.wayneedle.domain.TripRepository
import app.wayneedle.domain.model.Trip
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Room-backed [TripRepository].
 *
 * Queries run on Room's own executors; entity-to-domain mapping of the observed flows is moved to
 * [mappingDispatcher] so a long history never maps on the collector's (usually main) thread. Rows that
 * cannot be mapped (see `toDomainOrNull`) are skipped rather than failing the whole list.
 */
class RoomTripRepository(
    private val dao: TripDao,
    private val mappingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TripRepository {
    override fun observeTrips(): Flow<List<Trip>> = dao.observeAll()
        .map { rows -> rows.mapNotNull(TripEntity::toDomainOrNull) }
        .distinctUntilChanged()
        .flowOn(mappingDispatcher)

    override fun observeTrip(id: String): Flow<Trip?> = dao.observeById(id)
        .map { row -> row?.toDomainOrNull() }
        .distinctUntilChanged()
        .flowOn(mappingDispatcher)

    override suspend fun getTrip(id: String): Trip? = dao.getById(id)?.toDomainOrNull()

    /** Upsert by id: calling this twice with the same trip leaves exactly one row. */
    override suspend fun saveTrip(trip: Trip) {
        dao.upsert(trip.toEntity())
    }

    override suspend fun deleteTrip(id: String) {
        dao.deleteById(id)
    }

    override suspend fun deleteAllTrips() {
        dao.deleteAll()
    }
}

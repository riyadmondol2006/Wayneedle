package app.wayneedle.domain

import app.wayneedle.domain.model.Trip
import kotlinx.coroutines.flow.Flow

interface TripRepository {
    /** All finished trips, newest first. */
    fun observeTrips(): Flow<List<Trip>>

    /** Emits null if the trip does not exist (e.g. deleted). */
    fun observeTrip(id: String): Flow<Trip?>

    suspend fun getTrip(id: String): Trip?

    /** Insert-or-replace by id. MUST be idempotent: saving the same trip twice never duplicates it. */
    suspend fun saveTrip(trip: Trip)

    suspend fun deleteTrip(id: String)

    suspend fun deleteAllTrips()
}

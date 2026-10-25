package app.wayneedle.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    /** Newest first by start time; the id tie-break keeps the order stable for equal start times. */
    @Query("SELECT * FROM trips ORDER BY started_at_epoch_millis DESC, id ASC")
    fun observeAll(): Flow<List<TripEntity>>

    /** Emits null while the trip does not exist, including after it is deleted. */
    @Query("SELECT * FROM trips WHERE id = :id")
    fun observeById(id: String): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: String): TripEntity?

    /** Insert-or-replace by primary key, so saving the same trip twice never duplicates it. */
    @Upsert
    suspend fun upsert(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}

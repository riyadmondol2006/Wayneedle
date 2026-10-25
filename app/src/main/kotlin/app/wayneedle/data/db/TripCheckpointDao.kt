package app.wayneedle.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TripCheckpointDao {
    /** Replaces whatever checkpoint exists: [TripCheckpointEntity.slot] is the fixed primary key. */
    @Upsert
    suspend fun upsert(checkpoint: TripCheckpointEntity)

    @Query("SELECT * FROM trip_checkpoint WHERE slot = ${TripCheckpointEntity.SLOT}")
    suspend fun get(): TripCheckpointEntity?

    @Query("DELETE FROM trip_checkpoint")
    suspend fun clear()
}

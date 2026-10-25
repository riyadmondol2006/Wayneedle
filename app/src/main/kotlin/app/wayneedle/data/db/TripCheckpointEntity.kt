package app.wayneedle.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-slot table holding the in-progress trip. The primary key is the constant [SLOT], so at most one
 * row can ever exist and an upsert always replaces the previous checkpoint.
 *
 * [phase] is deliberately a plain string rather than a Room type converter: an unknown value must be
 * readable (and then rejected by the mapper) instead of crashing the query.
 */
@Entity(tableName = TripCheckpointEntity.TABLE_NAME)
data class TripCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "slot")
    val slot: Int = SLOT,
    @ColumnInfo(name = "trip_id")
    val tripId: String,
    @ColumnInfo(name = "phase")
    val phase: String,
    @ColumnInfo(name = "started_at_epoch_millis")
    val startedAtEpochMillis: Long,
    @ColumnInfo(name = "distance_meters")
    val distanceMeters: Double,
    @ColumnInfo(name = "active_duration_millis")
    val activeDurationMillis: Long,
    @ColumnInfo(name = "max_speed_mps")
    val maxSpeedMps: Float,
    @ColumnInfo(name = "saved_at_epoch_millis")
    val savedAtEpochMillis: Long,
) {
    companion object {
        const val TABLE_NAME = "trip_checkpoint"

        /** The one and only primary-key value. */
        const val SLOT = 1
    }
}

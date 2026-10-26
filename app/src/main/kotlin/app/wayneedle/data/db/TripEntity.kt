package app.wayneedle.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A finished trip. Only the base measurements are stored; the average speed is derived by
 * `TripStats.averageSpeedMps` so it can never disagree with the stored distance and duration.
 *
 * [id] is the trip UUID created at start, which makes saving idempotent. The index on the start time
 * backs the newest-first history query.
 */
@Entity(
    tableName = TripEntity.TABLE_NAME,
    indices = [Index(value = ["started_at_epoch_millis"], name = "index_trips_started_at_epoch_millis")],
)
data class TripEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "started_at_epoch_millis")
    val startedAtEpochMillis: Long,
    @ColumnInfo(name = "ended_at_epoch_millis")
    val endedAtEpochMillis: Long,
    @ColumnInfo(name = "distance_meters")
    val distanceMeters: Double,
    @ColumnInfo(name = "active_duration_millis")
    val activeDurationMillis: Long,
    @ColumnInfo(name = "max_speed_mps")
    val maxSpeedMps: Float,
    /** Since schema 2; null for trips recorded before (unknown, not zero). */
    @ColumnInfo(name = "moving_duration_millis")
    val movingDurationMillis: Long? = null,
    @ColumnInfo(name = "ascent_meters")
    val ascentMeters: Double? = null,
    @ColumnInfo(name = "descent_meters")
    val descentMeters: Double? = null,
) {
    companion object {
        const val TABLE_NAME = "trips"
    }
}

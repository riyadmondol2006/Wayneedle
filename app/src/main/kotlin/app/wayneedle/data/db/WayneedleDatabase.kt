package app.wayneedle.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's only Room database. Schemas are exported to `app/schemas` and must be committed.
 *
 * Obtain instances through [create] (on-disk, one per process) or [createInMemory] (tests). Callers own
 * the instance; this class keeps no global state.
 */
@Database(
    entities = [TripEntity::class, TripCheckpointEntity::class],
    version = WayneedleDatabase.VERSION,
    exportSchema = true,
)
abstract class WayneedleDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    abstract fun tripCheckpointDao(): TripCheckpointDao

    companion object {
        const val VERSION = 2
        const val FILE_NAME = "wayneedle.db"

        /**
         * Opens (creating if needed) the on-disk database. Create it once and share it; two instances on
         * the same file would not see each other's Flow invalidations.
         */
        fun create(context: Context): WayneedleDatabase =
            Room.databaseBuilder(context.applicationContext, WayneedleDatabase::class.java, FILE_NAME)
                .addMigrations(*Migrations.all)
                .build()

        /** A throw-away database that disappears when closed; for instrumentation tests. */
        fun createInMemory(context: Context): WayneedleDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, WayneedleDatabase::class.java)
                .build()
    }
}

package app.wayneedle.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit schema migrations. Policy:
 *
 * * Version 1 is the first release. Version 2 adds moving time, ascent and descent ([MIGRATION_1_2]).
 * * There is no destructive-migration fallback anywhere: trips are the user's data, so a missing
 *   migration must fail loudly in the migration test instead of silently wiping the history.
 * * Every `@Database(version = N)` bump adds a `Migration(N - 1, N)` here, registers it in [all], and adds a
 *   test in `WayneedleDatabaseMigrationTest` that creates the old schema from `app/schemas`, inserts rows,
 *   migrates, and validates the result. Commit the newly exported schema JSON together with the migration.
 */
internal object Migrations {
    /**
     * 1 → 2: moving time, ascent and descent for trips and the checkpoint. Nullable columns without a default,
     * so every trip recorded before keeps its data and reads these as "not measured" rather than zero.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (table in listOf(TripEntity.TABLE_NAME, TripCheckpointEntity.TABLE_NAME)) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `moving_duration_millis` INTEGER")
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `ascent_meters` REAL")
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `descent_meters` REAL")
            }
        }
    }

    val all: Array<Migration> = arrayOf(MIGRATION_1_2)
}

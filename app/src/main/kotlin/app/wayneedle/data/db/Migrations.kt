package app.wayneedle.data.db

import androidx.room.migration.Migration

/**
 * Explicit schema migrations. Policy:
 *
 * * Version 1 is the first release; there is nothing to migrate yet.
 * * There is no destructive-migration fallback anywhere: trips are the user's data, so a missing
 *   migration must fail loudly in the migration test instead of silently wiping the history.
 * * Every `@Database(version = N)` bump adds a `Migration(N - 1, N)` here, registers it in [all], and adds a
 *   test in `WayneedleDatabaseMigrationTest` that creates the old schema from `app/schemas`, inserts rows,
 *   migrates, and validates the result. Commit the newly exported schema JSON together with the migration.
 */
internal object Migrations {
    val all: Array<Migration> = emptyArray()
}

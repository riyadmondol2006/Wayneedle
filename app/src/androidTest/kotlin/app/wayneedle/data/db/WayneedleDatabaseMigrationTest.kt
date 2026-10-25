package app.wayneedle.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema and migration checks against the JSON exported to `app/schemas`. The Room Gradle plugin copies
 * that directory into the androidTest assets, which is where [MigrationTestHelper] reads it from.
 *
 * When bumping [WayneedleDatabase.VERSION] to N: add `Migration(N - 1, N)` to `Migrations.all` and a test
 * of this shape, then commit the newly exported `N.json`:
 *
 * ```
 * @Test
 * fun migrate1To2() {
 *     helper.createDatabase(TEST_DB, 1).apply { execSQL("INSERT INTO trips ..."); close() }
 *     val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, *Migrations.all)
 *     // query the migrated database and assert on the data
 * }
 * ```
 */
@RunWith(AndroidJUnit4::class)
class WayneedleDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, WayneedleDatabase::class.java)

    @After
    fun deleteDatabase() {
        instrumentation.targetContext.deleteDatabase(TEST_DB)
    }

    @Test
    fun version1IsCreatedFromTheExportedSchemaAndValidates() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(TEST_DB, 1, true).close()
    }

    @Test
    fun aVersion1DatabaseWithDataOpensWithRoom() = runBlocking {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO trips (id, started_at_epoch_millis, ended_at_epoch_millis, " +
                    "distance_meters, active_duration_millis, max_speed_mps) " +
                    "VALUES ('v1-trip', 1000, 61000, 1234.5, 60000, 12.5)",
            )
            close()
        }

        val database = Room.databaseBuilder(instrumentation.targetContext, WayneedleDatabase::class.java, TEST_DB)
            .addMigrations(*Migrations.all)
            .build()
        helper.closeWhenFinished(database)

        val trip = database.tripDao().getById("v1-trip")
        assertEquals(1_234.5, trip?.distanceMeters ?: Double.NaN, 0.0)
        assertEquals(60_000L, trip?.activeDurationMillis)
    }

    @Test
    fun everyVersionStepHasAnExplicitMigration() {
        val steps = Migrations.all.map { it.startVersion to it.endVersion }

        for (from in 1 until WayneedleDatabase.VERSION) {
            assertTrue("Missing migration $from -> ${from + 1}", (from to from + 1) in steps)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}

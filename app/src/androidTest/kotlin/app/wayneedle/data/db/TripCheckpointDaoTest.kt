package app.wayneedle.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripCheckpointDaoTest {
    @get:Rule
    val databaseRule = InMemoryDatabaseRule()

    private val dao get() = databaseRule.database.tripCheckpointDao()

    private fun checkpoint(tripId: String, distance: Double = 500.0) = TripCheckpointEntity(
        tripId = tripId,
        phase = "RUNNING",
        startedAtEpochMillis = 1_000L,
        distanceMeters = distance,
        activeDurationMillis = 30_000L,
        maxSpeedMps = 12f,
        savedAtEpochMillis = 31_000L,
    )

    private fun rowCount(): Int =
        databaseRule.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM trip_checkpoint").use {
            it.moveToFirst()
            it.getInt(0)
        }

    @Test
    fun emptyTableHasNoCheckpoint() = runBlocking {
        assertNull(dao.get())
    }

    @Test
    fun upsertThenGetRoundTrips() = runBlocking {
        val saved = checkpoint("trip-1")

        dao.upsert(saved)

        assertEquals(saved, dao.get())
    }

    @Test
    fun theTableHoldsAtMostOneRowAndTheLatestSaveWins() = runBlocking {
        dao.upsert(checkpoint("first"))
        dao.upsert(checkpoint("second", distance = 900.0))
        dao.upsert(checkpoint("third", distance = 1_200.0))

        assertEquals(1, rowCount())
        assertEquals("third", dao.get()?.tripId)
        assertEquals(TripCheckpointEntity.SLOT, dao.get()?.slot)
    }

    @Test
    fun clearEmptiesTheSlotAndIsIdempotent() = runBlocking {
        dao.upsert(checkpoint("trip-1"))

        dao.clear()
        dao.clear()

        assertNull(dao.get())
        assertEquals(0, rowCount())
    }

    @Test
    fun storeReplacesLoadsAndClearsOnARealDatabase() = runBlocking {
        val store = RoomTripCheckpointStore(dao)
        val first = aCheckpoint(tripId = "first")
        val second = aCheckpoint(tripId = "second")

        assertNull(store.load())
        store.save(first)
        store.save(second)
        assertEquals(second, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun storeClearsAndIgnoresACorruptRow() = runBlocking {
        databaseRule.database.openHelper.writableDatabase.execSQL(
            "INSERT INTO trip_checkpoint " +
                "(slot, trip_id, phase, started_at_epoch_millis, distance_meters, " +
                "active_duration_millis, max_speed_mps, saved_at_epoch_millis) " +
                "VALUES (1, 'trip', 'NOT_A_PHASE', 1, 2.0, 3, 4.0, 5)",
        )
        val store = RoomTripCheckpointStore(dao)

        assertNull(store.load())

        assertEquals(0, rowCount())
    }
}

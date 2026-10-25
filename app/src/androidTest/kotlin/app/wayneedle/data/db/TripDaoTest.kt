package app.wayneedle.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripDaoTest {
    @get:Rule
    val databaseRule = InMemoryDatabaseRule()

    private val dao get() = databaseRule.database.tripDao()

    private fun entity(id: String, startedAt: Long, distance: Double = 1_000.0) = TripEntity(
        id = id,
        startedAtEpochMillis = startedAt,
        endedAtEpochMillis = startedAt + 60_000L,
        distanceMeters = distance,
        activeDurationMillis = 60_000L,
        maxSpeedMps = 20f,
    )

    private suspend fun rowCount(): Int = dao.observeAll().first().size

    @Test
    fun emptyDatabaseObservesEmptyList() = runBlocking {
        assertEquals(emptyList<TripEntity>(), dao.observeAll().first())
    }

    @Test
    fun tripsAreOrderedNewestFirstWithStableTieBreak() = runBlocking {
        dao.upsert(entity("b", startedAt = 2_000L))
        dao.upsert(entity("oldest", startedAt = 1_000L))
        dao.upsert(entity("newest", startedAt = 3_000L))
        dao.upsert(entity("a", startedAt = 2_000L))

        assertEquals(listOf("newest", "a", "b", "oldest"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun upsertIsIdempotentAndReplacesValues() = runBlocking {
        dao.upsert(entity("trip", startedAt = 1_000L, distance = 100.0))
        dao.upsert(entity("trip", startedAt = 1_000L, distance = 100.0))
        assertEquals(1, rowCount())

        val updated = entity("trip", startedAt = 1_000L, distance = 250.0)
        dao.upsert(updated)

        assertEquals(1, rowCount())
        assertEquals(updated, dao.getById("trip"))
    }

    @Test
    fun getByIdReturnsNullForUnknownTrip() = runBlocking {
        assertNull(dao.getById("missing"))
    }

    @Test
    fun observeByIdEmitsNullAfterDeletion() = runBlocking {
        val trip = entity("trip", startedAt = 1_000L)
        dao.upsert(trip)
        assertEquals(trip, dao.observeById("trip").first())

        dao.deleteById("trip")

        assertNull(dao.observeById("trip").first())
        assertNull(dao.getById("trip"))
    }

    @Test
    fun deleteByIdKeepsOtherTripsAndToleratesUnknownIds() = runBlocking {
        dao.upsert(entity("keep", startedAt = 1_000L))
        dao.upsert(entity("drop", startedAt = 2_000L))

        dao.deleteById("drop")
        dao.deleteById("never-existed")

        assertEquals(listOf("keep"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun deleteAllEmptiesTheTable() = runBlocking {
        dao.upsert(entity("a", startedAt = 1_000L))
        dao.upsert(entity("b", startedAt = 2_000L))

        dao.deleteAll()

        assertEquals(0, rowCount())
    }

    @Test
    fun observeAllReEmitsWhenTheTableChanges() = runBlocking {
        val emissions = Channel<List<String>>(Channel.UNLIMITED)
        val collector = launch { dao.observeAll().collect { emissions.send(it.map(TripEntity::id)) } }

        withTimeout(TIMEOUT_MILLIS) {
            assertEquals(emptyList<String>(), emissions.receive())
            dao.upsert(entity("a", startedAt = 1_000L))
            assertEquals(listOf("a"), emissions.receive())
            dao.upsert(entity("b", startedAt = 2_000L))
            assertEquals(listOf("b", "a"), emissions.receive())
            dao.deleteById("b")
            assertEquals(listOf("a"), emissions.receive())
        }
        collector.cancel()
    }

    @Test
    fun startTimeIndexExists() {
        val indexNames = mutableListOf<String>()
        databaseRule.database.openHelper.readableDatabase.query("PRAGMA index_list('trips')").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) indexNames += cursor.getString(nameColumn)
        }

        assertTrue(indexNames.toString(), "index_trips_started_at_epoch_millis" in indexNames)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}

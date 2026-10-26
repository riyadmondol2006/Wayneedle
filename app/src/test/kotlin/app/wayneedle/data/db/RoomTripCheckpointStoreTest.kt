package app.wayneedle.data.db

import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomTripCheckpointStoreTest {
    private val dao = FakeTripCheckpointDao()
    private val store = RoomTripCheckpointStore(dao)

    @Test
    fun `load is null when nothing was saved`() = runTest {
        assertNull(store.load())
    }

    @Test
    fun `save then load returns the checkpoint`() = runTest {
        val checkpoint = aCheckpoint(phase = TripPhase.PAUSED)

        store.save(checkpoint)

        assertEquals(checkpoint, store.load())
    }

    @Test
    fun `save replaces the previous checkpoint`() = runTest {
        store.save(aCheckpoint(tripId = "first"))
        val second = aCheckpoint(
            tripId = "second",
            stats = TripStats(distanceMeters = 9_999.0, activeDurationMillis = 1L, maxSpeedMps = 1f),
        )

        store.save(second)

        assertEquals(second, store.load())
        assertEquals(TripCheckpointEntity.SLOT, dao.stored?.slot)
    }

    @Test
    fun `clear removes the checkpoint and is idempotent`() = runTest {
        store.save(aCheckpoint())

        store.clear()
        store.clear()

        assertNull(store.load())
        assertNull(dao.stored)
    }

    @Test
    fun `a corrupt row loads as null and is cleared`() = runTest {
        dao.stored = aCheckpointEntity(phase = "SPRINTING")

        assertNull(store.load())

        assertNull(dao.stored)
    }

    @Test
    fun `a row with impossible numbers loads as null and is cleared`() = runTest {
        dao.stored = aCheckpointEntity(distanceMeters = Double.NaN)

        assertNull(store.load())

        assertNull(dao.stored)
    }

    @Test
    fun `an undecodable row loads as null instead of throwing, and is cleared`() = runTest {
        dao.stored = aCheckpointEntity()
        dao.failOnGet = IllegalStateException("NULL in NOT NULL column")

        assertNull(store.load())

        assertNull(dao.stored)
    }

    @Test
    fun `load does not throw even when clearing the corrupt row also fails`() = runTest {
        dao.stored = aCheckpointEntity(phase = "bogus")
        dao.failOnClear = IllegalStateException("disk")

        assertNull(store.load())
        assertEquals(1, dao.clearCalls)
    }

    @Test
    fun `a locked or failing database never deletes the checkpoint`() = runTest {
        store.save(aCheckpoint())
        // Stands in for SQLiteDatabaseLockedException / SQLiteDiskIOException (android.database.SQLException).
        class TransientDatabaseError : RuntimeException("database is locked")
        dao.failOnGet = TransientDatabaseError()
        assertFailsWith<TransientDatabaseError> { store.load() }
        dao.failOnGet = null
        assertEquals(aCheckpoint(), store.load(), "the in-progress trip survived the hiccup")
    }

    @Test
    fun `a valid row is never cleared by load`() = runTest {
        store.save(aCheckpoint())

        store.load()
        store.load()

        assertEquals(0, dao.clearCalls)
    }

    @Test
    fun `cancellation is not swallowed by load`() = runTest {
        dao.stored = aCheckpointEntity()
        dao.failOnGet = CancellationException("cancelled")

        assertFailsWith<CancellationException> { store.load() }
    }

    @Test
    fun `saving a checkpoint that cannot exist is rejected and stores nothing`() = runTest {
        assertFailsWith<IllegalArgumentException> { store.save(aCheckpoint(phase = TripPhase.FINISHED)) }

        assertNull(dao.stored)
    }

    @Test
    fun `concurrent saves loads and clears never throw and leave a consistent slot`() = runBlocking {
        val checkpoints = (0 until 8).map { aCheckpoint(tripId = "trip-$it") }

        withContext(Dispatchers.Default) {
            (0 until 400).map { i ->
                async {
                    when (i % 4) {
                        0, 1 -> store.save(checkpoints[i % checkpoints.size])
                        2 -> store.load()?.also { assertTrue(it in checkpoints) }
                        else -> store.clear()
                    }
                }
            }.awaitAll()
        }

        val finalState = store.load()
        assertTrue(finalState == null || finalState in checkpoints)
    }

    @Test
    fun `clearing a corrupt row never wipes a checkpoint saved concurrently`() = runBlocking {
        val fresh = aCheckpoint(tripId = "fresh")

        repeat(200) {
            dao.stored = aCheckpointEntity(phase = "bogus")

            withContext(Dispatchers.Default) {
                val load = async { store.load() }
                val save = async { store.save(fresh) }
                awaitAll(load, save)
            }

            assertEquals(fresh, store.load(), "iteration $it")
        }
    }
}

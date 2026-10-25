package app.wayneedle.data.db

import app.cash.turbine.test
import app.wayneedle.domain.model.TripStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class RoomTripRepositoryTest {
    private val dao = FakeTripDao()

    private fun TestScope.repository() = RoomTripRepository(dao, UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `saving the same trip twice leaves exactly one row`() = runTest {
        val repository = repository()
        val trip = aTrip(id = "a")

        repository.saveTrip(trip)
        repository.saveTrip(trip)

        assertEquals(1, dao.storedCount)
        repository.observeTrips().test {
            assertEquals(listOf(trip), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving again with the same id replaces the previous values`() = runTest {
        val repository = repository()
        repository.saveTrip(aTrip(id = "a", distanceMeters = 100.0))

        val updated = aTrip(id = "a", distanceMeters = 250.0)
        repository.saveTrip(updated)

        assertEquals(1, dao.storedCount)
        assertEquals(updated, repository.getTrip("a"))
    }

    @Test
    fun `trips are observed newest first`() = runTest {
        val repository = repository()
        val oldest = aTrip(id = "old", startedAtEpochMillis = 1_000L)
        val newest = aTrip(id = "new", startedAtEpochMillis = 3_000L)
        val middle = aTrip(id = "mid", startedAtEpochMillis = 2_000L)

        repository.saveTrip(oldest)
        repository.saveTrip(newest)
        repository.saveTrip(middle)

        repository.observeTrips().test {
            assertEquals(listOf(newest, middle, oldest), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observed list updates when trips are saved and deleted`() = runTest {
        val repository = repository()
        val first = aTrip(id = "a", startedAtEpochMillis = 1_000L)
        val second = aTrip(id = "b", startedAtEpochMillis = 2_000L)

        repository.observeTrips().test {
            assertEquals(emptyList(), awaitItem())
            repository.saveTrip(first)
            assertEquals(listOf(first), awaitItem())
            repository.saveTrip(second)
            assertEquals(listOf(second, first), awaitItem())
            repository.deleteTrip("b")
            assertEquals(listOf(first), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeTrip emits null for an unknown trip and after deletion`() = runTest {
        val repository = repository()
        val trip = aTrip(id = "a")

        repository.observeTrip("a").test {
            assertNull(awaitItem())
            repository.saveTrip(trip)
            assertEquals(trip, awaitItem())
            repository.deleteTrip("a")
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting one trip keeps the others and deleting an unknown id is harmless`() = runTest {
        val repository = repository()
        repository.saveTrip(aTrip(id = "a", startedAtEpochMillis = 1_000L))
        repository.saveTrip(aTrip(id = "b", startedAtEpochMillis = 2_000L))

        repository.deleteTrip("a")
        repository.deleteTrip("does-not-exist")

        assertNull(repository.getTrip("a"))
        assertEquals("b", repository.getTrip("b")?.id)
        assertEquals(1, dao.storedCount)
    }

    @Test
    fun `delete all removes every trip`() = runTest {
        val repository = repository()
        repository.saveTrip(aTrip(id = "a"))
        repository.saveTrip(aTrip(id = "b", startedAtEpochMillis = 5L))

        repository.deleteAllTrips()

        assertEquals(0, dao.storedCount)
        repository.observeTrips().test {
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getTrip returns null for an unknown id`() = runTest {
        assertNull(repository().getTrip("nope"))
    }

    @Test
    fun `unmappable rows are skipped rather than failing the list`() = runTest {
        val repository = repository()
        val good = aTrip(id = "good", startedAtEpochMillis = 1_000L)
        repository.saveTrip(good)
        dao.upsert(aTripEntity(id = "", startedAtEpochMillis = 9_000L))

        repository.observeTrips().test {
            assertEquals(listOf(good), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(repository.getTrip(""))
    }

    @Test
    fun `an unrelated unmappable row does not cause a duplicate emission`() = runTest {
        val repository = repository()
        val good = aTrip(id = "good")
        repository.saveTrip(good)

        repository.observeTrips().test {
            assertEquals(listOf(good), awaitItem())
            dao.upsert(aTripEntity(id = ""))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dirty stored values are repaired when read back`() = runTest {
        val repository = repository()
        dao.upsert(
            aTripEntity(id = "dirty", distanceMeters = Double.NaN, activeDurationMillis = -1L, maxSpeedMps = -2f),
        )

        assertEquals(TripStats.Empty, repository.getTrip("dirty")?.stats)
    }

    @Test
    fun `saving a trip with a blank id fails and stores nothing`() = runTest {
        val repository = repository()

        assertFailsWith<IllegalArgumentException> { repository.saveTrip(aTrip(id = "")) }

        assertEquals(0, dao.storedCount)
        assertEquals(0, dao.upsertCalls)
    }
}

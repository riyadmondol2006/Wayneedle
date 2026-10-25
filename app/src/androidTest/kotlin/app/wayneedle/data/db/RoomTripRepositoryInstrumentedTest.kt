package app.wayneedle.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The repository against the real DAO and SQLite (the JVM tests use a fake DAO). */
@RunWith(AndroidJUnit4::class)
class RoomTripRepositoryInstrumentedTest {
    @get:Rule
    val databaseRule = InMemoryDatabaseRule()

    private val repository get() = RoomTripRepository(databaseRule.database.tripDao())

    @Test
    fun savingTheSameTripTwiceStoresOneTrip() = runBlocking {
        val trip = aTrip(id = "trip")

        repository.saveTrip(trip)
        repository.saveTrip(trip)

        assertEquals(listOf(trip), repository.observeTrips().first())
    }

    @Test
    fun tripsRoundTripAndAreNewestFirst() = runBlocking {
        val older = aTrip(id = "older", startedAtEpochMillis = 1_000L)
        val newer = aTrip(id = "newer", startedAtEpochMillis = 9_000L, distanceMeters = 12_345.678)

        repository.saveTrip(older)
        repository.saveTrip(newer)

        assertEquals(listOf(newer, older), repository.observeTrips().first())
        assertEquals(newer, repository.getTrip("newer"))
    }

    @Test
    fun deleteAndObserveOneTrip() = runBlocking {
        val trip = aTrip(id = "trip")
        repository.saveTrip(trip)
        assertEquals(trip, repository.observeTrip("trip").first())

        repository.deleteTrip("trip")

        assertNull(repository.observeTrip("trip").first())
        assertNull(repository.getTrip("trip"))
    }

    @Test
    fun deleteAllRemovesEverything() = runBlocking {
        repository.saveTrip(aTrip(id = "a"))
        repository.saveTrip(aTrip(id = "b", startedAtEpochMillis = 5L))

        repository.deleteAllTrips()

        assertEquals(emptyList<Any>(), repository.observeTrips().first())
    }
}

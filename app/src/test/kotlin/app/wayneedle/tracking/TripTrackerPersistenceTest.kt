@file:OptIn(ExperimentalCoroutinesApi::class)

package app.wayneedle.tracking

import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripCheckpoint
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TripTrackerPersistenceTest {
    private val recoverable = TripCheckpoint(
        tripId = "4f9d1c1e-0000-4000-8000-000000000001",
        phase = TripPhase.RUNNING,
        startedAtEpochMillis = 1_600_000_000_000L,
        stats = TripStats(distanceMeters = 1_234.0, activeDurationMillis = 600_000L, maxSpeedMps = 25f),
        savedAtEpochMillis = 1_600_000_600_000L,
    )

    @Test
    fun `checkpoints are written on phase changes and every five seconds of active time`() = runTest {
        val h = TrackerHarness(this)
        h.tracker.startTrip()
        h.runCurrent()
        val tripId = assertNotNull(h.state.trip.tripId)
        fun written() = h.checkpoints.writes.map { it.phase to it.stats.activeDurationMillis }

        assertEquals(listOf(TripPhase.RUNNING to 0L), written())
        h.advance(4_000L)
        assertEquals(1, h.checkpoints.writes.size)
        h.advance(1_000L)
        h.advance(5_000L)
        assertEquals(
            listOf(TripPhase.RUNNING to 0L, TripPhase.RUNNING to 5_000L, TripPhase.RUNNING to 10_000L),
            written(),
        )

        h.advance(2_000L)
        h.tracker.pauseTrip()
        h.runCurrent()
        assertEquals(TripPhase.PAUSED to 12_000L, written().last())
        h.tracker.setUiVisible(true)
        h.advance(20_000L)
        assertEquals(4, h.checkpoints.writes.size, "nothing written while paused")

        h.tracker.resumeTrip()
        h.advance(5_000L)
        assertEquals(listOf(TripPhase.RUNNING to 12_000L, TripPhase.RUNNING to 17_000L), written().takeLast(2))

        val last = h.checkpoints.writes.last()
        assertEquals(tripId, last.tripId)
        assertEquals(h.state.trip.startedAtEpochMillis, last.startedAtEpochMillis)
        assertEquals(h.time.epochMillis(), last.savedAtEpochMillis)
    }

    @Test
    fun `an in-flight checkpoint write cannot resurrect a discarded trip`() = runTest {
        val store = InMemoryCheckpointStore()
        val h = TrackerHarness(this, checkpoints = store)
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate

        h.tracker.startTrip()
        h.runCurrent()
        assertEquals(1, store.saveCalls, "the start checkpoint is being written")
        assertNull(store.checkpoint)

        assertEquals(CommandResult.Ok, h.tracker.discardTrip())
        gate.complete(Unit)
        h.runCurrent()

        assertNull(store.checkpoint, "clear ran after the late write")
        assertEquals(1, store.writes.size)
        assertEquals(1, store.clearCalls)

        // A new process must not find anything to recover.
        val restarted = TrackerHarness(this, checkpoints = store)
        assertFalse(restarted.tracker.recoverInterruptedTrip())
        assertEquals(TripPhase.IDLE, restarted.state.trip.phase)
    }

    @Test
    fun `a queued checkpoint of a finished trip is skipped`() = runTest {
        val store = InMemoryCheckpointStore()
        val h = TrackerHarness(this, checkpoints = store)
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate

        h.tracker.startTrip()
        h.drive(6, speedMps = 10f)
        assertEquals(1, store.saveCalls, "writer blocked on the first write; later ones are queued")

        assertIs<FinishResult.Saved>(h.tracker.finishTrip())
        gate.complete(Unit)
        h.runCurrent()

        assertNull(store.checkpoint)
        assertEquals(1, store.saveCalls, "queued writes of the finished trip never reached the store")
        assertEquals(1, store.clearCalls)

        h.advance(30_000L)
        assertEquals(1, store.saveCalls, "no ticks write after finishing")
        assertNull(store.checkpoint)
    }

    @Test
    fun `recovery after discard in the same process sees the cleared store`() = runTest {
        val store = InMemoryCheckpointStore()
        val h = TrackerHarness(this, checkpoints = store)
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate
        h.tracker.startTrip()
        h.runCurrent()
        h.tracker.discardTrip()

        val recovered = async { h.tracker.recoverInterruptedTrip() }
        h.runCurrent()
        gate.complete(Unit)
        assertFalse(recovered.await())
        assertEquals(TripPhase.IDLE, h.state.trip.phase)
        assertNull(store.checkpoint)
    }

    @Test
    fun `recovery restores a paused trip and never starts the foreground service`() = runTest {
        val h = TrackerHarness(this, checkpoints = InMemoryCheckpointStore(recoverable))
        h.advance(3_600_000L)

        assertTrue(h.tracker.recoverInterruptedTrip())
        h.runCurrent()
        val trip = h.state.trip
        assertEquals(TripPhase.PAUSED, trip.phase)
        assertTrue(trip.recovered)
        assertEquals(recoverable.tripId, trip.tripId)
        assertEquals(recoverable.startedAtEpochMillis, trip.startedAtEpochMillis)
        assertEquals(recoverable.stats, trip.stats)
        assertTrue(h.foreground.calls.isEmpty())
        assertEquals(0, h.source.activeCollectors, "paused and not visible: no GPS")
        assertEquals(TripPhase.PAUSED, h.checkpoints.checkpoint?.phase)

        h.advance(3_600_000L)
        assertEquals(recoverable.stats, h.state.trip.stats, "the unobserved gap is never counted")
        assertFalse(h.tracker.recoverInterruptedTrip(), "only once")

        h.showUi()
        assertEquals(CommandResult.Ok, h.tracker.resumeTrip())
        assertEquals(listOf("start"), h.foreground.calls)
        assertFalse(h.state.trip.recovered)
        assertTrue(h.state.trip.awaitingFix)
        h.drive(3, speedMps = 10f)
        assertEquals(603_000L, h.state.trip.stats.activeDurationMillis)
        assertEquals(1_234.0 + 2 * 10.0, h.state.trip.stats.distanceMeters, 0.01)
    }

    @Test
    fun `recovery finds nothing when there is no checkpoint`() = runTest {
        val empty = TrackerHarness(this)
        assertFalse(empty.tracker.recoverInterruptedTrip())
        assertEquals(TripPhase.IDLE, empty.state.trip.phase)
    }

    @Test
    fun `tapping Start before recovery ran files the interrupted trip into History instead of losing it`() =
        runTest {
            val busy = TrackerHarness(this, checkpoints = InMemoryCheckpointStore(recoverable))
            busy.showUi()
            assertEquals(CommandResult.Ok, busy.tracker.startTrip())
            busy.runCurrent()
            assertFalse(busy.tracker.recoverInterruptedTrip())
            assertEquals(TripPhase.RUNNING, busy.state.trip.phase)
            assertTrue(busy.state.trip.tripId != recoverable.tripId)

            val preserved = assertNotNull(busy.repository.stored[recoverable.tripId])
            assertEquals(recoverable.stats, preserved.stats)
            assertEquals(recoverable.startedAtEpochMillis, preserved.startedAtEpochMillis)
            assertEquals(recoverable.savedAtEpochMillis, preserved.endedAtEpochMillis)
            assertEquals(busy.state.trip.tripId, busy.checkpoints.checkpoint?.tripId)
        }

    @Test
    fun `tapping Start while recovery is still reading the database also keeps the interrupted trip`() =
        runTest {
            val busy = TrackerHarness(this, checkpoints = InMemoryCheckpointStore(recoverable))
            busy.showUi()
            val slowDatabase = CompletableDeferred<Unit>()
            busy.repository.getGate = slowDatabase
            // Recovery has loaded the checkpoint and is still checking History when the user taps Start.
            val recovery = async { busy.tracker.recoverInterruptedTrip() }
            busy.runCurrent()
            assertEquals(CommandResult.Ok, busy.tracker.startTrip())
            busy.runCurrent()
            busy.repository.getGate = null
            slowDatabase.complete(Unit)
            busy.runCurrent()
            assertFalse(recovery.await())
            assertEquals(TripPhase.RUNNING, busy.state.trip.phase)
            assertEquals(recoverable.stats, assertNotNull(busy.repository.stored[recoverable.tripId]).stats)
        }

    @Test
    fun `a database error while checking History keeps the checkpoint for later`() = runTest {
        val store = InMemoryCheckpointStore(recoverable)
        val h = TrackerHarness(this, checkpoints = store)
        h.repository.getFailure = IOException("database locked")
        assertFalse(h.tracker.recoverInterruptedTrip())
        h.runCurrent()
        assertEquals(recoverable, store.checkpoint, "not restored, not deleted")
        // Next app start, the database works again: the trip comes back.
        h.repository.getFailure = null
        assertTrue(h.tracker.recoverInterruptedTrip())
        assertEquals(TripPhase.PAUSED, h.state.trip.phase)
    }

    @Test
    fun `a discarded trip whose checkpoint could not be deleted never comes back in the same session`() =
        runTest {
            val store = InMemoryCheckpointStore()
            val h = TrackerHarness(this, checkpoints = store)
            h.showUi()
            h.tracker.startTrip()
            h.drive(3, speedMps = 10f)
            val discardedId = h.state.trip.tripId
            store.failClears = true
            assertEquals(CommandResult.Ok, h.tracker.discardTrip())
            h.runCurrent()
            assertEquals(discardedId, store.checkpoint?.tripId, "the delete failed")
            assertFalse(h.tracker.recoverInterruptedTrip(), "but it is not recovered")
            assertEquals(TripPhase.IDLE, h.state.trip.phase)

            // A new trip replaces the slot without filing the discarded one into History.
            h.tracker.startTrip()
            h.runCurrent()
            assertTrue(h.repository.stored.isEmpty())
            assertEquals(h.state.trip.tripId, store.checkpoint?.tripId)

            // Once the store works again the pending delete goes through.
            store.failClears = false
            h.tracker.discardTrip()
            h.runCurrent()
            assertNull(store.checkpoint)
        }

    @Test
    fun `a checkpoint whose trip was already saved is cleared instead of restored`() = runTest {
        val store = InMemoryCheckpointStore(recoverable)
        val h = TrackerHarness(this, checkpoints = store)
        h.repository.saveTrip(Trip(recoverable.tripId, recoverable.startedAtEpochMillis, 1L, recoverable.stats))
        assertFalse(h.tracker.recoverInterruptedTrip())
        h.runCurrent()
        assertNull(store.checkpoint)
        assertEquals(TripPhase.IDLE, h.state.trip.phase)
    }

    @Test
    fun `a nonsensical checkpoint is cleared instead of restored`() = runTest {
        val store = InMemoryCheckpointStore(recoverable.copy(stats = TripStats(distanceMeters = -5.0)))
        val h = TrackerHarness(this, checkpoints = store)
        assertFalse(h.tracker.recoverInterruptedTrip())
        h.runCurrent()
        assertNull(store.checkpoint)
    }

    @Test
    fun `save failure keeps the trip paused with stats and checkpoint and allows a retry`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        h.drive(6, speedMps = 10f)
        val failure = IOException("database locked")
        h.repository.saveFailure = failure

        val failed = assertIs<FinishResult.SaveFailed>(h.tracker.finishTrip())
        assertSame(failure, failed.cause)
        h.runCurrent()
        assertEquals(TripPhase.PAUSED, h.state.trip.phase)
        val stats = h.state.trip.stats
        assertEquals(6_000L, stats.activeDurationMillis)
        assertEquals(TripPhase.PAUSED, h.checkpoints.checkpoint?.phase)
        assertEquals(stats, h.checkpoints.checkpoint?.stats)
        assertEquals(listOf("start"), h.foreground.calls, "service keeps running")

        h.advance(10_000L)
        assertEquals(stats, h.state.trip.stats, "frozen after the failed finish")

        h.repository.saveFailure = null
        val saved = assertIs<FinishResult.Saved>(h.tracker.finishTrip())
        assertEquals(stats, saved.trip.stats)
        h.runCurrent()
        assertEquals(TripPhase.FINISHED, h.state.trip.phase)
        assertNull(h.checkpoints.checkpoint)
        assertEquals(listOf("start", "stop"), h.foreground.calls)
        assertEquals(setOf(saved.trip.id), h.repository.stored.keys)
    }

    @Test
    fun `failing checkpoint store never breaks tracking`() = runTest {
        val h = TrackerHarness(this)
        h.checkpoints.failSaves = true
        h.showUi()
        h.tracker.startTrip()
        h.drive(12, speedMps = 10f)
        assertEquals(CommandResult.Ok, h.tracker.pauseTrip())
        assertNull(h.checkpoints.checkpoint)

        h.checkpoints.failSaves = false
        h.tracker.resumeTrip()
        h.runCurrent()
        assertEquals(TripPhase.RUNNING, h.checkpoints.checkpoint?.phase, "the writer survived the failures")
        assertIs<FinishResult.Saved>(h.tracker.finishTrip())
    }

    @Test
    fun `foreground service follows the trip lifecycle`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.tracker.startTrip()
        assertEquals(listOf("start"), h.foreground.calls)
        h.tracker.pauseTrip()
        assertEquals(listOf("start"), h.foreground.calls, "paused trips keep their notification")
        h.tracker.resumeTrip()
        assertEquals(listOf("start", "start"), h.foreground.calls)
        h.tracker.discardTrip()
        assertEquals(listOf("start", "start", "stop"), h.foreground.calls)
        h.tracker.startTrip()
        h.tracker.finishTrip()
        assertEquals(listOf("start", "start", "stop", "start", "stop"), h.foreground.calls)
        h.tracker.dismissFinished()
        assertEquals(5, h.foreground.calls.size)
    }

    @Test
    fun `a refused foreground service is reported until a retry succeeds or the trip ends`() = runTest {
        val h = TrackerHarness(this)
        h.showUi()
        h.foreground.startAccepted = false
        assertEquals(CommandResult.Ok, h.tracker.startTrip(), "the trip still records while the app is visible")
        assertTrue(h.state.backgroundRecordingUnavailable)

        h.tracker.pauseTrip()
        h.foreground.startAccepted = true
        h.tracker.resumeTrip()
        assertFalse(h.state.backgroundRecordingUnavailable, "resume retried and Android accepted")

        // The service itself is refused later (asynchronously).
        h.tracker.reportBackgroundRecordingUnavailable()
        assertTrue(h.state.backgroundRecordingUnavailable)
        h.tracker.discardTrip()
        assertFalse(h.state.backgroundRecordingUnavailable)

        h.tracker.reportBackgroundRecordingUnavailable()
        assertFalse(h.state.backgroundRecordingUnavailable, "no trip, nothing to warn about")
    }
}

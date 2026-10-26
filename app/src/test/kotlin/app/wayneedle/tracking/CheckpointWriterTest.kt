@file:OptIn(ExperimentalCoroutinesApi::class)

package app.wayneedle.tracking

import app.wayneedle.domain.model.TripCheckpoint
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckpointWriterTest {
    private fun checkpoint(durationMillis: Long) =
        TripCheckpoint("trip", TripPhase.RUNNING, 0L, TripStats(activeDurationMillis = durationMillis), durationMillis)

    @Test
    fun `operations reach the store in request order`() = runTest {
        val store = InMemoryCheckpointStore()
        val writer = CheckpointWriter(store, backgroundScope) { true }
        writer.save(checkpoint(1L), generation = 1L)
        writer.clear()
        writer.save(checkpoint(2L), generation = 1L)
        runCurrent()
        assertEquals(checkpoint(2L), store.checkpoint)
        assertEquals(listOf(checkpoint(1L), checkpoint(2L)), store.writes)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun `saves from an outdated generation are skipped`() = runTest {
        val store = InMemoryCheckpointStore()
        var generation = 1L
        val writer = CheckpointWriter(store, backgroundScope) { it == generation }
        writer.save(checkpoint(1L), generation = 1L)
        generation = 2L
        writer.save(checkpoint(2L), generation = 2L)
        runCurrent()
        assertEquals(listOf(checkpoint(2L)), store.writes)
    }

    @Test
    fun `load waits for queued writes`() = runTest {
        val store = InMemoryCheckpointStore()
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate
        val writer = CheckpointWriter(store, backgroundScope) { true }
        writer.save(checkpoint(5L), generation = 1L)
        val loaded = async { writer.load() }
        runCurrent()
        gate.complete(Unit)
        assertEquals(checkpoint(5L), loaded.await())
    }

    @Test
    fun `store failures do not stop the writer`() = runTest {
        val store = InMemoryCheckpointStore()
        store.failSaves = true
        val writer = CheckpointWriter(store, backgroundScope) { true }
        writer.save(checkpoint(1L), generation = 1L)
        runCurrent()
        store.failSaves = false
        writer.save(checkpoint(2L), generation = 1L)
        runCurrent()
        assertEquals(checkpoint(2L), store.checkpoint)
    }

    @Test
    fun `another trip's checkpoint is handed over before it is replaced`() = runTest {
        val interrupted = checkpoint(7L).copy(tripId = "interrupted")
        val store = InMemoryCheckpointStore(interrupted)
        val preserved = mutableListOf<TripCheckpoint>()
        val writer = CheckpointWriter(store, backgroundScope, preserveOrphan = { preserved += it }) { true }
        writer.save(checkpoint(1L), generation = 1L)
        writer.save(checkpoint(2L), generation = 1L)
        runCurrent()
        assertEquals(listOf(interrupted), preserved, "handed over exactly once")
        assertEquals(checkpoint(2L), store.checkpoint)
    }

    @Test
    fun `the same trip's checkpoint is simply replaced`() = runTest {
        val store = InMemoryCheckpointStore(checkpoint(1L))
        val preserved = mutableListOf<TripCheckpoint>()
        val writer = CheckpointWriter(store, backgroundScope, preserveOrphan = { preserved += it }) { true }
        writer.save(checkpoint(2L), generation = 1L)
        runCurrent()
        assertTrue(preserved.isEmpty())
    }

    @Test
    fun `a failed clear is retried and hides the dead checkpoint meanwhile`() = runTest {
        val store = InMemoryCheckpointStore(checkpoint(3L))
        store.failClears = true
        val preserved = mutableListOf<TripCheckpoint>()
        val writer = CheckpointWriter(store, backgroundScope, preserveOrphan = { preserved += it }) { true }
        writer.clear()
        runCurrent()
        assertEquals(checkpoint(3L), store.checkpoint, "the delete failed")
        assertNull(writer.load(), "but the cleared checkpoint is not offered for recovery")

        store.failClears = false
        assertNull(writer.load())
        assertNull(store.checkpoint, "retried before the next operation")
        assertTrue(preserved.isEmpty())
    }

    @Test
    fun `a cleared trip is not handed over when a new trip replaces it`() = runTest {
        val store = InMemoryCheckpointStore(checkpoint(3L))
        store.failClears = true
        val preserved = mutableListOf<TripCheckpoint>()
        val writer = CheckpointWriter(store, backgroundScope, preserveOrphan = { preserved += it }) { true }
        writer.clear()
        writer.save(checkpoint(1L).copy(tripId = "next"), generation = 1L)
        runCurrent()
        assertTrue(preserved.isEmpty(), "the old trip was finished or discarded, not interrupted")
        assertEquals("next", store.checkpoint?.tripId)
    }

    @Test
    fun `load returns null when the writer can no longer run`() = runTest {
        val deadScope = CoroutineScope(Job().apply { cancel() })
        val writer = CheckpointWriter(InMemoryCheckpointStore(checkpoint(9L)), deadScope) { true }
        assertNull(writer.load())
    }
}

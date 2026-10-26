package app.wayneedle.tracking

import app.wayneedle.domain.TripCheckpointStore
import app.wayneedle.domain.model.TripCheckpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Single writer for the checkpoint store. Every operation goes through one FIFO queue drained by one
 * coroutine, so store calls never overlap and run exactly in the order they were requested.
 *
 * This is what prevents a late checkpoint write from resurrecting a finished or discarded trip:
 * the `clear()` requested by finish/discard is queued behind any write already requested, so it always
 * runs last. In addition each save carries the trip generation it was taken in and is skipped when the
 * generation has moved on by the time it reaches the front of the queue (no pointless write of a dead
 * trip that a process death could otherwise leave behind).
 *
 * The store has a single slot, so the first save of a trip would replace whatever another trip left there.
 * That other checkpoint is handed to [preserveOrphan] first, never silently overwritten: it belongs to a
 * trip that was interrupted and not yet recovered (for example the user tapped Start before recovery
 * finished, or recovery could not read the database), and losing it would lose the user's trip.
 *
 * A failed clear is remembered and retried before every later operation; until it succeeds, [load]
 * reports no checkpoint, so a finished or discarded trip cannot come back in this process.
 *
 * Other store failures are swallowed: checkpointing is best effort and must never break tracking.
 */
internal class CheckpointWriter(
    private val store: TripCheckpointStore,
    scope: CoroutineScope,
    private val preserveOrphan: suspend (TripCheckpoint) -> Unit = {},
    private val isCurrentGeneration: (Long) -> Boolean,
) {
    private sealed interface Op {
        class Save(val checkpoint: TripCheckpoint, val generation: Long) : Op

        data object Clear : Op

        class Load(val result: CompletableDeferred<TripCheckpoint?>) : Op
    }

    private val queue = Channel<Op>(Channel.UNLIMITED)

    // Only touched by the drainer coroutine.
    private var clearPending = false
    private var slotTripId: String? = null
    private var slotKnown = false

    init {
        val drainer = scope.launch {
            for (op in queue) execute(op)
        }
        // Also runs if the scope was cancelled before the drainer started: never leave a load hanging.
        drainer.invokeOnCompletion {
            queue.close()
            while (true) {
                val pending = queue.tryReceive().getOrNull() ?: break
                if (pending is Op.Load) pending.result.complete(null)
            }
        }
    }

    /** Non-blocking; safe to call while holding the tracker lock. */
    fun save(checkpoint: TripCheckpoint, generation: Long) {
        queue.trySend(Op.Save(checkpoint, generation))
    }

    /** Non-blocking; safe to call while holding the tracker lock. */
    fun clear() {
        queue.trySend(Op.Clear)
    }

    /** Loads the checkpoint after every previously queued save/clear has been applied. */
    suspend fun load(): TripCheckpoint? {
        val result = CompletableDeferred<TripCheckpoint?>()
        if (queue.trySend(Op.Load(result)).isFailure) return null
        return result.await()
    }

    private suspend fun execute(op: Op) {
        try {
            when (op) {
                is Op.Save -> if (isCurrentGeneration(op.generation)) saveSlot(op.checkpoint)
                Op.Clear -> clearSlot()
                is Op.Load -> op.result.complete(loadSlot())
            }
        } catch (e: CancellationException) {
            // Only a cancellation of the writer itself ends the loop; one thrown by the store is a failure.
            currentCoroutineContext().ensureActive()
            if (op is Op.Load) {
                op.result.completeExceptionally(
                    IllegalStateException("Checkpoint load was cancelled", e),
                )
            }
        } catch (e: Exception) {
            if (op is Op.Load) op.result.completeExceptionally(e)
        }
    }

    private suspend fun saveSlot(checkpoint: TripCheckpoint) {
        retryPendingClear()
        if (!clearPending && (!slotKnown || slotTripId != checkpoint.tripId)) {
            val existing = try {
                store.load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (existing != null && existing.tripId != checkpoint.tripId) preserveOrphan(existing)
        }
        store.save(checkpoint)
        slotTripId = checkpoint.tripId
        slotKnown = true
        clearPending = false
    }

    private suspend fun clearSlot() {
        clearPending = true
        store.clear()
        clearPending = false
        slotTripId = null
        slotKnown = true
    }

    private suspend fun loadSlot(): TripCheckpoint? {
        retryPendingClear()
        if (clearPending) return null
        val checkpoint = store.load()
        slotTripId = checkpoint?.tripId
        slotKnown = true
        return checkpoint
    }

    private suspend fun retryPendingClear() {
        if (!clearPending) return
        try {
            clearSlot()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Still failing; [clearPending] stays set.
        }
    }
}

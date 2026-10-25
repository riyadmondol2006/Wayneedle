package app.wayneedle.domain

import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.Trip
import app.wayneedle.domain.model.TripPhase
import kotlinx.coroutines.flow.StateFlow

sealed interface CommandResult {
    data object Ok : CommandResult

    /** The command is not valid in the current phase; nothing changed. */
    data class InvalidTransition(val from: TripPhase) : CommandResult

    /** Precise location permission is missing; nothing changed. */
    data object PermissionRequired : CommandResult

    /** The GPS provider is switched off (start only); nothing changed. */
    data object LocationDisabled : CommandResult
}

sealed interface FinishResult {
    data class Saved(val trip: Trip) : FinishResult

    data class InvalidTransition(val from: TripPhase) : FinishResult

    /** Persisting failed. The trip stays PAUSED with all statistics and checkpoint intact so the user can retry. */
    data class SaveFailed(val cause: Throwable) : FinishResult
}

/**
 * The single application-facing entry point to GPS speed and trip recording. The UI, the foreground
 * service and the notification all talk to this interface and nothing else. Implementation lives in
 * `app.wayneedle.tracking`.
 *
 * State machine (anything else is rejected with InvalidTransition and changes nothing):
 *
 *  IDLE|FINISHED --startTrip--> RUNNING --pauseTrip--> PAUSED --resumeTrip--> RUNNING
 *  RUNNING|PAUSED --finishTrip--> FINISHED (saved) ; RUNNING|PAUSED --discardTrip--> IDLE
 *  FINISHED --dismissFinished--> IDLE
 *
 * Commands are thread-safe and may be called from any thread.
 */
interface TrackingController {
    val state: StateFlow<TrackingState>

    /** Creates a new trip, resets statistics, starts the foreground service. */
    fun startTrip(): CommandResult

    fun pauseTrip(): CommandResult

    /** Same trip; waits for a fresh reliable fix before counting distance again. */
    fun resumeTrip(): CommandResult

    /** Finalises statistics, saves the trip, stops the foreground service. */
    suspend fun finishTrip(): FinishResult

    /** Destructive: drops the in-progress trip and its checkpoint. The UI must confirm first. */
    fun discardTrip(): CommandResult

    /** Leaves the FINISHED summary state so another trip can be started. No-op unless FINISHED. */
    fun dismissFinished()

    /**
     * Tells the controller whether the app UI is on screen. While visible, live speed is shown even
     * when no trip is recording. While invisible and no trip is RUNNING, the GPS listener is released
     * to save battery.
     */
    fun setUiVisible(visible: Boolean)

    /** Re-reads permission and provider state; call from onResume and after a permission result. */
    fun refreshEnvironment()

    /**
     * Restores an interrupted trip from the checkpoint (as PAUSED, recovered = true). Never starts the
     * foreground service. Returns true if a trip was restored.
     */
    suspend fun recoverInterruptedTrip(): Boolean
}

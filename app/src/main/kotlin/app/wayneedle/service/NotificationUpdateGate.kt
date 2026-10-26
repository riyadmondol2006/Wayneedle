package app.wayneedle.service

import app.wayneedle.domain.model.TripPhase

/**
 * Decides when a refreshed [NotificationContent] may be posted: never when nothing visible changed,
 * at most once per [minIntervalMillis] (once per [screenOffIntervalMillis] while the screen is off, when
 * nobody can read the ticking duration and every post is wasted battery), [PHASE_SETTLE_MILLIS] after the trip
 * phase changed (soon, but not at once: Finish passes through PAUSED for a moment, and a "Paused" notification
 * posted in the same instant the service removes its notification could outlive the trip), and not at all after
 * the user swiped the notification away until the trip phase changes.
 * Pure and single-threaded; the caller supplies a monotonic clock and the screen state.
 */
internal class NotificationUpdateGate(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    private val screenOffIntervalMillis: Long = SCREEN_OFF_INTERVAL_MILLIS,
) {
    sealed interface Decision {
        data object Skip : Decision

        data object PostNow : Decision

        /** Too soon: wait [delayMillis], then ask again with the latest content. */
        data class PostAfter(val delayMillis: Long) : Decision
    }

    private var lastPosted: NotificationContent? = null
    private var lastPostedAtMillis = 0L
    private var dismissedInPhase: TripPhase? = null
    private var phaseChangeSeenAtMillis: Long? = null

    fun decide(content: NotificationContent, nowMillis: Long, screenOn: Boolean = true): Decision {
        val dismissed = dismissedInPhase
        if (dismissed != null) {
            if (content.phase == dismissed) return Decision.Skip
            dismissedInPhase = null
        }
        val previous = lastPosted
        if (previous != null) {
            if (previous.phase != content.phase) {
                val seenAt = phaseChangeSeenAtMillis ?: nowMillis.also { phaseChangeSeenAtMillis = it }
                val wait = PHASE_SETTLE_MILLIS - (nowMillis - seenAt)
                return if (wait > 0L) Decision.PostAfter(wait) else Decision.PostNow
            }
            // Back in the posted phase (a change undone within the settle time): the settle starts over next time.
            phaseChangeSeenAtMillis = null
            if (previous == content) return Decision.Skip
            val interval = if (screenOn) minIntervalMillis else screenOffIntervalMillis
            val remaining = interval - (nowMillis - lastPostedAtMillis)
            if (remaining > 0L) return Decision.PostAfter(remaining)
        }
        return Decision.PostNow
    }

    fun onPosted(content: NotificationContent, nowMillis: Long) {
        lastPosted = content
        lastPostedAtMillis = nowMillis
        phaseChangeSeenAtMillis = null
    }

    /**
     * Android 14+ lets users dismiss foreground-service notifications; the service keeps running.
     * Re-posting on every refresh would make the notification impossible to get rid of, so updates stop
     * until the phase changes (for example the user pauses or resumes in the app).
     */
    fun onDismissedByUser(currentPhase: TripPhase) {
        // The phase of the notification the user swiped, which can lag the trip by one update.
        dismissedInPhase = lastPosted?.phase ?: currentPhase
        lastPosted = null
    }

    /** Forgets everything; for a new trip in a service instance that outlived the previous one. */
    fun reset() {
        lastPosted = null
        lastPostedAtMillis = 0L
        dismissedInPhase = null
        phaseChangeSeenAtMillis = null
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MILLIS: Long = 1_000L
        const val SCREEN_OFF_INTERVAL_MILLIS: Long = 30_000L
        const val PHASE_SETTLE_MILLIS: Long = 250L
    }
}

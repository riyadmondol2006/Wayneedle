package app.wayneedle.service

import app.wayneedle.domain.model.TripPhase

/** Intent actions understood by [TrackingService]. Prefixed so they cannot collide with system actions. */
internal object TrackingServiceActions {
    const val START = "app.wayneedle.service.action.START"
    const val PAUSE = "app.wayneedle.service.action.PAUSE"
    const val RESUME = "app.wayneedle.service.action.RESUME"
    const val NOTIFICATION_DISMISSED = "app.wayneedle.service.action.NOTIFICATION_DISMISSED"
}

internal enum class ServiceCommand {
    START,
    PAUSE,
    RESUME,
    NOTIFICATION_DISMISSED,

    /** Null or unrecognised action. Never acted upon beyond re-checking whether the service is still needed. */
    UNKNOWN,
    ;

    companion object {
        fun fromAction(action: String?): ServiceCommand = when (action) {
            TrackingServiceActions.START -> START
            TrackingServiceActions.PAUSE -> PAUSE
            TrackingServiceActions.RESUME -> RESUME
            TrackingServiceActions.NOTIFICATION_DISMISSED -> NOTIFICATION_DISMISSED
            else -> UNKNOWN
        }

        /** The action carried by the notification button for [action]. */
        fun actionFor(action: NotificationAction): String = when (action) {
            NotificationAction.PAUSE -> TrackingServiceActions.PAUSE
            NotificationAction.RESUME -> TrackingServiceActions.RESUME
        }
    }
}

internal enum class ServiceDirective {
    /** A trip is recording or paused: stay a foreground service and keep the notification current. */
    KEEP,

    /** Just started and the trip state has not caught up yet: keep the placeholder, decide later. */
    WAIT_FOR_TRIP,

    /** No trip needs the service any more: remove the notification and stop. */
    STOP,
}

internal object TrackingServicePolicy {
    /** How long a freshly started service waits for the tracker to publish RUNNING/PAUSED. */
    const val START_GRACE_MILLIS: Long = 3_000L

    /** RUNNING and PAUSED keep the service alive; IDLE and FINISHED never do. */
    fun shouldRun(phase: TripPhase): Boolean = phase == TripPhase.RUNNING || phase == TripPhase.PAUSED

    /**
     * The launcher may be asked to start the service a moment before the tracker publishes RUNNING, so
     * IDLE/FINISHED is tolerated until a trip was seen or [START_GRACE_MILLIS] elapsed. After a trip was
     * seen, leaving RUNNING/PAUSED stops the service immediately.
     */
    fun directive(phase: TripPhase, hasSeenActiveTrip: Boolean, startGraceExpired: Boolean): ServiceDirective = when {
        shouldRun(phase) -> ServiceDirective.KEEP
        !hasSeenActiveTrip && !startGraceExpired -> ServiceDirective.WAIT_FOR_TRIP
        else -> ServiceDirective.STOP
    }
}

package app.wayneedle.service

import app.wayneedle.domain.model.TripPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingServicePolicyTest {
    @Test
    fun `running and paused keep the service alive`() {
        assertTrue(TrackingServicePolicy.shouldRun(TripPhase.RUNNING))
        assertTrue(TrackingServicePolicy.shouldRun(TripPhase.PAUSED))
        assertFalse(TrackingServicePolicy.shouldRun(TripPhase.IDLE))
        assertFalse(TrackingServicePolicy.shouldRun(TripPhase.FINISHED))
    }

    @Test
    fun `an active trip always keeps the service`() {
        for (phase in listOf(TripPhase.RUNNING, TripPhase.PAUSED)) {
            for (seen in listOf(true, false)) {
                for (expired in listOf(true, false)) {
                    assertEquals(ServiceDirective.KEEP, TrackingServicePolicy.directive(phase, seen, expired))
                }
            }
        }
    }

    @Test
    fun `a fresh start tolerates idle or finished until the grace period ends`() {
        for (phase in listOf(TripPhase.IDLE, TripPhase.FINISHED)) {
            assertEquals(ServiceDirective.WAIT_FOR_TRIP, TrackingServicePolicy.directive(phase, false, false))
            assertEquals(ServiceDirective.STOP, TrackingServicePolicy.directive(phase, false, true))
        }
    }

    @Test
    fun `leaving an active trip stops immediately regardless of the grace period`() {
        for (phase in listOf(TripPhase.IDLE, TripPhase.FINISHED)) {
            assertEquals(ServiceDirective.STOP, TrackingServicePolicy.directive(phase, true, false))
            assertEquals(ServiceDirective.STOP, TrackingServicePolicy.directive(phase, true, true))
        }
    }

    @Test
    fun `grace period is a few seconds`() {
        assertTrue(TrackingServicePolicy.START_GRACE_MILLIS in 1_000L..10_000L)
    }

    @Test
    fun `actions map to commands`() {
        assertEquals(ServiceCommand.START, ServiceCommand.fromAction(TrackingServiceActions.START))
        assertEquals(ServiceCommand.PAUSE, ServiceCommand.fromAction(TrackingServiceActions.PAUSE))
        assertEquals(ServiceCommand.RESUME, ServiceCommand.fromAction(TrackingServiceActions.RESUME))
        assertEquals(
            ServiceCommand.NOTIFICATION_DISMISSED,
            ServiceCommand.fromAction(TrackingServiceActions.NOTIFICATION_DISMISSED),
        )
    }

    @Test
    fun `null and foreign actions are unknown`() {
        assertEquals(ServiceCommand.UNKNOWN, ServiceCommand.fromAction(null))
        assertEquals(ServiceCommand.UNKNOWN, ServiceCommand.fromAction(""))
        assertEquals(ServiceCommand.UNKNOWN, ServiceCommand.fromAction("android.intent.action.MAIN"))
        assertEquals(ServiceCommand.UNKNOWN, ServiceCommand.fromAction(TrackingServiceActions.START.lowercase()))
    }

    @Test
    fun `notification buttons carry the matching command`() {
        assertEquals(
            ServiceCommand.PAUSE,
            ServiceCommand.fromAction(ServiceCommand.actionFor(NotificationAction.PAUSE)),
        )
        assertEquals(
            ServiceCommand.RESUME,
            ServiceCommand.fromAction(ServiceCommand.actionFor(NotificationAction.RESUME)),
        )
    }

    @Test
    fun `actions are unique and namespaced`() {
        val actions = listOf(
            TrackingServiceActions.START,
            TrackingServiceActions.PAUSE,
            TrackingServiceActions.RESUME,
            TrackingServiceActions.NOTIFICATION_DISMISSED,
        )
        assertEquals(actions.size, actions.toSet().size)
        assertTrue(actions.all { it.startsWith("app.wayneedle.") })
    }
}

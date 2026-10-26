package app.wayneedle.ui

import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepAwakePolicyTest {
    private val policy = KeepAwakePolicy()
    private val minute = 60_000_000_000L

    private fun state(speedMps: Float? = null, phase: TripPhase = TripPhase.IDLE) = TrackingState(
        liveSpeed = speedMps?.let { LiveSpeed.Available(it, it) } ?: LiveSpeed.Unavailable,
        trip = TripState(phase = phase, tripId = "t".takeIf { phase != TripPhase.IDLE }),
    )

    @Test
    fun `an idle phone lying still lets the screen time out`() {
        assertFalse(policy.update(state(speedMps = 0f), 0L))
        assertFalse(policy.update(state(speedMps = 0.4f), minute), "GPS jitter is not movement")
    }

    @Test
    fun `moving keeps the screen on, and for five minutes after stopping`() {
        assertTrue(policy.update(state(speedMps = 15f), 0L))
        assertTrue(policy.update(state(speedMps = 0f), 4 * minute), "a long red light")
        assertFalse(policy.update(state(speedMps = 0f), 6 * minute), "parked")
        assertTrue(policy.update(state(speedMps = 2f), 7 * minute), "walking again")
    }

    @Test
    fun `starting or resuming a trip keeps it on while setting off, a paused trip does not`() {
        assertTrue(policy.update(state(phase = TripPhase.RUNNING), 0L))
        assertTrue(policy.update(state(phase = TripPhase.RUNNING), 4 * minute))
        assertFalse(policy.update(state(phase = TripPhase.RUNNING), 6 * minute), "running but never moved")
        assertFalse(policy.update(state(phase = TripPhase.PAUSED), 7 * minute))
        assertTrue(policy.update(state(phase = TripPhase.RUNNING), 8 * minute), "resumed")
    }

    @Test
    fun `no GPS fix counts as not moving`() {
        assertFalse(policy.update(state(speedMps = null, phase = TripPhase.IDLE), 0L))
    }
}

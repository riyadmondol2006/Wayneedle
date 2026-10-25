package app.wayneedle.service

import app.wayneedle.domain.model.TripPhase
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationUpdateGateTest {
    private fun content(text: String, phase: TripPhase = TripPhase.RUNNING) = NotificationContent(
        phase = phase,
        title = "t",
        text = text,
        action = NotificationAction.PAUSE,
    )

    @Test
    fun `first content posts immediately`() {
        val gate = NotificationUpdateGate()
        assertEquals(NotificationUpdateGate.Decision.PostNow, gate.decide(content("a"), nowMillis = 0L))
    }

    @Test
    fun `unchanged content is skipped even long after the last post`() {
        val gate = NotificationUpdateGate()
        gate.onPosted(content("a"), nowMillis = 0L)
        assertEquals(NotificationUpdateGate.Decision.Skip, gate.decide(content("a"), nowMillis = 60_000L))
    }

    @Test
    fun `changes inside the interval wait for the remainder`() {
        val gate = NotificationUpdateGate(minIntervalMillis = 1_000L)
        gate.onPosted(content("a"), nowMillis = 10_000L)
        assertEquals(NotificationUpdateGate.Decision.PostAfter(600L), gate.decide(content("b"), nowMillis = 10_400L))
        assertEquals(NotificationUpdateGate.Decision.PostAfter(1L), gate.decide(content("b"), nowMillis = 10_999L))
    }

    @Test
    fun `changes at or after the interval post now`() {
        val gate = NotificationUpdateGate(minIntervalMillis = 1_000L)
        gate.onPosted(content("a"), nowMillis = 10_000L)
        assertEquals(NotificationUpdateGate.Decision.PostNow, gate.decide(content("b"), nowMillis = 11_000L))
        assertEquals(NotificationUpdateGate.Decision.PostNow, gate.decide(content("b"), nowMillis = 20_000L))
    }

    @Test
    fun `a change reverted while waiting is skipped`() {
        val gate = NotificationUpdateGate()
        gate.onPosted(content("a"), nowMillis = 0L)
        assertEquals(NotificationUpdateGate.Decision.PostAfter(500L), gate.decide(content("b"), nowMillis = 500L))
        assertEquals(NotificationUpdateGate.Decision.Skip, gate.decide(content("a"), nowMillis = 600L))
    }

    @Test
    fun `sustained updates never exceed one per second`() {
        val gate = NotificationUpdateGate(minIntervalMillis = 1_000L)
        var posts = 0
        var now = 0L
        var counter = 0
        while (now < 10_000L) {
            val next = content("v${counter++}")
            when (val decision = gate.decide(next, now)) {
                NotificationUpdateGate.Decision.Skip -> Unit
                NotificationUpdateGate.Decision.PostNow -> {
                    gate.onPosted(next, now)
                    posts++
                }
                is NotificationUpdateGate.Decision.PostAfter -> {
                    now += decision.delayMillis
                    gate.onPosted(next, now)
                    posts++
                }
            }
            now += 100L
        }
        assertEquals(true, posts <= 11, "posted $posts times in 10 s")
    }

    @Test
    fun `after the user dismisses the notification updates stop until the phase changes`() {
        val gate = NotificationUpdateGate()
        gate.onPosted(content("a"), nowMillis = 0L)
        gate.onDismissedByUser(TripPhase.RUNNING)
        assertEquals(NotificationUpdateGate.Decision.Skip, gate.decide(content("b"), nowMillis = 5_000L))
        assertEquals(NotificationUpdateGate.Decision.Skip, gate.decide(content("c"), nowMillis = 6_000L))
        assertEquals(NotificationUpdateGate.Decision.PostNow, gate.decide(content("d", TripPhase.PAUSED), nowMillis = 7_000L))
    }

    @Test
    fun `dismissal is forgotten once the phase changed`() {
        val gate = NotificationUpdateGate()
        gate.onDismissedByUser(TripPhase.RUNNING)
        assertEquals(NotificationUpdateGate.Decision.PostNow, gate.decide(content("p", TripPhase.PAUSED), nowMillis = 0L))
        gate.onPosted(content("p", TripPhase.PAUSED), nowMillis = 0L)
        // Back in RUNNING later: no longer suppressed.
        assertEquals(NotificationUpdateGate.Decision.PostNow, gate.decide(content("r"), nowMillis = 2_000L))
    }
}

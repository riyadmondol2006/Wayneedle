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
        assertEquals(
            NotificationUpdateGate.Decision.PostNow,
            gate.decide(content("d", TripPhase.PAUSED), nowMillis = 7_000L),
        )
    }

    @Test
    fun `dismissal is forgotten once the phase changed`() {
        val gate = NotificationUpdateGate()
        gate.onDismissedByUser(TripPhase.RUNNING)
        assertEquals(
            NotificationUpdateGate.Decision.PostNow,
            gate.decide(content("p", TripPhase.PAUSED), nowMillis = 0L),
        )
        gate.onPosted(content("p", TripPhase.PAUSED), nowMillis = 0L)
        // Back in RUNNING later: no longer suppressed (after the usual short settle of a phase change).
        assertEquals(NotificationUpdateGate.Decision.PostAfter(250L), gate.decide(content("r"), nowMillis = 2_000L))
        assertEquals(NotificationUpdateGate.Decision.PostNow, gate.decide(content("r"), nowMillis = 2_250L))
    }

    @Test
    fun `with the screen off updates wait 30 seconds, and resume at once when it comes on`() {
        val gate = NotificationUpdateGate()
        gate.onPosted(content("a"), nowMillis = 0L)
        assertEquals(
            NotificationUpdateGate.Decision.PostAfter(28_000L),
            gate.decide(content("b"), nowMillis = 2_000L, screenOn = false),
        )
        assertEquals(
            NotificationUpdateGate.Decision.PostNow,
            gate.decide(content("c"), nowMillis = 3_000L, screenOn = true),
        )
    }

    @Test
    fun `a phase change is shown after a short settle, even inside the interval or with the screen off`() {
        val gate = NotificationUpdateGate()
        gate.onPosted(content("a"), nowMillis = 0L)
        val paused = content("paused", TripPhase.PAUSED)
        assertEquals(NotificationUpdateGate.Decision.PostAfter(250L), gate.decide(paused, 10_000L, screenOn = false))
        assertEquals(NotificationUpdateGate.Decision.PostAfter(150L), gate.decide(paused, 10_100L, screenOn = false))
        assertEquals(NotificationUpdateGate.Decision.PostNow, gate.decide(paused, 10_250L, screenOn = false))
    }

    @Test
    fun `a phase change that is undone within the settle time posts nothing new`() {
        val gate = NotificationUpdateGate()
        gate.onPosted(content("a"), nowMillis = 0L)
        assertEquals(
            NotificationUpdateGate.Decision.PostAfter(250L),
            gate.decide(content("paused", TripPhase.PAUSED), nowMillis = 5_000L),
        )
        // Resumed again before the settle ended: back to the normal rules, and the settle starts over next time.
        assertEquals(NotificationUpdateGate.Decision.Skip, gate.decide(content("a"), nowMillis = 5_100L))
        assertEquals(
            NotificationUpdateGate.Decision.PostAfter(250L),
            gate.decide(content("paused", TripPhase.PAUSED), nowMillis = 6_000L),
        )
    }

    @Test
    fun `a swipe silences the phase of the swiped notification, not the newer trip phase`() {
        val gate = NotificationUpdateGate()
        gate.onPosted(content("recording"), nowMillis = 0L)
        // The user paused in the app and swiped the still "recording" notification before it updated.
        gate.onDismissedByUser(currentPhase = TripPhase.PAUSED)
        assertEquals(
            NotificationUpdateGate.Decision.PostNow,
            gate.decide(content("paused", TripPhase.PAUSED), nowMillis = 500L),
            "the paused notification must still appear (a swipe forgets the last post, so no settle applies)",
        )
    }

    @Test
    fun `reset forgets a dismissal from the previous trip`() {
        val gate = NotificationUpdateGate()
        gate.onPosted(content("a"), nowMillis = 0L)
        gate.onDismissedByUser(TripPhase.RUNNING)
        gate.reset()
        assertEquals(NotificationUpdateGate.Decision.PostNow, gate.decide(content("next trip"), nowMillis = 100L))
    }
}

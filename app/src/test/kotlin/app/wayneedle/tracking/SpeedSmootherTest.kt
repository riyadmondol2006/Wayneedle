package app.wayneedle.tracking

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedSmootherTest {
    private val smoother = SpeedSmoother()
    private var nowMillis = 0L

    /** Feeds a reading one second after the previous one (or [stepMillis] later). */
    private fun feed(speedMps: Float, stepMillis: Long = 1_000L): Float {
        nowMillis += stepMillis
        return smoother.update(speedMps, nowMillis * 1_000_000L)
    }

    @Test
    fun `first reading is shown unsmoothed`() {
        assertEquals(17.3f, feed(17.3f))
    }

    @Test
    fun `stopping shows exactly zero immediately`() {
        feed(20f)
        feed(19f)
        assertEquals(0f, feed(0f))
        assertEquals(0f, feed(0.3f))
    }

    @Test
    fun `large changes pass straight through`() {
        assertEquals(0f, feed(0f))
        assertEquals(10f, feed(10f))
        assertEquals(30f, feed(30f))
    }

    @Test
    fun `small changes settle within three fixes without overshoot`() {
        feed(10f)
        val outputs = List(3) { feed(11f) }
        outputs.zipWithNext().forEach { (a, b) -> assertTrue(b >= a, "monotonic: $outputs") }
        assertTrue(outputs.all { it in 10f..11f }, "no overshoot: $outputs")
        assertTrue(outputs.first() >= 10.5f, "responds at least half way on the first fix: $outputs")
        assertTrue(outputs.last() >= 10.9f, "settled after three fixes: $outputs")
    }

    @Test
    fun `steady acceleration is tracked with well under 1 m per s of lag`() {
        feed(0f)
        var raw = 0f
        repeat(10) {
            raw += 3f
            val shown = feed(raw)
            assertTrue(raw - shown in 0f..1f, "lag ${raw - shown} at raw $raw")
        }
    }

    @Test
    fun `jitter around a constant speed is damped`() {
        feed(10f)
        val shown = (1..20).map { i -> feed(if (i % 2 == 0) 10.4f else 9.6f) }
        val amplitude = shown.drop(4).let { it.max() - it.min() }
        assertTrue(amplitude < 0.8f * 0.6f, "output amplitude $amplitude vs input 0.8")
    }

    @Test
    fun `output always lies between the previous output and the new reading`() {
        val random = Random(42)
        var previous = feed(10f)
        repeat(500) {
            val raw = random.nextFloat() * 40f
            val shown = feed(raw, stepMillis = 200L + random.nextLong(1_500L))
            if (raw >= 0.5f) {
                assertTrue(shown in minOf(previous, raw)..maxOf(previous, raw), "prev=$previous raw=$raw shown=$shown")
            } else {
                assertEquals(0f, shown)
            }
            previous = shown
        }
    }

    @Test
    fun `after a gap the reading is shown unsmoothed`() {
        feed(10f)
        assertEquals(12f, feed(12f, stepMillis = 5_000L))
    }

    @Test
    fun `reset makes the next reading pass through`() {
        feed(10f)
        smoother.reset()
        assertEquals(11f, feed(11f))
    }

    @Test
    fun `weights scale with the fix interval`() {
        val fast = SpeedSmoother()
        val slow = SpeedSmoother()
        fast.update(10f, 0L)
        slow.update(10f, 0L)
        val halfSecond = fast.update(11f, 500_000_000L)
        val fullSecond = slow.update(11f, 1_000_000_000L)
        assertTrue(halfSecond in 10f..<fullSecond, "500 ms step $halfSecond, 1 s step $fullSecond")
    }
}

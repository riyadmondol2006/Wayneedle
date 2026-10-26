package app.wayneedle.ui.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeedAlertMonitorTest {
    private val limit = 30f
    private val monitor = SpeedAlertMonitor()

    private fun drive(vararg speeds: Float?): List<Boolean> = speeds.map {
        monitor.update(it, enabled = true, limitMps = limit)
    }

    @Test
    fun `crossing the limit alerts once and stays on above it`() {
        assertEquals(listOf(false, false, true, false, false), drive(28f, 30f, 30.5f, 33f, 31f))
        assertTrue(monitor.isOverLimit)
    }

    @Test
    fun `hovering around the limit does not buzz again and again`() {
        // GPS noise around 30 m/s: 30.2, 29.8, 30.3, 29.7, ...
        val buzzes = drive(30.2f, 29.8f, 30.3f, 29.7f, 30.1f, 29.9f, 30.4f).count { it }
        assertEquals(1, buzzes)
    }

    @Test
    fun `slowing down clearly re-arms the alert`() {
        drive(31f)
        assertFalse(drive(29.5f).single(), "29.5 is not yet clearly below 30")
        assertTrue(monitor.isOverLimit)
        drive(28.9f)
        assertFalse(monitor.isOverLimit)
        assertEquals(listOf(true), drive(31f), "the next crossing alerts again")
    }

    @Test
    fun `a GPS gap while speeding neither alerts again nor re-arms`() {
        drive(32f)
        assertEquals(listOf(false, false, false), drive(null, null, 32f))
        assertTrue(monitor.isOverLimit)
        assertFalse(monitor.update(Float.NaN, enabled = true, limitMps = limit))
    }

    @Test
    fun `nothing happens while the alert is off, and turning it off re-arms it`() {
        assertFalse(monitor.update(40f, enabled = false, limitMps = limit))
        assertFalse(monitor.isOverLimit)
        drive(40f)
        monitor.update(40f, enabled = false, limitMps = limit)
        assertFalse(monitor.isOverLimit)
        assertTrue(monitor.update(40f, enabled = true, limitMps = limit))
    }

    @Test
    fun `raising the limit above the current speed ends the alert`() {
        drive(31f)
        monitor.update(31f, enabled = true, limitMps = 40f)
        assertFalse(monitor.isOverLimit)
    }
}

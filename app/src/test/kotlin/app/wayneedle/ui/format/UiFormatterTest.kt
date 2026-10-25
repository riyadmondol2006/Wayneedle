package app.wayneedle.ui.format

import app.wayneedle.domain.model.SpeedUnit
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiFormatterTest {
    private val us = UiFormatter(Locale.US)
    private val de = UiFormatter(Locale.GERMANY)

    @Test
    fun `speed is a whole number in the chosen unit`() {
        assertEquals("54", us.speed(15f, SpeedUnit.KMH))
        assertEquals("60", us.speed(26.8224f, SpeedUnit.MPH))
        assertEquals("112", us.speed(31.1f, SpeedUnit.KMH))
    }

    @Test
    fun `a genuine standstill is zero`() {
        assertEquals("0", us.speed(0f, SpeedUnit.KMH))
        assertEquals("0", us.speed(0f, SpeedUnit.MPH))
    }

    @Test
    fun `tiny speeds round to zero rather than showing a fraction`() {
        assertEquals("0", us.speed(0.1f, SpeedUnit.KMH))
    }

    @Test
    fun `speed never prints NaN or Infinity`() {
        assertEquals(PLACEHOLDER, us.speed(Float.NaN, SpeedUnit.KMH))
        assertEquals(PLACEHOLDER, us.speed(Float.POSITIVE_INFINITY, SpeedUnit.KMH))
        assertEquals(PLACEHOLDER, us.speedInUnit(Double.NaN))
        assertEquals("0", us.speed(-3f, SpeedUnit.KMH))
        assertFalse(us.speed(Float.NaN, SpeedUnit.MPH).contains("NaN"))
    }

    @Test
    fun `distance has two decimals in km or miles`() {
        assertEquals("0.00", us.distance(0.0, SpeedUnit.KMH))
        assertEquals("12.35", us.distance(12_345.678, SpeedUnit.KMH))
        assertEquals("1.00", us.distance(1_609.344, SpeedUnit.MPH))
    }

    @Test
    fun `distance uses locale separators and grouping`() {
        assertEquals("1,234.57", us.distance(1_234_567.0, SpeedUnit.KMH))
        assertEquals("1.234,57", de.distance(1_234_567.0, SpeedUnit.KMH))
        assertEquals("0,50", de.distance(500.0, SpeedUnit.KMH))
    }

    @Test
    fun `distance guards against non finite input`() {
        assertEquals(PLACEHOLDER, us.distance(Double.NaN, SpeedUnit.KMH))
        assertEquals("0.00", us.distance(-5.0, SpeedUnit.KMH))
    }

    @Test
    fun `duration is H MM SS`() {
        assertEquals("0:00:00", us.duration(0))
        assertEquals("0:00:59", us.duration(59_999))
        assertEquals("0:01:00", us.duration(60_000))
        assertEquals("1:02:03", us.duration(3_723_000))
    }

    @Test
    fun `duration does not cap hours`() {
        assertEquals("99:59:59", us.duration(359_999_000))
        assertEquals("100:00:00", us.duration(360_000_000))
        assertEquals("1000:00:00", us.duration(3_600_000_000))
    }

    @Test
    fun `negative durations show zero`() {
        assertEquals("0:00:00", us.duration(-1))
        assertEquals("0:00:00", us.duration(Long.MIN_VALUE))
    }

    @Test
    fun `duration parts split hours minutes seconds`() {
        assertEquals(Triple(1L, 2L, 3L), us.durationParts(3_723_999))
        assertEquals(Triple(100L, 0L, 0L), us.durationParts(360_000_000))
    }

    @Test
    fun `accuracy is metres for km per hour and feet for mph`() {
        assertEquals("5", us.accuracy(5f, SpeedUnit.KMH))
        assertEquals("16", us.accuracy(5f, SpeedUnit.MPH))
        assertEquals(PLACEHOLDER, us.accuracy(Float.NaN, SpeedUnit.KMH))
    }

    @Test
    fun `dates and times are formatted for the locale and zone`() {
        val epoch = 1_792_929_600_000L // 2026-10-25 12:00 UTC
        val text = us.dateTime(epoch, ZoneOffset.UTC)
        assertTrue(text.contains("2026"), text)
        assertTrue(text.contains("12:00"), text)
        assertTrue(us.time(epoch, ZoneOffset.UTC).contains("12:00"))
        val berlin = de.dateTime(epoch, ZoneOffset.ofHours(1))
        assertTrue(berlin.contains("13:00"), berlin)
        assertTrue(de.date(epoch, ZoneOffset.UTC).contains("2026"))
    }

    @Test
    fun `pluralQuantityOf is one only for exactly one`() {
        assertEquals(1, pluralQuantityOf(1.0))
        assertEquals(1, pluralQuantityOf(1.001))
        assertEquals(2, pluralQuantityOf(1.5))
        assertEquals(2, pluralQuantityOf(0.0))
    }
}

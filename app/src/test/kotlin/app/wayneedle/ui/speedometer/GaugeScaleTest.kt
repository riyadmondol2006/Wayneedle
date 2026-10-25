package app.wayneedle.ui.speedometer

import app.wayneedle.domain.model.SpeedUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GaugeScaleTest {
    @Test
    fun `base scales are 0 to 240 km per hour and 0 to 150 mph`() {
        assertEquals(GaugeScale(SpeedUnit.KMH, max = 240, majorStep = 40, minorStep = 10), GaugeScale.base(SpeedUnit.KMH))
        assertEquals(GaugeScale(SpeedUnit.MPH, max = 150, majorStep = 25, minorStep = 5), GaugeScale.base(SpeedUnit.MPH))
    }

    @Test
    fun `a speed inside the range keeps the same instance`() {
        val base = GaugeScale.base(SpeedUnit.KMH)
        assertSame(base, base.extendedFor(SpeedUnit.KMH, 239f))
        assertSame(base, base.extendedFor(SpeedUnit.KMH, 240f))
    }

    @Test
    fun `exceeding the range grows it in whole major steps`() {
        val extended = GaugeScale.base(SpeedUnit.KMH).extendedFor(SpeedUnit.KMH, 255f)
        assertEquals(280, extended.max)
        assertEquals(0, extended.max % extended.majorStep)
        assertEquals(175, GaugeScale.base(SpeedUnit.MPH).extendedFor(SpeedUnit.MPH, 151f).max)
    }

    @Test
    fun `the range never shrinks when the speed drops again`() {
        val extended = GaugeScale.base(SpeedUnit.KMH).extendedFor(SpeedUnit.KMH, 255f)
        assertSame(extended, extended.extendedFor(SpeedUnit.KMH, 10f))
    }

    @Test
    fun `changing unit rebuilds the scale`() {
        val extended = GaugeScale.base(SpeedUnit.KMH).extendedFor(SpeedUnit.KMH, 300f)
        val mph = extended.extendedFor(SpeedUnit.MPH, 20f)
        assertEquals(GaugeScale.base(SpeedUnit.MPH), mph)
    }

    @Test
    fun `very high values widen the tick spacing so labels do not crowd`() {
        val scale = GaugeScale.forRequiredMax(SpeedUnit.KMH, 700f)
        assertTrue(scale.max >= 700)
        assertTrue(scale.max / scale.majorStep <= 8)
        assertEquals(0, scale.max % scale.majorStep)
        assertEquals(0, scale.majorStep % scale.minorStep)
    }

    @Test
    fun `non finite values fall back to the base scale`() {
        assertEquals(GaugeScale.base(SpeedUnit.KMH), GaugeScale.forRequiredMax(SpeedUnit.KMH, Float.NaN))
        assertEquals(GaugeScale.base(SpeedUnit.KMH), GaugeScale.forRequiredMax(SpeedUnit.KMH, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `fraction is clamped to the dial`() {
        val scale = GaugeScale.base(SpeedUnit.KMH)
        assertEquals(0f, scale.fractionOf(0f))
        assertEquals(0.5f, scale.fractionOf(120f))
        assertEquals(1f, scale.fractionOf(500f))
        assertEquals(0f, scale.fractionOf(-4f))
    }
}

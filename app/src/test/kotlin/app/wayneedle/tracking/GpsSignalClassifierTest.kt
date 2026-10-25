package app.wayneedle.tracking

import app.wayneedle.domain.model.GpsSignalQuality
import kotlin.test.Test
import kotlin.test.assertEquals

class GpsSignalClassifierTest {
    private val classifier = GpsSignalClassifier()

    private fun classify(
        accuracy: Float? = 4f,
        ageMillis: Long? = 500L,
        satellites: Int? = null,
        providerEnabled: Boolean = true,
    ) = classifier.classify(providerEnabled, accuracy, ageMillis, satellites)

    @Test
    fun `quality levels are ordered from none to excellent`() {
        assertEquals(
            listOf(
                GpsSignalQuality.NONE,
                GpsSignalQuality.POOR,
                GpsSignalQuality.FAIR,
                GpsSignalQuality.GOOD,
                GpsSignalQuality.EXCELLENT,
            ),
            GpsSignalQuality.entries.sorted(),
        )
    }

    @Test
    fun `no fix, stale fix or provider off is none`() {
        assertEquals(GpsSignalQuality.NONE, classify(ageMillis = null))
        assertEquals(GpsSignalQuality.NONE, classify(ageMillis = 4_001L))
        assertEquals(GpsSignalQuality.NONE, classify(providerEnabled = false))
    }

    @Test
    fun `accuracy maps to quality tiers`() {
        assertEquals(GpsSignalQuality.EXCELLENT, classify(accuracy = 3f))
        assertEquals(GpsSignalQuality.EXCELLENT, classify(accuracy = 5f))
        assertEquals(GpsSignalQuality.GOOD, classify(accuracy = 8f))
        assertEquals(GpsSignalQuality.FAIR, classify(accuracy = 20f))
        assertEquals(GpsSignalQuality.FAIR, classify(accuracy = 25f))
        assertEquals(GpsSignalQuality.POOR, classify(accuracy = 40f))
        assertEquals(GpsSignalQuality.POOR, classify(accuracy = null))
        assertEquals(GpsSignalQuality.POOR, classify(accuracy = Float.NaN))
    }

    @Test
    fun `few satellites cap the quality`() {
        assertEquals(GpsSignalQuality.POOR, classify(accuracy = 3f, satellites = 3))
        assertEquals(GpsSignalQuality.FAIR, classify(accuracy = 3f, satellites = 5))
        assertEquals(GpsSignalQuality.EXCELLENT, classify(accuracy = 3f, satellites = 12))
        assertEquals(GpsSignalQuality.POOR, classify(accuracy = 40f, satellites = 12))
    }

    @Test
    fun `an ageing fix is shown one level lower but never below poor`() {
        assertEquals(GpsSignalQuality.EXCELLENT, classify(accuracy = 3f, ageMillis = 2_000L))
        assertEquals(GpsSignalQuality.GOOD, classify(accuracy = 3f, ageMillis = 3_000L))
        assertEquals(GpsSignalQuality.POOR, classify(accuracy = 40f, ageMillis = 3_000L))
    }
}

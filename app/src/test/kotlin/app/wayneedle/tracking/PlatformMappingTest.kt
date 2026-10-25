package app.wayneedle.tracking

import app.wayneedle.domain.LocationSourceEvent
import app.wayneedle.domain.model.LocationSample
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformMappingTest {
    @Test
    fun `values flagged present are copied`() {
        val sample = locationSampleOf(
            latitude = 52.1,
            longitude = 4.3,
            hasAccuracy = true,
            accuracyM = 3.5f,
            hasSpeed = true,
            speedMps = 12.25f,
            hasSpeedAccuracy = true,
            speedAccuracyMps = 0.4f,
            elapsedRealtimeNanos = 123_456_789L,
            epochMillis = 1_700_000_000_123L,
        )
        assertEquals(LocationSample(52.1, 4.3, 3.5f, 12.25f, 0.4f, 123_456_789L, 1_700_000_000_123L), sample)
    }

    @Test
    fun `values flagged absent become null and never zero`() {
        val sample = locationSampleOf(
            latitude = 52.1,
            longitude = 4.3,
            hasAccuracy = false,
            accuracyM = 0f,
            hasSpeed = false,
            speedMps = 0f,
            hasSpeedAccuracy = false,
            speedAccuracyMps = 0f,
            elapsedRealtimeNanos = 1L,
            epochMillis = 2L,
        )
        assertEquals(null, sample.horizontalAccuracyM)
        assertEquals(null, sample.speedMps)
        assertEquals(null, sample.speedAccuracyMps)
    }

    @Test
    fun `satellite counts use the used-in-fix flags`() {
        assertEquals(LocationSourceEvent.Satellites(used = 3, inView = 5), satellitesOf(5) { it % 2 == 0 })
        assertEquals(LocationSourceEvent.Satellites(used = 0, inView = 0), satellitesOf(0) { true })
        assertEquals(LocationSourceEvent.Satellites(used = 0, inView = 0), satellitesOf(-1) { true })
    }
}

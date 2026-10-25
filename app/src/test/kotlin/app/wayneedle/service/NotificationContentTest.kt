package app.wayneedle.service

import app.wayneedle.domain.model.GpsStatus
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripState
import app.wayneedle.domain.model.TripStats
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NotificationContentTest {
    private val labels = NotificationLabels(
        titleApp = "Wayneedle",
        titleRecording = "Wayneedle – Recording",
        titlePaused = "Wayneedle – Paused",
        starting = "Starting…",
        waitingForGps = "Waiting for GPS…",
        gpsOff = "GPS is off",
        permissionNeeded = "Location permission needed",
        recordingFormat = "%1\$s · %2\$s · %3\$s",
        pausedFormat = "%1\$s · %2\$s",
        speedKmhFormat = "%1\$s km/h",
        speedMphFormat = "%1\$s mph",
        distanceKmFormat = "%1\$s km",
        distanceMiFormat = "%1\$s mi",
    )

    private fun state(
        phase: TripPhase = TripPhase.RUNNING,
        speedMps: Float? = 10f,
        distanceMeters: Double = 0.0,
        durationMillis: Long = 0L,
        permission: LocationPermissionState = LocationPermissionState.GRANTED_PRECISE,
        providerEnabled: Boolean = true,
    ) = TrackingState(
        permission = permission,
        gps = GpsStatus(providerEnabled = providerEnabled),
        liveSpeed = if (speedMps == null) LiveSpeed.Unavailable else LiveSpeed.Available(speedMps, speedMps),
        trip = TripState(
            phase = phase,
            stats = TripStats(distanceMeters = distanceMeters, activeDurationMillis = durationMillis),
        ),
    )

    private fun content(
        state: TrackingState,
        unit: SpeedUnit = SpeedUnit.KMH,
        locale: Locale = Locale.US,
    ): NotificationContent = assertNotNull(NotificationContent.from(state, unit, labels, locale))

    @Test
    fun `recording shows speed distance and duration in km`() {
        val result = content(state(speedMps = 10f, distanceMeters = 3_421.6, durationMillis = 754_000L))
        assertEquals("Wayneedle – Recording", result.title)
        assertEquals("36 km/h · 3.42 km · 12:34", result.text)
        assertEquals(NotificationAction.PAUSE, result.action)
        assertEquals(TripPhase.RUNNING, result.phase)
    }

    @Test
    fun `recording shows mph and miles`() {
        val result =
            content(state(speedMps = 26.8224f, distanceMeters = 1_609.344, durationMillis = 61_000L), SpeedUnit.MPH)
        assertEquals("60 mph · 1.00 mi · 1:01", result.text)
    }

    @Test
    fun `speed is rounded to the nearest whole unit`() {
        assertEquals("63 km/h", speedText(17.5f))
        assertEquals("63 km/h", speedText(17.49f))
        assertEquals("62 km/h", speedText(17.2f))
        assertEquals("0 km/h", speedText(0.1f))
        assertEquals("1 km/h", speedText(0.2f))
    }

    @Test
    fun `a genuine standstill reading shows zero not waiting`() {
        assertEquals("0 km/h", speedText(0f))
    }

    @Test
    fun `no fix says waiting for GPS and never invents a number`() {
        val result = content(state(speedMps = null, distanceMeters = 12.0, durationMillis = 5_000L))
        assertEquals("Waiting for GPS… · 0.01 km · 0:05", result.text)
    }

    @Test
    fun `provider off and missing permission are named instead of waiting`() {
        assertEquals("GPS is off · 0.00 km · 0:00", content(state(speedMps = null, providerEnabled = false)).text)
        assertEquals(
            "Location permission needed · 0.00 km · 0:00",
            content(state(speedMps = null, permission = LocationPermissionState.DENIED)).text,
        )
        assertEquals(
            "Location permission needed · 0.00 km · 0:00",
            content(state(speedMps = null, permission = LocationPermissionState.GRANTED_APPROXIMATE_ONLY)).text,
        )
    }

    @Test
    fun `a bad speed value is treated as no fix`() {
        assertEquals("Waiting for GPS… · 0.00 km · 0:00", content(state(speedMps = Float.NaN)).text)
        assertEquals("Waiting for GPS… · 0.00 km · 0:00", content(state(speedMps = -3f)).text)
        assertEquals("Waiting for GPS… · 0.00 km · 0:00", content(state(speedMps = Float.POSITIVE_INFINITY)).text)
    }

    @Test
    fun `paused omits speed and offers resume`() {
        val result =
            content(
                state(phase = TripPhase.PAUSED, speedMps = 12f, distanceMeters = 3_421.6, durationMillis = 754_000L),
            )
        assertEquals("Wayneedle – Paused", result.title)
        assertEquals("3.42 km · 12:34", result.text)
        assertEquals(NotificationAction.RESUME, result.action)
    }

    @Test
    fun `idle and finished have nothing to show`() {
        assertNull(NotificationContent.from(state(phase = TripPhase.IDLE), SpeedUnit.KMH, labels, Locale.US))
        assertNull(NotificationContent.from(state(phase = TripPhase.FINISHED), SpeedUnit.KMH, labels, Locale.US))
    }

    @Test
    fun `distance uses two decimals below ten and one decimal from ten`() {
        assertEquals("0.00 km", distanceText(0.0))
        assertEquals("0.01 km", distanceText(5.0))
        assertEquals("9.99 km", distanceText(9_994.0))
        assertEquals("10.0 km", distanceText(9_995.0))
        assertEquals("10.0 km", distanceText(10_000.0))
        assertEquals("12.3 km", distanceText(12_345.0))
        assertEquals("123.5 km", distanceText(123_456.0))
        assertEquals("1,234.6 km", distanceText(1_234_567.0))
    }

    @Test
    fun `distance in miles`() {
        assertEquals("0.62 mi", NotificationContent.formatDistance(1_000.0, SpeedUnit.MPH, labels, Locale.US))
        assertEquals("10.0 mi", NotificationContent.formatDistance(16_093.44, SpeedUnit.MPH, labels, Locale.US))
    }

    @Test
    fun `invalid distance shows zero`() {
        assertEquals("0.00 km", distanceText(-5.0))
        assertEquals("0.00 km", distanceText(Double.NaN))
    }

    @Test
    fun `duration formatting`() {
        fun d(millis: Long) = NotificationContent.formatDuration(millis, Locale.US)
        assertEquals("0:00", d(0))
        assertEquals("0:00", d(999))
        assertEquals("0:59", d(59_999))
        assertEquals("1:01", d(61_000))
        assertEquals("59:59", d(3_599_000))
        assertEquals("1:00:00", d(3_600_000))
        assertEquals("1:01:01", d(3_661_000))
        assertEquals("10:00:00", d(36_000_000))
        assertEquals("0:00", d(-5_000))
    }

    @Test
    fun `numbers follow the locale`() {
        val result =
            content(
                state(speedMps = 10f, distanceMeters = 3_421.6, durationMillis = 3_661_000L),
                locale = Locale.GERMANY,
            )
        assertEquals("36 km/h · 3,42 km · 1:01:01", result.text)
        assertEquals("12,3 km", NotificationContent.formatDistance(12_345.0, SpeedUnit.KMH, labels, Locale.GERMANY))
    }

    @Test
    fun `identical state yields equal content and any visible change yields different content`() {
        val base = content(state(speedMps = 10f, distanceMeters = 100.0, durationMillis = 10_000L))
        assertEquals(base, content(state(speedMps = 10f, distanceMeters = 100.0, durationMillis = 10_000L)))
        // Invisible noise: sub-rounding speed change, sub-second duration change, sub-centimetre distance change.
        assertEquals(base, content(state(speedMps = 10.01f, distanceMeters = 100.4, durationMillis = 10_400L)))
        assertNotEquals(base, content(state(speedMps = 11f, distanceMeters = 100.0, durationMillis = 10_000L)))
        assertNotEquals(base, content(state(speedMps = 10f, distanceMeters = 100.0, durationMillis = 11_000L)))
        assertNotEquals(
            base,
            content(state(phase = TripPhase.PAUSED, distanceMeters = 100.0, durationMillis = 10_000L)),
        )
    }

    @Test
    fun `placeholder is unit independent and matches the phase`() {
        val running = NotificationContent.placeholder(TripPhase.RUNNING, labels)
        assertEquals("Wayneedle – Recording", running.title)
        assertEquals("Starting…", running.text)
        assertEquals(NotificationAction.PAUSE, running.action)

        val paused = NotificationContent.placeholder(TripPhase.PAUSED, labels)
        assertEquals("Wayneedle – Paused", paused.title)
        assertEquals(NotificationAction.RESUME, paused.action)

        val idle = NotificationContent.placeholder(TripPhase.IDLE, labels)
        assertEquals("Wayneedle", idle.title)
        assertNull(idle.action)
        assertEquals(idle.title, NotificationContent.placeholder(TripPhase.FINISHED, labels).title)
    }

    private fun speedText(mps: Float): String = content(state(speedMps = mps)).text.substringBefore(" · ")

    private fun distanceText(meters: Double): String = NotificationContent.formatDistance(
        meters,
        SpeedUnit.KMH,
        labels,
        Locale.US,
    )
}

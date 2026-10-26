package app.wayneedle.service

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wayneedle.WayneedleApplication
import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.model.TripPhase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Background reliability on the real device: a trip keeps recording after the app is closed, the screen is
 * turned off and the phone is forced into deep Doze, the state a phone is in while in a pocket or a bag.
 *
 * Fixes are scripted through the platform's test provider (see `GpsPipelineInstrumentedTest`), so this proves
 * the delivery path (location manager, foreground service, tracker) keeps working under Doze; it says nothing
 * about satellite reception. The screen and Doze state are restored afterwards, even when the test fails.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundRecordingDozeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val locationManager: LocationManager = context.getSystemService(LocationManager::class.java)
    private val powerManager: PowerManager = context.getSystemService(PowerManager::class.java)
    private val container = (context.applicationContext as WayneedleApplication).container
    private val tracker = container.trackingController
    private val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private var mockProviderAdded = false
    private val startedTripIds = mutableSetOf<String>()
    private var activity: ActivityScenario<ComponentActivity>? = null
    private var latitude = 52.0

    @Before
    fun setUp() {
        uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        try {
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true,
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE,
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            mockProviderAdded = true
        } catch (_: SecurityException) {
            assumeTrue("Mock location not allowed (appops set ... android:mock_location allow)", false)
        }
        activity = ActivityScenario.launch(ComponentActivity::class.java)
        tracker.refreshEnvironment()
        tracker.setUiVisible(true)
        val phase = tracker.state.value.trip.phase
        assumeTrue(
            "A trip is in progress in the app on this device; finish or discard it before running this test",
            phase == TripPhase.IDLE || phase == TripPhase.FINISHED,
        )
    }

    @After
    fun tearDown() {
        shell("dumpsys deviceidle unforce")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        // Only ever discard the trip this test started.
        val trip = tracker.state.value.trip
        val ours = trip.tripId != null && trip.tripId in startedTripIds
        if (ours && (trip.phase == TripPhase.RUNNING || trip.phase == TripPhase.PAUSED)) tracker.discardTrip()
        if (ours) tracker.dismissFinished()
        tracker.setUiVisible(false)
        activity?.close()
        if (mockProviderAdded) {
            try {
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (_: IllegalArgumentException) {
                // Already gone.
            }
        }
    }

    @Test
    fun aTripKeepsRecordingWithTheScreenOffAndThePhoneInDeepDoze() {
        pushUntilListening()
        assertEquals(CommandResult.Ok, tracker.startTrip())
        tracker.state.value.trip.tripId?.let(startedTripIds::add)
        awaitCondition("the service is in the foreground") { TrackingServiceStatus.isInForeground }
        drive(steps = 3)

        // Pocket: the app is gone, the screen is off, Doze is forced at once.
        activity?.close()
        activity = null
        tracker.setUiVisible(false)
        shell("input keyevent KEYCODE_SLEEP")
        awaitCondition("the screen is off") { !powerManager.isInteractive }
        shell("dumpsys deviceidle force-idle")
        assertTrue("the phone is in deep Doze", shell("dumpsys deviceidle get deep").trim() == "IDLE")
        assertTrue("Android reports the device idle", powerManager.isDeviceIdleMode)

        val before = tracker.state.value.trip.stats
        val startedAt = SystemClock.elapsedRealtime()
        drive(steps = DOZE_STEPS)
        val after = tracker.state.value
        val elapsed = SystemClock.elapsedRealtime() - startedAt

        assertEquals("still recording", TripPhase.RUNNING, after.trip.phase)
        assertTrue("the foreground service survived", TrackingServiceStatus.isInForeground)
        assertFalse("no background refusal", after.backgroundRecordingUnavailable)
        assertTrue("still in Doze at the end", shell("dumpsys deviceidle get deep").trim() == "IDLE")
        val gained = after.trip.stats.distanceMeters - before.distanceMeters
        assertTrue(
            "GPS kept arriving in Doze: ${"%.1f".format(gained)} m of ${DOZE_STEPS * STEP_METERS} m driven",
            gained >= (DOZE_STEPS - 2) * STEP_METERS,
        )
        val counted = after.trip.stats.activeDurationMillis - before.activeDurationMillis
        assertTrue("active time kept counting ($counted ms in $elapsed ms)", counted >= elapsed - TIME_SLACK_MS)
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private fun shell(command: String): String =
        uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }

    private fun push(speed: Float, northMeters: Double = 0.0) {
        latitude += northMeters / METERS_PER_DEGREE_LATITUDE
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = this@BackgroundRecordingDozeTest.latitude
            longitude = 4.0
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            this.speed = speed
            speedAccuracyMetersPerSecond = 0.5f
        }
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix)
    }

    private fun drive(steps: Int) {
        repeat(steps) {
            push(speed = STEP_METERS.toFloat(), northMeters = STEP_METERS)
            SystemClock.sleep(FIX_INTERVAL_MS)
        }
    }

    private fun pushUntilListening() {
        val deadline = SystemClock.elapsedRealtime() + AWAIT_MS
        while (tracker.state.value.gps.lastFixAgeMillis == null) {
            check(SystemClock.elapsedRealtime() < deadline) { "No fix reached the tracker: ${tracker.state.value}" }
            push(speed = STEP_METERS.toFloat())
            SystemClock.sleep(PRIME_RETRY_MS)
        }
        SystemClock.sleep(SETTLE_MS)
    }

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + AWAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            runBlocking { kotlinx.coroutines.delay(POLL_MS) }
        }
        error("Timed out waiting for: $what. Last state: ${tracker.state.value}")
    }

    private companion object {
        const val METERS_PER_DEGREE_LATITUDE = 111_194.93
        const val STEP_METERS = 10.0
        const val DOZE_STEPS = 30
        const val FIX_INTERVAL_MS = 1_000L
        const val PRIME_RETRY_MS = 300L
        const val SETTLE_MS = 1_300L
        const val AWAIT_MS = 10_000L
        const val POLL_MS = 150L
        const val TIME_SLACK_MS = 2_000L
    }
}

package app.wayneedle.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.wayneedle.domain.LocationPermissionChecker
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.permissions.AndroidLocationPermissionChecker
import app.wayneedle.permissions.mapLocationPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run only on the verified Pixel 6 Pro, by the lead. Checks the platform-facing pieces that JVM tests cannot:
 * the real notification channel, the built notification and the launcher's exception handling.
 */
@RunWith(AndroidJUnit4::class)
class TrackingNotificationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = TrackingNotification(context)

    @Test
    fun channelIsLowImportanceAndSilent() {
        notifications.ensureChannel()
        notifications.ensureChannel()
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(TrackingNotificationIds.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.shouldShowLights())
        assertFalse(channel.canShowBadge())
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
    }

    @Test
    fun recordingNotificationIsOngoingAndOffersPause() {
        val notification = notifications.build(NotificationContent.placeholder(TripPhase.RUNNING, notifications.labels))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.CATEGORY_SERVICE, notification.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(TrackingNotificationIds.CHANNEL_ID, notification.channelId)
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.deleteIntent)
        assertEquals(1, notification.actions.size)
        assertEquals(
            context.getString(app.wayneedle.R.string.service_action_pause),
            notification.actions[0].title.toString(),
        )
    }

    @Test
    fun pausedNotificationOffersResume() {
        val notification = notifications.build(NotificationContent.placeholder(TripPhase.PAUSED, notifications.labels))
        assertEquals(1, notification.actions.size)
        assertEquals(
            context.getString(app.wayneedle.R.string.service_action_resume),
            notification.actions[0].title.toString(),
        )
    }

    @Test
    fun idleFallbackNotificationHasNoAction() {
        val notification = notifications.build(NotificationContent.placeholder(TripPhase.IDLE, notifications.labels))
        assertTrue(notification.actions == null || notification.actions.isEmpty())
    }

    @Test
    fun launcherNeverThrowsAndReportsTheOutcome() {
        val launcher =
            ServiceTripForegroundLauncher(
                context,
                LocationPermissionChecker {
                    LocationPermissionState.GRANTED_PRECISE
                },
            )
        val result = launcher.launch()
        // Started when the platform accepted the request; a Failed result (not a crash) otherwise.
        assertTrue(result is ForegroundStartResult.Started || result is ForegroundStartResult.Failed)
        launcher.stop()
        launcher.stop()
    }

    @Test
    fun permissionCheckerAgreesWithTheGrants() {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val expected = mapLocationPermission(
            fineGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            coarseGranted = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        assertEquals(expected, AndroidLocationPermissionChecker(context).current())
    }
}

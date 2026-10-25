package app.wayneedle.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.wayneedle.R
import app.wayneedle.ui.MainActivity

/** Identifiers shared with the permission/status helpers so the UI can inspect the same channel. */
object TrackingNotificationIds {
    const val CHANNEL_ID: String = "wayneedle_trip_recording"
    const val NOTIFICATION_ID: Int = 4101
}

/**
 * Thin Android adapter: creates the notification channel and turns a pure [NotificationContent] into a
 * [Notification]. All text decisions live in [NotificationContent].
 */
internal class TrackingNotification(private val context: Context) {
    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private val contentIntent: PendingIntent by lazy {
        // Launcher-style intent: resumes the existing task, and onNewIntent (MainActivity is singleTask)
        // instead of stacking a second instance. Attached directly, so there is no notification trampoline.
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        PendingIntent.getActivity(context, REQUEST_OPEN, intent, PENDING_FLAGS)
    }

    private val deleteIntent: PendingIntent by lazy { servicePendingIntent(REQUEST_DISMISSED, TrackingServiceActions.NOTIFICATION_DISMISSED) }

    private val pauseIntent: PendingIntent by lazy {
        servicePendingIntent(REQUEST_PAUSE, ServiceCommand.actionFor(NotificationAction.PAUSE))
    }

    private val resumeIntent: PendingIntent by lazy {
        servicePendingIntent(REQUEST_RESUME, ServiceCommand.actionFor(NotificationAction.RESUME))
    }

    val labels: NotificationLabels
        get() = NotificationLabels(
            titleApp = context.getString(R.string.service_title_app),
            titleRecording = context.getString(R.string.service_title_recording),
            titlePaused = context.getString(R.string.service_title_paused),
            starting = context.getString(R.string.service_text_starting),
            waitingForGps = context.getString(R.string.service_text_waiting_for_gps),
            gpsOff = context.getString(R.string.service_text_gps_off),
            permissionNeeded = context.getString(R.string.service_text_permission_needed),
            recordingFormat = context.getString(R.string.service_text_recording),
            pausedFormat = context.getString(R.string.service_text_paused),
            speedKmhFormat = context.getString(R.string.service_speed_kmh),
            speedMphFormat = context.getString(R.string.service_speed_mph),
            distanceKmFormat = context.getString(R.string.service_distance_km),
            distanceMiFormat = context.getString(R.string.service_distance_mi),
        )

    /** Idempotent. Low importance: no sound, vibration, lights or launcher badge. */
    fun ensureChannel() {
        val channel = NotificationChannel(
            TrackingNotificationIds.CHANNEL_ID,
            context.getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.service_channel_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun build(content: NotificationContent): Notification {
        val builder = NotificationCompat.Builder(context, TrackingNotificationIds.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (content.action) {
            NotificationAction.PAUSE -> builder.addAction(
                R.drawable.ic_notification_pause,
                context.getString(R.string.service_action_pause),
                pauseIntent,
            )
            NotificationAction.RESUME -> builder.addAction(
                R.drawable.ic_notification_resume,
                context.getString(R.string.service_action_resume),
                resumeIntent,
            )
            null -> Unit
        }
        return builder.build()
    }

    fun post(content: NotificationContent) {
        notificationManager.notify(TrackingNotificationIds.NOTIFICATION_ID, build(content))
    }

    /** `PendingIntent.getService`, so no extra manifest receiver is needed. Explicit and immutable. */
    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(context, requestCode, TrackingService.commandIntent(context, action), PENDING_FLAGS)

    private companion object {
        const val REQUEST_OPEN = 1
        const val REQUEST_PAUSE = 2
        const val REQUEST_RESUME = 3
        const val REQUEST_DISMISSED = 4
        const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}

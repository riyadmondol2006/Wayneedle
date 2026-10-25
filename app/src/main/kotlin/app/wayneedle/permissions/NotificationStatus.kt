package app.wayneedle.permissions

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.wayneedle.service.TrackingNotificationIds

/** Whether the ongoing trip notification can currently be seen. The trip records either way. */
enum class TripNotificationVisibility {
    VISIBLE,

    /** Notifications are off for the app (Android 13+: `POST_NOTIFICATIONS` not granted, or disabled in settings). */
    APP_BLOCKED,

    /** The user switched the trip-recording channel off. */
    CHANNEL_BLOCKED,
}

/**
 * Notification state for the UI or logs. A foreground service keeps running when notifications are
 * blocked (Android 13+ still lists it in the Foreground Services Task Manager), so this is advice to the
 * user, never a reason to refuse to record.
 */
class NotificationStatus(context: Context) {
    private val appContext: Context = context.applicationContext

    fun areNotificationsEnabled(): Boolean = NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    fun tripNotificationVisibility(): TripNotificationVisibility {
        val manager = NotificationManagerCompat.from(appContext)
        // Null until the service created the channel for the first time; a missing channel is not "blocked".
        val channelImportance = manager.getNotificationChannel(TrackingNotificationIds.CHANNEL_ID)?.importance
        return classifyNotificationVisibility(manager.areNotificationsEnabled(), channelImportance)
    }
}

/** Pure mapping; [channelImportance] is null when the channel does not exist yet. */
fun classifyNotificationVisibility(appEnabled: Boolean, channelImportance: Int?): TripNotificationVisibility = when {
    !appEnabled -> TripNotificationVisibility.APP_BLOCKED
    channelImportance == NotificationManager.IMPORTANCE_NONE -> TripNotificationVisibility.CHANNEL_BLOCKED
    else -> TripNotificationVisibility.VISIBLE
}

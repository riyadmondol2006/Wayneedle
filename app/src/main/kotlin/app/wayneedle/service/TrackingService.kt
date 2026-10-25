package app.wayneedle.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TrackingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

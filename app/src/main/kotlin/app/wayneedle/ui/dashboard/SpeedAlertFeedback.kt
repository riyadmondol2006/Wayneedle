package app.wayneedle.ui.dashboard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Two short pulses: noticeable in a pocket or a car mount, too short to be mistaken for a call. */
private val SPEED_ALERT_PATTERN = longArrayOf(0L, 120L, 90L, 120L)
private const val NO_REPEAT = -1

/** Vibrates once for a speed alert. Does nothing on a device without a vibrator. */
internal fun Context.vibrateSpeedAlert() {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        getSystemService(Vibrator::class.java)
    }
    if (vibrator == null || !vibrator.hasVibrator()) return
    vibrator.vibrate(VibrationEffect.createWaveform(SPEED_ALERT_PATTERN, NO_REPEAT))
}

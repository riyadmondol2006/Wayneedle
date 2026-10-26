package app.wayneedle.alert

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Two short pulses: noticeable in a pocket or a car mount, too short to be mistaken for a call. */
private val SPEED_ALERT_PATTERN = longArrayOf(0L, 120L, 90L, 120L)
private const val NO_REPEAT = -1
private const val BEEP_VOLUME = 80
private const val BEEP_MILLIS = 350
private const val RELEASE_AFTER_MILLIS = 800L

/**
 * One speed alert: a short vibration and, when [sound] is on, a short beep on the notification stream (so it
 * follows the phone's ring mode: silent stays silent). Does nothing on hardware without a vibrator or speaker.
 */
internal fun Context.signalSpeedAlert(sound: Boolean) {
    vibrateSpeedAlert()
    if (sound) beepSpeedAlert()
}

private fun Context.vibrateSpeedAlert() {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        getSystemService(Vibrator::class.java)
    }
    if (vibrator == null || !vibrator.hasVibrator()) return
    vibrator.vibrate(VibrationEffect.createWaveform(SPEED_ALERT_PATTERN, NO_REPEAT))
}

private fun beepSpeedAlert() {
    val tone = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME)
    } catch (_: RuntimeException) {
        // No audio output available right now (for example taken by a call).
        return
    }
    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_MILLIS)
    Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, RELEASE_AFTER_MILLIS)
}

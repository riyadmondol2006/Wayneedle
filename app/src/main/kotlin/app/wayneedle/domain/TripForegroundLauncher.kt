package app.wayneedle.domain

/**
 * Bridge from the tracking engine to the Android foreground service, so the engine has no
 * Android dependency. Implemented by the service package.
 *
 * Rules: [start] is idempotent and is only called as a direct result of a user action while the app
 * is visible (Android 12+/14+ forbid starting a location foreground service from the background).
 * [stop] is idempotent.
 */
interface TripForegroundLauncher {
    fun start()

    fun stop()
}

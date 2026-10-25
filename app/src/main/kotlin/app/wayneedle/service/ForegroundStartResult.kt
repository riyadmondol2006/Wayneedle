package app.wayneedle.service

import java.util.concurrent.atomic.AtomicReference

/** Why entering (or requesting) the foreground state failed, without exposing platform exception types. */
internal enum class ForegroundFailure {
    /** Background-start limits (Android 12+): thrown as an `IllegalStateException` subclass. */
    START_NOT_ALLOWED,

    /** Missing manifest/runtime permission or while-in-use restriction (Android 14+): `SecurityException`. */
    PERMISSION,
    OTHER,
    ;

    companion object {
        fun classify(error: Throwable): ForegroundFailure = when (error) {
            is SecurityException -> PERMISSION
            is IllegalStateException -> START_NOT_ALLOWED
            else -> OTHER
        }
    }
}

/** Outcome of asking for, or entering, the foreground state. Logged; never thrown. */
internal sealed interface ForegroundStartResult {
    data object Started : ForegroundStartResult

    /** Not even attempted because it cannot succeed (for example no location permission at all). */
    data object SkippedNoLocationPermission : ForegroundStartResult

    data class Failed(val failure: ForegroundFailure, val cause: Throwable) : ForegroundStartResult
}

/**
 * Last foreground-start result of this process, so callers that started the service asynchronously can
 * find out afterwards whether it actually became a foreground service. Null until the first attempt.
 */
object TrackingServiceStatus {
    private val last = AtomicReference<ForegroundStartResult?>(null)

    internal fun record(result: ForegroundStartResult) {
        last.set(result)
    }

    /** True when the most recent attempt succeeded, false when it failed, null when nothing was attempted. */
    val lastStartSucceeded: Boolean?
        get() = last.get()?.let { it is ForegroundStartResult.Started }

    internal fun lastResult(): ForegroundStartResult? = last.get()
}

package app.wayneedle.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/** True when the user turned system animations off (animator duration scale 0); motion must then be skipped. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
internal fun rememberReduceMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.contentResolver
    var reduce by remember(resolver) { mutableStateOf(readReduceMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = readReduceMotion(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduce
}

private fun readReduceMotion(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * The Material 3 Expressive motion scheme: springs, not durations. The numbers are the published
 * expressive motion tokens (spatial springs may overshoot slightly, effects springs never do).
 *
 * They are reproduced here because `MotionScheme.expressive()` and `MaterialTheme.motionScheme` are
 * `internal` in the stable material3 1.4.0 artifact and only become public in its 1.5 pre-releases, which
 * this project forbids. Every animation in the app asks this object, so switching to the library scheme
 * later is a one-file change. Each call returns `snap()` when the user turned animations off.
 */
object WayneedleMotion {
    private const val SPATIAL_DAMPING = 0.8f
    private const val SPATIAL_STIFFNESS = 380f
    private const val FAST_SPATIAL_DAMPING = 0.6f
    private const val FAST_SPATIAL_STIFFNESS = 800f
    private const val EFFECTS_STIFFNESS = 1600f
    private const val FAST_EFFECTS_STIFFNESS = 3800f

    /** Movement, size and shape changes. */
    @Composable
    fun <T> spatial(): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) snap() else spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS)

    /** Quick spatial feedback such as a button changing shape while pressed. */
    @Composable
    fun <T> fastSpatial(): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) snap() else spring(FAST_SPATIAL_DAMPING, FAST_SPATIAL_STIFFNESS)

    /** Colour and alpha changes. */
    @Composable
    fun <T> effects(): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) snap() else spring(1f, EFFECTS_STIFFNESS)

    /** Very quick effects such as a ripple-like highlight. */
    @Composable
    fun <T> fastEffects(): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) snap() else spring(1f, FAST_EFFECTS_STIFFNESS)
}

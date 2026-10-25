package app.wayneedle.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colours that must keep their meaning (good = green, caution = amber) even when Material You
 * recolours the rest of the UI, so they are not part of the dynamic [androidx.compose.material3.ColorScheme].
 */
@Immutable
data class ExtraColors(
    /** Strong GPS signal; contrast-safe as text and as a graphic on every surface role. */
    val success: Color,
    /** Fair/poor GPS signal and the "paused" accent. */
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LightExtraColors = ExtraColors(
    success = Color(0xFF1B6E2D),
    warning = Color(0xFF7A5900),
    warningContainer = Color(0xFFFFDF9E),
    onWarningContainer = Color(0xFF261A00),
)

internal val DarkExtraColors = ExtraColors(
    success = Color(0xFF8BD99A),
    warning = Color(0xFFFFD27A),
    warningContainer = Color(0xFF5C4300),
    onWarningContainer = Color(0xFFFFDF9E),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtraColors }

package app.wayneedle.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import app.wayneedle.domain.model.ThemeMode

/** Whether [ThemeMode] resolves to a dark theme for the current system setting. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Material You dynamic colour needs Android 12 (API 31). */
val DynamicColorSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Wayneedle's Material 3 Expressive theme: Material You dynamic colour (or the brand palette), generous
 * Expressive corner shapes, heavier type, and the Expressive spring motion scheme via [WayneedleMotion].
 *
 * Experimental API note: `MaterialExpressiveTheme` is `internal` in material3 1.4.0 stable (it is public
 * only in 1.5 pre-releases), so this uses the stable `MaterialTheme` and supplies the expressive shapes,
 * typography and motion itself. No Expressive `@OptIn` is needed anywhere in the app.
 */
@Composable
fun WayneedleTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val darkTheme = themeMode.isDark()
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> BrandDarkColorScheme

        else -> BrandLightColorScheme
    }
    val reduceMotion = rememberReduceMotion()
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = WayneedleShapes,
        typography = WayneedleTypography,
    ) {
        CompositionLocalProvider(
            LocalReduceMotion provides reduceMotion,
            LocalExtraColors provides if (darkTheme) DarkExtraColors else LightExtraColors,
            content = content,
        )
    }
}

package app.wayneedle.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import app.wayneedle.domain.model.ThemeMode
import app.wayneedle.ui.theme.WayneedleTheme

internal val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

internal fun string(@StringRes id: Int, vararg args: Any): String = targetContext.getString(id, *args)

internal fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): String =
    targetContext.resources.getQuantityString(id, quantity, *args)

/** Hosts [content] in the Wayneedle theme (brand palette, so results do not depend on the wallpaper). */
internal fun ComposeContentTestRule.setWayneedleContent(
    darkTheme: Boolean = false,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            WayneedleTheme(themeMode = if (darkTheme) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

/** A node with [text] inside a dialog; dialog buttons often repeat a label that is also on screen behind them. */
internal fun ComposeContentTestRule.onDialogNodeWithText(text: String) =
    onNode(hasText(text) and hasAnyAncestor(isDialog()))

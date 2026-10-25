package app.wayneedle.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/** A [UiFormatter] for the current UI locale; recreated when the locale changes. */
@Composable
fun rememberUiFormatter(): UiFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { UiFormatter(locale) }
}

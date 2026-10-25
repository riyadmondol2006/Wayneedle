package app.wayneedle.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource

/** One top-level destination of the navigation bar/rail. [route] doubles as the stable identity. */
@Immutable
data class NavigationItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

/**
 * The compact-width navigation bar.
 *
 * Opt-in: `ShortNavigationBar` is the Expressive replacement for `NavigationBar` and is
 * `@ExperimentalMaterial3ExpressiveApi` in material3 1.4.0. The opt-in is local to this function.
 */
@Composable
fun WayneedleNavigationBar(
    items: List<NavigationItem>,
    selectedRoute: String?,
    onSelect: (NavigationItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    ShortNavigationBar(modifier = modifier) {
        items.forEach { item ->
            ShortNavigationBarItem(
                selected = item.route == selectedRoute,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.labelRes)) },
            )
        }
    }
}

/** The navigation rail used when the window is wide (landscape phones, tablets, unfolded foldables). */
@Composable
fun WayneedleNavigationRail(
    items: List<NavigationItem>,
    selectedRoute: String?,
    onSelect: (NavigationItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(modifier = modifier) {
        Spacer(Modifier.weight(1f))
        items.forEach { item ->
            NavigationRailItem(
                selected = item.route == selectedRoute,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.labelRes)) },
                alwaysShowLabel = true,
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

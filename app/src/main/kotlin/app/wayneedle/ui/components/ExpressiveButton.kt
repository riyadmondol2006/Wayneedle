package app.wayneedle.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wayneedle.ui.theme.WayneedleMotion

/** Colour roles of an [ExpressiveButton]. */
enum class ExpressiveButtonStyle {
    /** The one primary action of a screen. */
    FILLED,
    TONAL,

    /** Positive completion (e.g. finishing a trip). */
    COMPLETE,
    TEXT,
    DESTRUCTIVE_TEXT,
}

private val PressedCorner = 16.dp

/** A corner that is fully round (a pill) at [progress] 0 and shrinks to [PressedCorner] at 1. */
private class MorphingCorner(private val progress: Float) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float {
        val pill = shapeSize.height / 2f
        val squared = with(density) { PressedCorner.toPx() }
        return pill + (minOf(pill, squared) - pill) * progress
    }
}
private const val STACK_FONT_SCALE = 1.4f

/**
 * A button that is a pill at rest and squares off while pressed, with the corner change driven by the
 * Expressive spatial spring. This is the shape-morph behaviour of Expressive buttons, built on the stable
 * [Button] because `ButtonDefaults.shapes()` is not in material3 1.4.0.
 *
 * Pass the same [interactionSource] to observe the press from outside (see [ExpressiveButtonRow]).
 */
@Composable
fun ExpressiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ExpressiveButtonStyle = ExpressiveButtonStyle.FILLED,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    minHeight: Dp = 56.dp,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val morph by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = WayneedleMotion.fastSpatial(),
        label = "button shape",
    )
    val shape = remember(morph) { RoundedCornerShape(MorphingCorner(morph)) }
    val scheme = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
    val buttonModifier = modifier.heightIn(min = minHeight)
    val padding = ButtonDefaults.ContentPadding
    when (style) {
        ExpressiveButtonStyle.FILLED -> Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            interactionSource = source,
            content = { content() },
        )
        ExpressiveButtonStyle.TONAL -> FilledTonalButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            interactionSource = source,
            content = { content() },
        )
        ExpressiveButtonStyle.COMPLETE -> Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.tertiaryContainer,
                contentColor = scheme.onTertiaryContainer,
            ),
            contentPadding = padding,
            interactionSource = source,
            content = { content() },
        )
        ExpressiveButtonStyle.TEXT -> TextButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = shape,
            interactionSource = source,
            content = { content() },
        )
        ExpressiveButtonStyle.DESTRUCTIVE_TEXT -> TextButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
            interactionSource = source,
            content = { content() },
        )
    }
}

/**
 * Two buttons side by side where the pressed one grows and its neighbour gives way, like an Expressive
 * button group. Stacks vertically at large font scales so labels never clip.
 */
@Composable
fun ExpressiveButtonRow(
    primary: @Composable (Modifier, MutableInteractionSource) -> Unit,
    secondary: @Composable (Modifier, MutableInteractionSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primarySource = remember { MutableInteractionSource() }
    val secondarySource = remember { MutableInteractionSource() }
    if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            primary(Modifier.fillMaxWidth(), primarySource)
            secondary(Modifier.fillMaxWidth(), secondarySource)
        }
        return
    }
    val primaryPressed by primarySource.collectIsPressedAsState()
    val secondaryPressed by secondarySource.collectIsPressedAsState()
    val primaryWeight by animateFloatAsState(
        targetValue = 1.4f + (if (primaryPressed) 0.3f else 0f) - (if (secondaryPressed) 0.15f else 0f),
        animationSpec = WayneedleMotion.fastSpatial(),
        label = "primary weight",
    )
    val secondaryWeight by animateFloatAsState(
        targetValue = 1f + (if (secondaryPressed) 0.3f else 0f) - (if (primaryPressed) 0.15f else 0f),
        animationSpec = WayneedleMotion.fastSpatial(),
        label = "secondary weight",
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        primary(Modifier.weight(primaryWeight.coerceAtLeast(0.1f)), primarySource)
        secondary(Modifier.weight(secondaryWeight.coerceAtLeast(0.1f)), secondarySource)
    }
}

package app.wayneedle.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.wayneedle.ui.theme.tabular

/** The bigger value type shrinks once the text would no longer fit a half-width tile. */
private const val LARGE_MAX_LENGTH = 8
private const val MEDIUM_MAX_LENGTH = 10
private const val DIMMED_ALPHA = 0.6f

/**
 * A labelled number with an optional unit. Reads as one phrase to TalkBack ("Distance, 12.34 kilometres").
 *
 * @param spokenValue what a screen reader says for the value; defaults to [value] and [unit].
 * @param large use the bigger type for the two headline statistics.
 * @param dimmed de-emphasise a value that is not counting (no trip, or still waiting for a GPS fix).
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    spokenValue: String = if (unit == null) value else "$value $unit",
    large: Boolean = false,
    dimmed: Boolean = false,
) {
    val typography = MaterialTheme.typography
    val textLength = value.length + (unit?.length?.plus(1) ?: 0)
    val valueStyle = when {
        !large -> typography.titleLarge
        textLength <= LARGE_MAX_LENGTH -> typography.headlineLarge
        textLength <= MEDIUM_MAX_LENGTH -> typography.headlineMedium
        else -> typography.headlineSmall
    }
    val unitStyle = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge
    val unitColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueText = buildAnnotatedString {
        append(value)
        if (unit != null) {
            withStyle(SpanStyle(fontSize = unitStyle.fontSize, fontWeight = FontWeight.Medium, color = unitColor)) {
                append(" ")
                append(unit)
            }
        }
    }
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label, $spokenValue" },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = valueText,
                style = valueStyle.tabular(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(if (dimmed) DIMMED_ALPHA else 1f),
            )
        }
    }
}

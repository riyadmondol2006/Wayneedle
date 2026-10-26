package app.wayneedle.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.wayneedle.BuildConfig
import app.wayneedle.R
import app.wayneedle.ui.icons.WayneedleIcons
import app.wayneedle.update.ReleaseInfo

/** "Update available": the new version and its notes; Download opens the release page in the browser. */
@Composable
fun UpdateDialog(
    release: ReleaseInfo,
    onDismiss: () -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(WayneedleIcons.Download, contentDescription = null) },
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.update_available_body, release.version, BuildConfig.VERSION_NAME))
                val notes = release.notes.trim()
                if (notes.isNotEmpty()) {
                    Text(
                        text = notes.take(MAX_NOTES_CHARS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(min = 0.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    context.openReleasePage(release.pageUrl)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_download)) }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_later)) }
                if (onSkip != null) TextButton(onClick = onSkip) { Text(stringResource(R.string.action_skip_version)) }
            }
        },
    )
}

private const val MAX_NOTES_CHARS = 1_500

private fun Context.openReleasePage(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing sensible to do; the release page stays where it is.
    }
}

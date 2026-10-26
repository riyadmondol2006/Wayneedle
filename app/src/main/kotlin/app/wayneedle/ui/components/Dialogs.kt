package app.wayneedle.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.wayneedle.R
import app.wayneedle.ui.icons.WayneedleIcons

/** A message with a single acknowledgement button, for outcomes the user cannot change from here. */
@Composable
fun InfoDialog(
    title: String,
    text: String,
    buttonLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(buttonLabel) } },
    )
}

/** Tells the user that a delete did not go through (storage error); nothing was changed. */
@Composable
fun DeleteFailedDialog(onDismiss: () -> Unit) {
    InfoDialog(
        title = stringResource(R.string.error_delete_failed_title),
        text = stringResource(R.string.error_delete_failed_body),
        buttonLabel = stringResource(R.string.action_ok),
        onDismiss = onDismiss,
        icon = WayneedleIcons.Warning,
    )
}

/**
 * A confirm/cancel dialog. Set [destructive] to colour the confirm action as an error so deleting never
 * looks like the safe choice.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

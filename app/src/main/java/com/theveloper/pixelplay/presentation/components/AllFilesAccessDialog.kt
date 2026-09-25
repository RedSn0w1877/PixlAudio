package com.theveloper.pixelplay.presentation.components

import com.theveloper.pixelplay.ui.glass.GlassAlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.theveloper.pixelplay.R
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun AllFilesAccessDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.all_files_access_title)) },
        text = { Text(text = stringResource(id = R.string.all_files_access_description)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.grant_permission_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    )
}

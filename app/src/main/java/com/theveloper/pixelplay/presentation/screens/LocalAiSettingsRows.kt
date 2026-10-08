package com.theveloper.pixelplay.presentation.screens

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.ai.local.DownloadFailureReason
import com.theveloper.pixelplay.data.ai.local.DownloadedModelSpec
import com.theveloper.pixelplay.data.ai.local.DownloadedModelState
import com.theveloper.pixelplay.data.ai.local.ModelFileDownloader
import com.theveloper.pixelplay.data.ai.local.NanoStatus
import com.theveloper.pixelplay.data.ai.local.OnDeviceFailure
import com.theveloper.pixelplay.presentation.components.AdaptiveAlertDialog
import com.theveloper.pixelplay.presentation.components.glassClear

/** Who answers AI requests right now, for the rows' "in use" wording. */
enum class LocalAiAnswerer { NANO, DOWNLOADED_MODEL, CLOUD }

private fun formatBytes(context: android.content.Context, bytes: Long): String =
    Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))

/**
 * Gemini Nano's row: whether it runs AI here, or why not, with "Get ready" when Android only
 * needs to download it. Same flat surface as [ActionSettingsItem], so glass mode (inside the
 * glass SettingsSubsection) and Material mode both render it.
 */
@Composable
fun GeminiNanoStatusRow(
    status: NanoStatus,
    answerer: LocalAiAnswerer,
    onGetReady: () -> Unit,
) {
    val context = LocalContext.current
    val subtitle = when (status) {
        NanoStatus.Unknown -> stringResource(R.string.settings_local_ai_nano_checking)
        NanoStatus.Ready -> when (answerer) {
            LocalAiAnswerer.NANO -> stringResource(R.string.settings_local_ai_nano_in_use)
            LocalAiAnswerer.DOWNLOADED_MODEL -> stringResource(R.string.settings_local_ai_downloaded_answers)
            LocalAiAnswerer.CLOUD -> stringResource(R.string.settings_local_ai_cloud_answers)
        }
        NanoStatus.NeedsDownload -> stringResource(R.string.settings_local_ai_nano_needs_download)
        is NanoStatus.Downloading -> if (status.bytesTotal != null) {
            stringResource(
                R.string.settings_local_ai_nano_downloading,
                stringResource(
                    R.string.ai_model_download_progress,
                    formatBytes(context, status.bytesDownloaded),
                    formatBytes(context, status.bytesTotal),
                )
            )
        } else {
            stringResource(R.string.settings_local_ai_nano_downloading_unknown)
        }
        is NanoStatus.Unavailable -> when (status.failure) {
            OnDeviceFailure.NEEDS_UPDATE -> stringResource(R.string.settings_local_ai_nano_needs_update)
            OnDeviceFailure.PREPARING -> stringResource(R.string.settings_local_ai_nano_downloading_unknown)
            else -> stringResource(R.string.settings_local_ai_nano_unavailable)
        }
    }
    LocalAiRowSurface {
        LocalAiRowHeader(
            title = stringResource(R.string.settings_local_ai_nano_title),
            subtitle = subtitle,
        )
        when (status) {
            NanoStatus.NeedsDownload -> {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onGetReady, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_local_ai_get_ready))
                }
            }
            is NanoStatus.Downloading -> {
                Spacer(Modifier.height(12.dp))
                val total = status.bytesTotal
                if (total != null && total > 0L) {
                    val fraction = (status.bytesDownloaded.toFloat() / total).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            else -> Unit
        }
    }
}

/** The downloaded model's row: size and Download, progress with Cancel, or its size with Delete. */
@Composable
fun DownloadedModelRow(
    spec: DownloadedModelSpec,
    state: DownloadedModelState,
    loadError: String?,
    cloudIsOn: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val size = formatBytes(context, spec.sizeBytes)
    val subtitle = when (state) {
        DownloadedModelState.NotDownloaded -> stringResource(R.string.settings_local_ai_model_not_downloaded, size)
        DownloadedModelState.Queued -> stringResource(R.string.settings_local_ai_model_queued)
        is DownloadedModelState.Downloading -> stringResource(
            R.string.ai_model_download_progress,
            formatBytes(context, state.bytes),
            formatBytes(context, state.total),
        )
        DownloadedModelState.Verifying -> stringResource(R.string.settings_local_ai_model_verifying)
        is DownloadedModelState.Ready -> stringResource(R.string.settings_local_ai_model_ready, formatBytes(context, state.sizeBytes))
        is DownloadedModelState.Failed -> when (state.reason) {
            DownloadFailureReason.NOT_ENOUGH_SPACE -> stringResource(
                R.string.settings_local_ai_model_failed_space,
                formatBytes(context, ModelFileDownloader.requiredFreeBytes(spec.sizeBytes, 0L)),
            )
            DownloadFailureReason.CORRUPT -> stringResource(R.string.settings_local_ai_model_failed_corrupt)
            DownloadFailureReason.HTTP, DownloadFailureReason.IO -> stringResource(R.string.settings_local_ai_model_failed)
        }
    }
    LocalAiRowSurface {
        LocalAiRowHeader(title = spec.displayName, subtitle = subtitle)
        if (state is DownloadedModelState.Ready && loadError != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_local_ai_model_load_failed, loadError.take(80)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state is DownloadedModelState.Ready && cloudIsOn) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_local_ai_model_cloud_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            is DownloadedModelState.Downloading -> {
                Spacer(Modifier.height(12.dp))
                val fraction = if (state.total > 0L) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            DownloadedModelState.Verifying, DownloadedModelState.Queued -> {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            else -> Unit
        }
        Spacer(Modifier.height(12.dp))
        when (state) {
            DownloadedModelState.NotDownloaded, is DownloadedModelState.Failed -> FilledTonalButton(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    if (state is DownloadedModelState.Failed) stringResource(R.string.settings_local_ai_try_again)
                    else stringResource(R.string.settings_local_ai_download)
                )
            }
            DownloadedModelState.Queued, is DownloadedModelState.Downloading -> OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_local_ai_cancel)) }
            DownloadedModelState.Verifying -> Unit
            is DownloadedModelState.Ready -> OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_local_ai_delete)) }
        }
    }
}

/**
 * Confirms the 2.6 GB download: what it is, its size and licence (Apache-2.0 has nothing to
 * accept, but it's shown before anything downloads), Wi-Fi by default with a mobile-data opt-in.
 */
@Composable
fun DownloadModelDialog(
    spec: DownloadedModelSpec,
    hasRoom: Boolean,
    onConfirm: (allowMetered: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var allowMetered by remember { mutableStateOf(false) }
    AdaptiveAlertDialog(
        icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_local_ai_download_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.settings_local_ai_download_dialog_text,
                        spec.displayName,
                        formatBytes(context, spec.sizeBytes),
                    )
                )
                Text(
                    text = stringResource(R.string.settings_local_ai_download_dialog_licence, spec.licenseName),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, spec.licenseUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text(stringResource(R.string.settings_local_ai_download_dialog_view_licence)) }
                if (!hasRoom) {
                    Text(
                        text = stringResource(
                            R.string.settings_local_ai_download_dialog_no_space,
                            formatBytes(context, ModelFileDownloader.requiredFreeBytes(spec.sizeBytes, 0L)),
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { allowMetered = !allowMetered },
                ) {
                    Checkbox(checked = allowMetered, onCheckedChange = { allowMetered = it })
                    Text(stringResource(R.string.settings_local_ai_download_dialog_metered))
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(allowMetered) }, enabled = hasRoom) {
                Text(stringResource(R.string.settings_local_ai_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_local_ai_cancel)) }
        },
    )
}

@Composable
fun DeleteModelDialog(
    sizeBytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AdaptiveAlertDialog(
        title = { Text(stringResource(R.string.settings_local_ai_delete_dialog_title)) },
        text = { Text(stringResource(R.string.settings_local_ai_delete_dialog_text, formatBytes(context, sizeBytes))) },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_local_ai_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_local_ai_cancel)) }
        },
    )
}

@Composable
private fun LocalAiRowSurface(content: @Composable () -> Unit) {
    Surface(
        color = glassClear(MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun LocalAiRowHeader(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(end = 16.dp).size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

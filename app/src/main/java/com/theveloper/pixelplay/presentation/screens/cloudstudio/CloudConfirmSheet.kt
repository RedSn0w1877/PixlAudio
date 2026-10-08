package com.theveloper.pixelplay.presentation.screens.cloudstudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.cloudstudio.CloudBatchPreview
import com.theveloper.pixelplay.data.cloudstudio.CloudCost
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.cloudstudio.CloudNotice
import com.theveloper.pixelplay.data.cloudstudio.CloudSettingsSnapshot
import com.theveloper.pixelplay.presentation.components.AdaptiveModalBottomSheet
import com.theveloper.pixelplay.presentation.viewmodel.CloudBatchOrigin
import com.theveloper.pixelplay.presentation.viewmodel.CloudStudioViewModel

/** Shows the confirm sheet when a batch asked for from [origin] is waiting (one host per screen that can ask). */
@Composable
fun CloudConfirmSheetHost(origin: CloudBatchOrigin, viewModel: CloudStudioViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pending = ui.pendingBatch?.takeIf { it.origin == origin } ?: return
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    CloudConfirmSheet(
        batch = pending.preview,
        settings = settings,
        isSending = ui.isSending,
        onCancel = viewModel::dismissBatch,
        onSend = viewModel::send,
    )
}

/**
 * The confirm sheet before anything is sent (design §5 app guards, §7.3), ported from the iOS app's
 * `CloudConfirmSheet`: songs, minutes, upload size, the estimated cost and what is left of the month's cap, plus what
 * was skipped and why. The figures sit on plain tonal fills; the Send button names the song count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudConfirmSheet(
    batch: CloudBatchPreview,
    settings: CloudSettingsSnapshot,
    isSending: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val estimate = batch.estimate
    val canSend = !batch.isEmpty && estimate.fitsCap && settings.enabled && !isSending
    AdaptiveModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Send to the cloud", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(batch.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure("Songs", "${estimate.songs}", Icons.AutoMirrored.Rounded.QueueMusic, Modifier.weight(1f))
                    Figure("Minutes", "${estimate.minutes}", Icons.Rounded.Schedule, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure("Upload", "${estimate.uploadMb} MB", Icons.Rounded.Upload, Modifier.weight(1f))
                    Figure("Estimated cost", CloudCost.format(estimate.costMicroUsd), Icons.Rounded.AttachMoney, Modifier.weight(1f))
                }
                if (estimate.fitsCap) {
                    Line(
                        "This month: ${CloudCost.format(estimate.remainingMicroUsd)} left of ${CloudCost.format(estimate.capMicroUsd)}.",
                        Icons.Rounded.Speed,
                    )
                } else {
                    Line(
                        "Over this month's cap: ${CloudCost.format(estimate.remainingMicroUsd)} left of " +
                            "${CloudCost.format(estimate.capMicroUsd)}. Raise it in Cloud processing, or send fewer songs.",
                        Icons.Rounded.Warning, emphasis = true,
                    )
                }
                if (estimate.streamedSongs > 0) {
                    Line(
                        if (estimate.streamedSongs == 1) "1 streamed song is downloaded to this phone first, then sent."
                        else "${estimate.streamedSongs} streamed songs are downloaded to this phone first, then sent.",
                        Icons.Rounded.Download,
                    )
                }
                if (!settings.useCellular) Line("Uploads wait for Wi-Fi (Use mobile data is off).", Icons.Rounded.Wifi)
                batch.skipped.forEach { (reason, count) ->
                    Line("$count skipped — ${reason.label}", Icons.Rounded.RemoveCircleOutline)
                }
                if (!settings.enabled) Line(CloudStudioCopy.notice(CloudNotice.OFF), Icons.Rounded.Lock, emphasis = true)
                CloudCaption(CloudStudioCopy.PROMISE, modifier = Modifier.padding(top = 4.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onCancel, enabled = !isSending) { Text("Cancel") }
                Button(onClick = onSend, enabled = canSend, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.CloudUpload, null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(CloudStudioCopy.sendLabel(batch.plans.size), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Figure(title: String, value: String, icon: ImageVector, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun Line(text: String, icon: ImageVector, emphasis: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, modifier = Modifier.size(20.dp),
            tint = if (emphasis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = if (emphasis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

/** The leading symbol of a job's state (the iOS `CloudJobStateIcon` in Material Symbols). */
internal object CloudJobIcons {
    fun icon(state: CloudJobState): ImageVector = when (state) {
        CloudJobState.QUEUED -> Icons.Rounded.Schedule
        CloudJobState.PREPARING -> Icons.Rounded.GraphicEq
        CloudJobState.UPLOADING -> Icons.Rounded.Upload
        CloudJobState.UPLOADED -> Icons.Rounded.HourglassTop
        CloudJobState.SUBMITTED -> Icons.Rounded.HourglassBottom
        CloudJobState.RUNNING -> Icons.Rounded.Memory
        CloudJobState.RESULTS_READY, CloudJobState.DOWNLOADING -> Icons.Rounded.Download
        CloudJobState.IMPORTED -> Icons.Rounded.CheckCircle
        CloudJobState.FAILED -> Icons.Rounded.ErrorOutline
        CloudJobState.CANCELLED -> Icons.Rounded.Cancel
        CloudJobState.EXPIRED -> Icons.Rounded.Schedule
    }
}

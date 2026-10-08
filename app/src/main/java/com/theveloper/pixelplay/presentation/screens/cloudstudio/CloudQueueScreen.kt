package com.theveloper.pixelplay.presentation.screens.cloudstudio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.cloudstudio.CloudBatchKind
import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.glassClear
import com.theveloper.pixelplay.presentation.screens.SettingsItem
import com.theveloper.pixelplay.presentation.viewmodel.CloudBatchOrigin
import com.theveloper.pixelplay.presentation.viewmodel.CloudStudioViewModel

/**
 * The "process later" queue (design §7.3), ported from the iOS app's `CloudQueueView`: what is on its way to the
 * cloud, what came back, and what needs the person. Songs are added from here (Current song / Songs without
 * word-timed lyrics / Songs without an instrumental) through the confirm sheet. Rows are keyed lazy items whose text
 * is built once per change of their own record; progress comes from the engine in whole percents, never a timer.
 */
@Composable
fun CloudQueueScreen(
    currentSong: () -> Song?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CloudStudioViewModel = hiltViewModel(),
) {
    val state by viewModel.engineState.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onScreenShown() }

    val active = remember(state.jobs) { state.activeJobs }
    val attention = remember(state.jobs) { state.attentionJobs }
    val finished = remember(state.jobs) { state.finishedJobs }
    val committed = remember(state.jobs, settings.pricePerSecondMicroUsd) { viewModel.committedThisMonthMicroUsd() }
    val progress = state.transferProgress

    Box(modifier = Modifier.fillMaxSize()) {
        CloudScreenScaffold(title = "Cloud queue", onBack = onBack) {
            state.notice?.let { notice ->
                item(key = "notice") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        CloudPanel(
                            color = if (CloudStudioCopy.noticeIsSoft(notice)) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onSurface)
                                Text(CloudStudioCopy.notice(notice), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                            if (CloudStudioCopy.noticeOpensSettings(notice)) {
                                FilledTonalButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                                    Text("Open Cloud processing")
                                }
                            }
                        }
                    }
                }
            }
            item(key = "add") {
                CloudSection("Add songs") {
                    AddRow("Current song", "The song that's playing now.", Icons.Rounded.MusicNote) {
                        viewModel.requestBatch(CloudBatchKind.CURRENT, currentSong(), CloudStudioCopy.batchTitle(CloudBatchKind.CURRENT))
                    }
                    AddRow("Songs without word-timed lyrics", "Up to 200 songs from your library.", Icons.Rounded.Subtitles) {
                        viewModel.requestBatch(CloudBatchKind.MISSING_LYRICS, null, CloudStudioCopy.batchTitle(CloudBatchKind.MISSING_LYRICS))
                    }
                    AddRow("Songs without an instrumental", "Up to 200 songs from your library.", Icons.Rounded.GraphicEq) {
                        viewModel.requestBatch(CloudBatchKind.MISSING_INSTRUMENTAL, null,
                            CloudStudioCopy.batchTitle(CloudBatchKind.MISSING_INSTRUMENTAL))
                    }
                }
            }
            if (state.isLoaded && active.isEmpty() && attention.isEmpty() && finished.isEmpty()) {
                item(key = "empty") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        CloudPanel {
                            Text(
                                "Nothing in the queue yet. Songs you send come back here with their instrumental and " +
                                    "word-timed lyrics.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            jobGroup("active", "On its way (${active.size})", active, progress, viewModel)
            jobGroup("attention", "Needs you (${attention.size})", attention, emptyMap(), viewModel)
            jobGroup("finished", "Done (${finished.size})", finished, emptyMap(), viewModel)
            if (finished.isNotEmpty()) {
                item(key = "clear") {
                    OutlinedButton(
                        onClick = viewModel::clearFinished,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Rounded.Done, null)
                        Text("  Clear done")
                    }
                }
            }
            item(key = "month") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    CloudPanel {
                        Text(
                            CloudStudioCopy.monthLine(committed, settings.monthlyCapMicroUsd),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        CloudCaption(CloudStudioCopy.PROMISE)
                    }
                }
            }
        }
        if (ui.isPreviewing) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Looking through your library", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    CloudConfirmSheetHost(origin = CloudBatchOrigin.QUEUE, viewModel = viewModel)
}

private fun androidx.compose.foundation.lazy.LazyListScope.jobGroup(
    key: String,
    title: String,
    jobs: List<CloudJobRecord>,
    progress: Map<String, Float>,
    viewModel: CloudStudioViewModel,
) {
    if (jobs.isEmpty()) return
    item(key = "header_$key") {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp),
        )
    }
    items(jobs, key = { "job_${it.jobKey}" }, contentType = { "job" }) { record ->
        CloudJobRow(
            record = record,
            progress = progress[record.jobKey],
            onCancel = { viewModel.cancel(record.jobKey) },
            onRetry = { viewModel.retry(record.jobKey) },
            onRemove = { viewModel.remove(record.jobKey) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AddRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    SettingsItem(
        title = title,
        subtitle = subtitle,
        leadingIcon = { Icon(icon, null, tint = MaterialTheme.colorScheme.secondary) },
        trailingIcon = {
            Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        onClick = onClick,
    )
}

/** One job: its song, where it is (with transfer or worker progress), what went wrong with Retry, or what it cost. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CloudJobRow(
    record: CloudJobRecord,
    progress: Float?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = remember(record, progress) { CloudStudioCopy.status(record, progress) }
    val detail = remember(record) { CloudStudioCopy.detail(record) }
    var menu by remember { mutableStateOf(false) }
    val failed = record.state == CloudJobState.FAILED
    Surface(
        color = glassClear(MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { if (record.state.isFinished) menu = true }),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = CloudJobIcons.icon(record.state),
                contentDescription = record.state.label,
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(record.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(record.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.labelLarge, maxLines = 2,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (record.state) {
                CloudJobState.FAILED, CloudJobState.CANCELLED, CloudJobState.EXPIRED ->
                    FilledTonalButton(onClick = onRetry) { Text("Retry") }
                CloudJobState.IMPORTED -> Unit
                else -> FilledTonalIconButton(
                    onClick = onCancel,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel")
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Remove from the list") }, onClick = {
                menu = false
                onRemove()
            })
        }
    }
}

/**
 * The studio card's Cloud row (the iOS `CloudSongRow`): the song's latest cloud job, or the button that sends it
 * through the confirm sheet. Nothing at all while Cloud processing is off.
 */
@Composable
fun CloudSongRow(song: Song?, viewModel: CloudStudioViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    if (!settings.enabled) return
    val state by viewModel.engineState.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val job = remember(state.jobs, song?.id) { song?.let { s -> state.jobs.lastOrNull { it.songId == s.id } } }
    val progress = job?.let { state.transferProgress[it.jobKey] }
    val pending = job?.state?.isPending == true
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (job != null) {
            val status = remember(job, progress) { "Cloud: " + CloudStudioCopy.status(job, progress) }
            val failed = job.state == CloudJobState.FAILED
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(CloudJobIcons.icon(job.state), null, modifier = Modifier.size(18.dp),
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalButton(
            onClick = { song?.let { viewModel.requestBatch(listOf(it), it.title, CloudBatchOrigin.STUDIO_CARD) } },
            enabled = song != null && !pending && !ui.isPreviewing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (song == null) "Play a song first" else "Process in the cloud")
        }
    }
    CloudConfirmSheetHost(origin = CloudBatchOrigin.STUDIO_CARD, viewModel = viewModel)
}

package com.theveloper.pixelplay.presentation.components.tais

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.worker.BsRoformerRenderWorker
import com.theveloper.pixelplay.data.worker.StemSeparatorWorker
import com.theveloper.pixelplay.data.worker.TaisStudioWorker
import com.theveloper.pixelplay.presentation.screens.cloudstudio.CloudSongButton
import com.theveloper.pixelplay.presentation.screens.cloudstudio.CloudSongStatus
import com.theveloper.pixelplay.presentation.screens.cloudstudio.CloudStudioCopy

/**
 * "Make an instrumental": one card, two ways to do it side by side — "Process on-device" ([StemSeparatorWorker]) and
 * "Process in cloud" (Cloud Studio) — with the progress of each underneath. This used to be the "Remaster Song" card
 * with a separate lyric-sync button; lyric syncing is no longer offered here. The on-device half reads its progress
 * straight from WorkManager so this survives the screen being backgrounded mid-render.
 */
@Composable
fun TaisStudioProgressCard(
    song: Song?,
    modifier: Modifier = Modifier,
    // BS-RoFormer specifically is off for Beta 1 — that render pipeline is still flaky
    // (processed songs can drop out and need reprocessing). Regular on-device processing
    // (StemSeparator) is unrelated and stays on regardless.
    showRoformerTools: Boolean = false,
    onInstrumentalReady: (instrumentalPath: String) -> Unit = {},
    // Not used any more (the card has no lyric-sync button); kept so existing call sites still compile.
    @Suppress("UNUSED_PARAMETER") onLyricsReady: (wordSyncProduced: Boolean) -> Unit = {},
    onRoformerInstrumentalReady: (instrumentalPath: String) -> Unit = {}
) {
    val context = LocalContext.current
    val instrumental = rememberTaisJob(
        song = song,
        uniqueWorkName = StemSeparatorWorker::uniqueWorkName,
        percentOf = { it.progress.getInt(StemSeparatorWorker.PROGRESS_PERCENT, 0) },
        detailOf = { it.progress.getString(StemSeparatorWorker.PROGRESS_DETAIL) },
        failureReasonOf = { it.outputData.getString(StemSeparatorWorker.OUTPUT_FAILURE_REASON) },
        onSucceeded = { info ->
            val path = info.outputData.getString(StemSeparatorWorker.OUTPUT_INSTRUMENTAL_PATH)
            if (path != null) onInstrumentalReady(path)
        }
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Make an instrumental",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = CloudStudioCopy.WIFI_HINT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (song == null) {
                FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Play a song first")
                }
            } else {
                // Equal height even when one label wraps onto a second line on a narrow phone.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    FilledTonalButton(
                        onClick = {
                            instrumental.markStarted()
                            StemSeparatorWorker.enqueue(WorkManager.getInstance(context), song.id, song.contentUriString)
                        },
                        enabled = !instrumental.isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Text("Process on-device", textAlign = TextAlign.Center)
                    }
                    CloudSongButton(
                        song = song,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }

            TaisJobStatus(instrumental, readyLabel = "Instrumental ready.")

            // The song's latest cloud job (only once it has one).
            CloudSongStatus(song = song)

            if (showRoformerTools) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                TaisJobRow(
                    song = song,
                    buttonLabel = "Render Studio Master (BS-RoFormer)",
                    readyLabel = "BS-RoFormer master ready.",
                    uniqueWorkName = BsRoformerRenderWorker::uniqueWorkName,
                    enqueue = { workManager, s -> BsRoformerRenderWorker.enqueue(workManager, s.id, s.contentUriString) },
                    percentOf = { it.progress.getInt(BsRoformerRenderWorker.PROGRESS_PERCENT, 0) },
                    detailOf = { it.progress.getString(BsRoformerRenderWorker.PROGRESS_DETAIL) },
                    failureReasonOf = { it.outputData.getString(BsRoformerRenderWorker.OUTPUT_FAILURE_REASON) },
                    onSucceeded = { info ->
                        val path = info.outputData.getString(BsRoformerRenderWorker.OUTPUT_INSTRUMENTAL_PATH)
                        if (path != null) onRoformerInstrumentalReady(path)
                    }
                )
            }
        }
    }
}

/** What WorkManager says about one job of one song: read by [TaisJobStatus] and by the job's button. */
@Stable
private class TaisJobState {
    var isRunning by mutableStateOf(false)
    var percent by mutableStateOf(0)
    var detail by mutableStateOf<String?>(null)
    var failureReason by mutableStateOf<String?>(null)
    var succeeded by mutableStateOf(false)
    var completionDetail by mutableStateOf<String?>(null)

    /** The button was tapped: show the bar at once, before WorkManager reports the new work. */
    fun markStarted() {
        isRunning = true
        percent = 0
        succeeded = false
        failureReason = null
    }
}

/** Follows [song]'s job through [WorkManager]'s unique-work flow; [TaisJobState] is what the screen reads. */
@Composable
private fun rememberTaisJob(
    song: Song?,
    uniqueWorkName: (String) -> String,
    percentOf: (WorkInfo) -> Int,
    detailOf: (WorkInfo) -> String?,
    failureReasonOf: (WorkInfo) -> String?,
    onSucceeded: (WorkInfo) -> Unit
): TaisJobState {
    val context = LocalContext.current
    val job = remember(song?.id) { TaisJobState() }

    val currentOnSucceeded by rememberUpdatedState(onSucceeded)
    LaunchedEffect(song?.id) {
        var deliveredCompletion: java.util.UUID? = null
        val songId = song?.id ?: return@LaunchedEffect
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(uniqueWorkName(songId))
            .collect { infos ->
                val info = infos.firstOrNull { !it.state.isFinished }
                    ?: infos.maxByOrNull { item ->
                        item.tags.firstOrNull { it.startsWith(TaisStudioWorker.REQUEST_CREATED_TAG) }
                            ?.removePrefix(TaisStudioWorker.REQUEST_CREATED_TAG)?.toLongOrNull() ?: 0L
                    } ?: return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        job.isRunning = true
                        job.succeeded = false
                        job.percent = percentOf(info)
                        job.detail = detailOf(info)
                        job.failureReason = null
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        job.isRunning = false
                        job.percent = 100
                        job.failureReason = failureReasonOf(info)
                        job.succeeded = job.failureReason == null
                        job.completionDetail = info.outputData.getString(TaisStudioWorker.OUTPUT_DETAIL)
                        if (job.succeeded && deliveredCompletion != info.id) {
                            deliveredCompletion = info.id
                            currentOnSucceeded(info)
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        job.isRunning = false
                        job.succeeded = false
                        job.failureReason = failureReasonOf(info) ?: "Unknown error"
                    }
                    WorkInfo.State.CANCELLED -> {
                        job.isRunning = false
                        job.succeeded = false
                        job.failureReason = "Cancelled — tap below to try again."
                    }
                }
            }
    }
    return job
}

/** The job's progress bar while it runs, then its ready line or what went wrong; nothing at all while idle. */
@Composable
private fun ColumnScope.TaisJobStatus(job: TaisJobState, readyLabel: String) {
    AnimatedVisibility(visible = job.isRunning, enter = fadeIn(), exit = fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(
                progress = { job.percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
            Text(
                text = "${job.detail ?: "Starting…"} · ${job.percent}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    AnimatedVisibility(visible = job.succeeded && !job.isRunning, enter = fadeIn(), exit = fadeOut()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = job.completionDetail ?: readyLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    job.failureReason?.let { reason ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** One job's full-width trigger button + progress/result state, driven by [WorkManager]'s unique-work flow for [song]. */
@Composable
private fun TaisJobRow(
    song: Song?,
    buttonLabel: String,
    readyLabel: String,
    uniqueWorkName: (String) -> String,
    enqueue: (WorkManager, Song) -> Unit,
    percentOf: (WorkInfo) -> Int,
    detailOf: (WorkInfo) -> String?,
    failureReasonOf: (WorkInfo) -> String?,
    onSucceeded: (WorkInfo) -> Unit
) {
    val context = LocalContext.current
    val job = rememberTaisJob(song, uniqueWorkName, percentOf, detailOf, failureReasonOf, onSucceeded)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TaisJobStatus(job, readyLabel)

        FilledTonalButton(
            onClick = {
                val s = song ?: return@FilledTonalButton
                job.markStarted()
                enqueue(WorkManager.getInstance(context), s)
            },
            enabled = song != null && !job.isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (song == null) "Play a song first" else buttonLabel)
        }
    }
}

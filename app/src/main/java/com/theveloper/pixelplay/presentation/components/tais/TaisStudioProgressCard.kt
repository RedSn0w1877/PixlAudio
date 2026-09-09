package com.theveloper.pixelplay.presentation.components.tais

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.worker.BsRoformerRenderWorker
import com.theveloper.pixelplay.data.worker.StemSeparatorWorker
import com.theveloper.pixelplay.data.worker.TaisStudioWorker

/**
 * "Instrumental" and "Lyrics" trigger + progress card. These used to be one combined "Remaster
 * Song" button that always ran stem separation *and* lyric alignment sequentially — split so a
 * lyrics-only request doesn't pay for a full separation pass it doesn't need, and vice versa.
 * Each half is backed by its own worker ([StemSeparatorWorker] / [TaisStudioWorker]) and reads its
 * progress straight from WorkManager so this survives the screen being backgrounded mid-render.
 */
@Composable
fun TaisStudioProgressCard(
    song: Song?,
    modifier: Modifier = Modifier,
    // BS-RoFormer specifically is off for Beta 1 — that render pipeline is still flaky
    // (processed songs can drop out and need reprocessing). Regular Instrumentalize
    // (StemSeparator) and Sync Lyrics are unrelated and both stay on regardless.
    showRoformerTools: Boolean = false,
    onInstrumentalReady: (instrumentalPath: String) -> Unit = {},
    onLyricsReady: (wordSyncProduced: Boolean) -> Unit = {},
    onRoformerInstrumentalReady: (instrumentalPath: String) -> Unit = {}
) {
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
                        text = "Remaster Song",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Render an instrumental or sync the lyrics word-by-word for this " +
                            "track — each runs on its own, so you don't have to wait on one to get " +
                            "the other.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TaisJobRow(
                song = song,
                buttonLabel = "Render Instrumental",
                readyLabel = "Instrumental ready.",
                uniqueWorkName = StemSeparatorWorker::uniqueWorkName,
                enqueue = { workManager, s -> StemSeparatorWorker.enqueue(workManager, s.id, s.contentUriString) },
                percentOf = { it.progress.getInt(StemSeparatorWorker.PROGRESS_PERCENT, 0) },
                detailOf = { it.progress.getString(StemSeparatorWorker.PROGRESS_DETAIL) },
                failureReasonOf = { it.outputData.getString(StemSeparatorWorker.OUTPUT_FAILURE_REASON) },
                onSucceeded = { info ->
                    val path = info.outputData.getString(StemSeparatorWorker.OUTPUT_INSTRUMENTAL_PATH)
                    if (path != null) onInstrumentalReady(path)
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LyricsSyncJobRow(song = song, onLyricsReady = onLyricsReady)

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

/** Explicit user action also works for lyrics which already contain word timings. */
@Composable
fun LyricsSyncJobRow(song: Song?, onLyricsReady: (Boolean) -> Unit = {}) {
    TaisJobRow(
        song = song,
        buttonLabel = "Sync / resync lyrics",
        readyLabel = "Lyrics synced.",
        uniqueWorkName = TaisStudioWorker::uniqueWorkName,
        enqueue = { manager, track ->
            TaisStudioWorker.enqueue(manager, track.id, track.contentUriString, forceResync = true)
        },
        percentOf = { it.progress.getInt(TaisStudioWorker.PROGRESS_PERCENT, 0) },
        detailOf = { it.progress.getString(TaisStudioWorker.PROGRESS_DETAIL) },
        failureReasonOf = { it.outputData.getString(TaisStudioWorker.OUTPUT_FAILURE_REASON) },
        onSucceeded = { info ->
            onLyricsReady(info.outputData.getBoolean(TaisStudioWorker.OUTPUT_LYRICS_UPDATED,
                info.outputData.getBoolean(TaisStudioWorker.OUTPUT_WORD_SYNC_PRODUCED, false)))
        }
    )
}

/** One job's trigger button + progress/result state, driven entirely by [WorkManager]'s unique-work flow for [song]. */
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
    var isRunning by remember(song?.id) { mutableStateOf(false) }
    var percent by remember(song?.id) { mutableStateOf(0) }
    var detail by remember(song?.id) { mutableStateOf<String?>(null) }
    var failureReason by remember(song?.id) { mutableStateOf<String?>(null) }
    var succeeded by remember(song?.id) { mutableStateOf(false) }
    var completionDetail by remember(song?.id) { mutableStateOf<String?>(null) }

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
                        isRunning = true
                        succeeded = false
                        percent = percentOf(info)
                        detail = detailOf(info)
                        failureReason = null
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        isRunning = false
                        percent = 100
                        failureReason = failureReasonOf(info)
                        succeeded = failureReason == null
                        completionDetail = info.outputData.getString(TaisStudioWorker.OUTPUT_DETAIL)
                        if (succeeded && deliveredCompletion != info.id) {
                            deliveredCompletion = info.id
                            currentOnSucceeded(info)
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        isRunning = false
                        succeeded = false
                        failureReason = failureReasonOf(info) ?: "Unknown error"
                    }
                    WorkInfo.State.CANCELLED -> {
                        isRunning = false
                        succeeded = false
                        failureReason = "Cancelled — tap below to try again."
                    }
                }
            }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(visible = isRunning, enter = fadeIn(), exit = fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Text(
                    text = "${detail ?: "Starting…"} · $percent%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        AnimatedVisibility(visible = succeeded && !isRunning, enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = completionDetail ?: readyLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        failureReason?.let { reason ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        FilledTonalButton(
            onClick = {
                val s = song ?: return@FilledTonalButton
                isRunning = true
                percent = 0
                succeeded = false
                failureReason = null
                enqueue(WorkManager.getInstance(context), s)
            },
            enabled = song != null && !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (song == null) "Play a song first" else buttonLabel)
        }
    }
}

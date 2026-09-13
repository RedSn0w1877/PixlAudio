package com.theveloper.pixelplay.presentation.components.tais

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.data.worker.StemSeparatorWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lyrics availability is independent of stem separation, including its offline playback action. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InstrumentalRenderAction(
    song: Song,
    instrumentalActive: Boolean,
    onPlayInstrumental: (String) -> Unit,
    onPlayOriginal: () -> Unit,
    onFindLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val workFlow = remember(song.id) {
        workManager.getWorkInfosByTagFlow(StemSeparatorWorker.uniqueWorkName(song.id))
    }
    val jobs by workFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeJob = jobs.firstOrNull { !it.state.isFinished }
    var enqueuePending by remember(song.id) { mutableStateOf(false) }
    var readyPath by remember(song.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(song.id, jobs) {
        if (jobs.isNotEmpty()) enqueuePending = false
        readyPath = withContext(Dispatchers.IO) {
            TaisInstrumentalIndex.bestAvailableFile(context, song.id)?.absolutePath
        }
    }
    val isRunning = activeJob != null || enqueuePending
    val error = jobs.firstOrNull { it.state == WorkInfo.State.FAILED }
        ?.outputData?.getString(StemSeparatorWorker.OUTPUT_FAILURE_REASON)

    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("No lyrics for this song yet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "You can still listen without vocals. Render an instrumental once and keep it on this device for offline listening.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isRunning) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    activeJob?.progress?.getString(StemSeparatorWorker.PROGRESS_DETAIL) ?: "Instrumental queued…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (!isRunning && readyPath == null && error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    val path = readyPath
                    when {
                        instrumentalActive -> onPlayOriginal()
                        path != null -> onPlayInstrumental(path)
                        else -> {
                            enqueuePending = true
                            StemSeparatorWorker.enqueue(workManager, song.id, song.contentUriString)
                        }
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(when {
                    instrumentalActive -> "Play original"
                    readyPath != null -> "Play instrumental"
                    isRunning -> "Rendering instrumental…"
                    else -> "Render instrumental"
                })
            }
            TextButton(onClick = onFindLyrics, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Find or import lyrics")
            }
        }
    }
}

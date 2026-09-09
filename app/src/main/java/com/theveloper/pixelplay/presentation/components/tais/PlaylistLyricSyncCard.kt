package com.theveloper.pixelplay.presentation.components.tais

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistLyricSyncState

/** Persistent WorkManager progress, styled with the player's expressive surface/shape tokens. */
@Composable
fun PlaylistLyricSyncCard(
    state: PlaylistLyricSyncState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.total == 0) return
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.isRunning) "Syncing your playlist" else "Playlist lyric sync",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "${state.completed} of ${state.total} finished · ${state.synced} synced · ${state.skipped} skipped" +
                    if (state.failedSongIds.isNotEmpty()) " · ${state.failedSongIds.size} need a retry" else "",
                style = MaterialTheme.typography.bodySmall
            )
            if (state.isRunning) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (state.isRunning) {
                    TextButton(onClick = onCancel) { Text("Cancel remaining") }
                } else if (state.failedSongIds.isNotEmpty()) {
                    FilledTonalButton(onClick = onRetry) { Text("Retry unfinished songs") }
                }
            }
        }
    }
}

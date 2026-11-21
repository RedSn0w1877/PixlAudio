package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel

/**
 * "Ask the host" of Endless Listening: one small chip that opens Ask Taizo in host mode. It exists only while Endless Listening is the queue's
 * station; for any other queue it draws nothing. [onClick] opens the one host sheet the app draws (see `EndlessStateHolder.openHostSheet`).
 */
@Composable
fun AskHostChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(stringResource(R.string.endless_ask_host), style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.height(32.dp),
    )
}

/** True while Endless Listening is the queue's station. Reads one flow, so only the entry that shows it recomposes. */
@Composable
fun rememberEndlessActive(playerViewModel: PlayerViewModel): Boolean {
    val active by playerViewModel.brainSession.endless.active.collectAsStateWithLifecycle()
    return active
}

/** The player's entry under the song's note: shown while Endless Listening is the queue's station. */
@Composable
fun AskHostPlayerEntry(playerViewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    if (!rememberEndlessActive(playerViewModel)) return
    AskHostChip(
        onClick = { playerViewModel.brainSession.endless.openHostSheet() },
        modifier = modifier,
    )
}

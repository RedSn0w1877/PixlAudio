package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.MixSongRow
import com.theveloper.pixelplay.presentation.utils.LocalBrainUi
import com.theveloper.pixelplay.presentation.viewmodel.MixDetailViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One made-for-you list (docs/brain-spec.md, 8.2): its songs with the line that says why each is here, Play and Shuffle. Material 3
 * Expressive, the same row as the Daily Mix screen. A song's overflow starts a station from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixDetailScreen(
    playerViewModel: PlayerViewModel,
    navController: NavController,
    viewModel: MixDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val brainUi = LocalBrainUi.current
    val currentSongId by remember { playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by remember { playerViewModel.stablePlayerState.map { it.isPlaying }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "header", contentType = "header") {
                androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ui.subtitle.isNotBlank()) Text(ui.subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ui.note?.let { note ->
                        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.brain_weekly_note_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(note, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (ui.songs.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                viewModel.expectPlay()
                                playerViewModel.showAndPlaySong(ui.songs.first(), ui.songs, ui.title, isVoluntaryPlay = false)
                            }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Text(stringResource(R.string.common_play), Modifier.padding(start = 8.dp))
                            }
                            FilledTonalButton(onClick = {
                                viewModel.expectPlay()
                                playerViewModel.playSongsShuffled(songsToPlay = ui.songs, queueName = ui.title, startAtZero = true)
                            }) {
                                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                                Text(stringResource(R.string.common_shuffle), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
            if (ui.loaded && ui.songs.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    Text(stringResource(R.string.brain_mix_nothing_yet), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            items(ui.songs, key = { it.id }, contentType = { "mix_song" }) { song ->
                MixSongRow(
                    song = song,
                    reason = ui.reasons[song.id],
                    isNovel = song.id in ui.novelIds,
                    isCurrentSong = currentSongId == song.id,
                    isPlaying = currentSongId == song.id && isPlaying,
                    onClick = {
                        viewModel.expectPlay()
                        playerViewModel.showAndPlaySong(song, ui.songs, ui.title, isVoluntaryPlay = false)
                    },
                    onMoreOptionsClick = { brainUi?.startSongStation?.invoke(it) },
                    onSave = { playerViewModel.brainSession.saveEngineSongs(listOf(song.id)) },
                )
            }
        }
    }
}

package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.MixSongRow
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.resolveNavBarOccupiedHeight
import com.theveloper.pixelplay.presentation.utils.LocalBrainUi
import com.theveloper.pixelplay.presentation.viewmodel.MixDetailViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One made-for-you list (docs/brain-spec.md, 8.2): its songs with the line that says why each is here, Play and Shuffle. Redesign batch 3:
 * the mix page of project/AndroidMix.dc.html (see MixPage.kt), the same row as the Daily Mix screen. A song's overflow starts a station
 * from it. These lists are planned by the brain and have no reroll, so the page has no Remix button.
 */
@Composable
fun MixDetailScreen(
    playerViewModel: PlayerViewModel,
    navController: NavController,
    viewModel: MixDetailViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val brainUi = LocalBrainUi.current
    val currentSongId by remember { playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by remember { playerViewModel.stablePlayerState.map { it.isPlaying }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false)
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showSaveMixSheet by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = MiniPlayerHeight + bottomBarHeightDp + 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "header", contentType = "mix_hero") {
                MixPageHero(
                    tone = MixPageTone.Secondary,
                    tag = stringResource(R.string.mix_tag_made_for_you),
                    title = ui.title,
                    meta = mixMetaLine(ui.songs),
                    why = mixWhyLine(ui.note, ui.songs.size, ui.songs.count { it.id in ui.novelIds }),
                    onShuffle = {
                        if (ui.songs.isNotEmpty()) {
                            viewModel.expectPlay()
                            playerViewModel.playSongsShuffled(songsToPlay = ui.songs, queueName = ui.title, startAtZero = true)
                        }
                    },
                    onPlay = {
                        if (ui.songs.isNotEmpty()) {
                            viewModel.expectPlay()
                            playerViewModel.showAndPlaySong(ui.songs.first(), ui.songs, ui.title, isVoluntaryPlay = false)
                        }
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
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
        MixPageTopButtons(
            onBack = { navController.popBackStack() },
            onSave = if (ui.songs.isNotEmpty()) ({ showSaveMixSheet = true }) else null,
        )
    }
    if (showSaveMixSheet) {
        val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
        PlaylistBottomSheet(
            playlistUiState = playlistUiState,
            songs = ui.songs,
            onDismiss = { showSaveMixSheet = false },
            bottomBarHeight = bottomBarHeightDp,
            playerViewModel = playerViewModel,
        )
    }
}

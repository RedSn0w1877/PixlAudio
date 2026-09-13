package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.navigation.navigateSafely

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.SongInfoBottomSheet
import com.theveloper.pixelplay.presentation.components.threeShapeSwitch
import com.theveloper.pixelplay.presentation.components.resolveNavBarOccupiedHeight
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.components.subcomps.TightWrapText
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.utils.formatDuration
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * "Check out your mix" destination — the same [Song] list the home shelf teases (with the same
 * curated → daily-mix → preview fallback chain), just the full set instead of the first four.
 * Structurally mirrors [DailyMixScreen] rather than sharing code with it: the two lists diverge
 * (this one is the always-on home recommendation, Daily Mix is the once-a-day AI-curated one)
 * and forcing them through one parameterized screen would leave every future divergence fighting
 * a shared abstraction instead of just being two screens.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YourMixScreen(
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel,
    navController: NavController,
) {
    val yourMixTitle = stringResource(R.string.home_your_mix_shelf_title)
    val playItLabel = stringResource(R.string.daily_mix_action_play_it)
    val shuffleLabel = stringResource(R.string.common_shuffle)

    val curatedYourMixSongs by playerViewModel.yourMixSongs.collectAsStateWithLifecycle()
    val isRegeneratingYourMix by playerViewModel.isRegeneratingYourMix.collectAsStateWithLifecycle()
    val dailyMixSongs by playerViewModel.dailyMixSongs.collectAsStateWithLifecycle()
    val homeMixPreviewSongs by playerViewModel.homeMixPreviewSongs.collectAsStateWithLifecycle()
    val usesFallbackHomeMix = remember(curatedYourMixSongs, dailyMixSongs) {
        curatedYourMixSongs.isEmpty() && dailyMixSongs.isEmpty()
    }
    val yourMixSongs: ImmutableList<Song> = remember(curatedYourMixSongs, dailyMixSongs, homeMixPreviewSongs) {
        when {
            curatedYourMixSongs.isNotEmpty() -> curatedYourMixSongs
            dailyMixSongs.isNotEmpty() -> dailyMixSongs
            else -> homeMixPreviewSongs
        }
    }

    val currentSongId by remember { playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by remember { playerViewModel.stablePlayerState.map { it.isPlaying }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue = false)
    val isShuffleEnabled by remember { playerViewModel.stablePlayerState.map { it.isShuffleEnabled }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue = false)
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()

    var showSongInfoSheet by remember { mutableStateOf(false) }

    val surfaceContainer = MaterialTheme.colorScheme.surface
    val headerColor = MaterialTheme.colorScheme.primary
    val backgroundBrush = remember(surfaceContainer, headerColor) {
        Brush.verticalGradient(
            colors = listOf(
                headerColor.copy(alpha = 0.25f),
                surfaceContainer.copy(alpha = 0.5f),
                surfaceContainer
            ),
            endY = 1200f
        )
    }

    fun playSong(song: Song) {
        if (usesFallbackHomeMix) {
            playerViewModel.showAndPlaySongFromLibrary(song, queueName = yourMixTitle)
        } else {
            playerViewModel.showAndPlaySong(song, yourMixSongs, yourMixTitle)
        }
    }

    if (showSongInfoSheet && selectedSongForInfo != null) {
        val song = selectedSongForInfo!!
        SongInfoBottomSheet(
            song = song,
            isFavorite = favoriteSongIds.contains(song.id),
            onToggleFavorite = { playerViewModel.toggleFavoriteSpecificSong(song) },
            onDismiss = { showSongInfoSheet = false },
            onPlaySong = { playSong(song) },
            onAddToQueue = { playerViewModel.addSongToQueue(song) },
            onAddNextToQueue = { playerViewModel.addSongNextToQueue(song) },
            onAddToPlayList = { showPlaylistBottomSheet = true },
            onDeleteFromDevice = playerViewModel::deleteFromDevice,
            onNavigateToAlbum = {
                navController.navigateSafely(Screen.AlbumDetail.createRoute(song.albumId))
                showSongInfoSheet = false
            },
            onNavigateToArtist = {
                navController.navigateSafely(Screen.ArtistDetail.createRoute(song.artistId))
                showSongInfoSheet = false
            },
            onNavigateToArtistById = { artistId ->
                navController.navigateSafely(Screen.ArtistDetail.createRoute(artistId))
                showSongInfoSheet = false
            },
            onNavigateToGenre = {
                song.genre?.let {
                    navController.navigateSafely(Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")))
                }
                showSongInfoSheet = false
            },
            onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                playerViewModel.editSongMetadata(
                    song,
                    newTitle,
                    newArtist,
                    newAlbum,
                    newAlbumArtist,
                    newComposer,
                    newGenre,
                    newLyrics,
                    newTrackNumber,
                    newDiscNumber,
                    replayGainTrackGainDb,
                    replayGainAlbumGainDb,
                    coverArtUpdate
                )
            },
            removeFromListTrigger = {}
        )

        if (showPlaylistBottomSheet) {
            val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
            PlaylistBottomSheet(
                playlistUiState = playlistUiState,
                songs = listOf(song),
                onDismiss = { showPlaylistBottomSheet = false },
                bottomBarHeight = bottomBarHeightDp,
                playerViewModel = playerViewModel,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        if (yourMixSongs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ContainedLoadingIndicator()
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "your_mix_header") {
                    ExpressiveYourMixHeader(songs = yourMixSongs, scrollState = lazyListState)
                }

                item(key = "play_shuffle_buttons") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(76.dp)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (yourMixSongs.isNotEmpty()) {
                                    playSong(yourMixSongs.first())
                                    if (isShuffleEnabled) playerViewModel.toggleShuffle()
                                }
                            },
                            modifier = Modifier.weight(1f).height(76.dp),
                            enabled = yourMixSongs.isNotEmpty(),
                            shape = RoundedCornerShape(
                                topStart = 60.dp, topEnd = 14.dp,
                                bottomStart = 60.dp, bottomEnd = 14.dp
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.common_play), modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            TightWrapText(
                                text = playItLabel,
                                modifier = Modifier.padding(end = 4.dp),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                                lineHeight = 20.sp
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                if (yourMixSongs.isNotEmpty()) {
                                    if (usesFallbackHomeMix) {
                                        playerViewModel.shuffleAllSongs(queueName = yourMixTitle)
                                    } else {
                                        playerViewModel.playSongsShuffled(
                                            songsToPlay = yourMixSongs,
                                            queueName = yourMixTitle,
                                            startAtZero = true,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).height(76.dp),
                            enabled = yourMixSongs.isNotEmpty(),
                            shape = RoundedCornerShape(
                                topStart = 14.dp, topEnd = 60.dp,
                                bottomStart = 14.dp, bottomEnd = 60.dp
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = shuffleLabel, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            TightWrapText(
                                text = shuffleLabel,
                                modifier = Modifier.padding(end = 4.dp),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }

                items(yourMixSongs, key = { it.id }) { song ->
                    EnhancedSongListItem(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        song = song,
                        isCurrentSong = stablePlayerState.currentSong?.id == song.id,
                        isPlaying = currentSongId == song.id && isPlaying,
                        onClick = { playSong(song) },
                        onMoreOptionsClick = {
                            playerViewModel.selectSongForInfo(song)
                            showSongInfoSheet = true
                        }
                    )
                }
            }
        }

        FilledIconButton(
            onClick = { navController.popBackStack() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 10.dp, top = 8.dp)
                .clip(CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.common_back)
            )
        }

        FilledIconButton(
            onClick = { if (!isRegeneratingYourMix) playerViewModel.regenerateYourMix() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .statusBarsPadding()
                .padding(end = 10.dp, top = 8.dp)
                .align(Alignment.TopEnd)
                .clip(CircleShape)
        ) {
            if (isRegeneratingYourMix) {
                ContainedLoadingIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
            } else {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.home_your_mix_action_regenerate)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(80.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.surfaceContainerLowest.copy(0.5f),
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .height(50.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceContainerLowest.copy(0.5f),
                            Color.Transparent,
                        )
                    )
                )
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveYourMixHeader(
    songs: List<Song>,
    scrollState: LazyListState,
) {
    val yourMixHeaderTitle = stringResource(R.string.home_your_mix_shelf_title)
    val albumArts = remember(songs) { songs.map { it.albumArtUriString }.distinct().take(3) }
    val totalDuration = remember(songs) { songs.sumOf { it.duration } }

    val parallaxOffset by remember { derivedStateOf { if (scrollState.firstVisibleItemIndex == 0) scrollState.firstVisibleItemScrollOffset * 0.5f else 0f } }
    val headerAlpha by remember {
        derivedStateOf { (1f - (scrollState.firstVisibleItemScrollOffset / 600f)).coerceIn(0f, 1f) }
    }

    val titleStyle = rememberYourMixScreenTitleStyle()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .graphicsLayer {
                translationY = parallaxOffset
                alpha = headerAlpha
            }
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(
                horizontalArrangement = Arrangement.spacedBy((-80).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                albumArts.forEachIndexed { index, artUrl ->
                    val size = when (index) { 0 -> 180.dp; 1 -> 220.dp; 2 -> 180.dp; else -> 150.dp }
                    val rotation = when (index) { 0 -> -15f; 1 -> 0f; 2 -> 15f; else -> 0f }
                    val shape = threeShapeSwitch(index, thirdShapeCornerRadius = 30.dp)

                    Box(
                        modifier = Modifier
                            .size(size)
                            .graphicsLayer { rotationZ = rotation }
                            .clip(shape)
                    ) {
                        SmartImage(
                            model = artUrl ?: R.drawable.rounded_album_24,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.1f),
                            Color.Transparent,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            MaterialTheme.colorScheme.surface
                        ),
                        startY = 0f,
                        endY = 900f
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = yourMixHeaderTitle,
                style = titleStyle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                modifier = Modifier.padding(start = 3.dp),
                text = pluralStringResource(
                    R.plurals.daily_mix_songs_dot_duration,
                    songs.size,
                    songs.size,
                    formatDuration(totalDuration)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun rememberYourMixScreenTitleStyle(): TextStyle {
    return remember {
        TextStyle(
            fontFamily = FontFamily(
                Font(
                    resId = R.font.gflex_variable,
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(436),
                        FontVariation.width(102f),
                        FontVariation.Setting("ROND", 100f),
                        FontVariation.Setting("XTRA", 520f),
                        FontVariation.Setting("YOPQ", 90f),
                        FontVariation.Setting("YTLC", 505f)
                    )
                )
            ),
            fontWeight = FontWeight(760),
            fontSize = 44.sp,
        )
    }
}

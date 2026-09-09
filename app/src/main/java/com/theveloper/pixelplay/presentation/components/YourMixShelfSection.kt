package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.ui.glass.glassClickable
import com.theveloper.pixelplay.ui.glass.glassPanel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Your Mix, reworked from a mosaic of independently-floating album-cover shapes into a shelf card
 * that matches [DailyMixSection]'s structure — overlapping thumbnail stack in a gradient header,
 * a short song list, one shuffle action — but pushed further on physicality: everything pops in
 * on a staggered spring overshoot instead of a flat fade, the header has its own slow color sweep
 * (same technique as [HomeGreetingCard]'s), and the shuffle button is a full circular glass FAB
 * that overlaps the header/list seam and bounces on press.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun YourMixShelfSection(
    songs: ImmutableList<Song>,
    playerViewModel: PlayerViewModel,
    isShuffleEnabled: Boolean,
    onPlayShuffled: () -> Unit,
    onSongClick: (Song) -> Unit,
    onCheckOutMix: () -> Unit,
    onNavigateToAlbum: (Song) -> Unit = {},
    onNavigateToArtist: (Song) -> Unit = {},
    onNavigateToGenre: (Song) -> Unit = {},
) {
    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showSongInfoSheet by remember { mutableStateOf(false) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        YourMixShelfCard(
            songs = songs,
            playerViewModel = playerViewModel,
            isShuffleEnabled = isShuffleEnabled,
            onPlayShuffled = onPlayShuffled,
            onMoreOptionsClick = { song ->
                playerViewModel.selectSongForInfo(song)
                showSongInfoSheet = true
            },
            onSongClick = onSongClick,
            onCheckOutMix = onCheckOutMix
        )
    }

    if (showSongInfoSheet && selectedSongForInfo != null) {
        val song = selectedSongForInfo!!
        SongInfoBottomSheet(
            song = song,
            isFavorite = favoriteSongIds.contains(song.id),
            onToggleFavorite = { playerViewModel.toggleFavoriteSpecificSong(song) },
            onDismiss = {
                showSongInfoSheet = false
                showPlaylistBottomSheet = false
            },
            onPlaySong = { onSongClick(song) },
            onAddToQueue = { playerViewModel.addSongToQueue(song) },
            onAddNextToQueue = { playerViewModel.addSongNextToQueue(song) },
            onAddToPlayList = { showPlaylistBottomSheet = true },
            onDeleteFromDevice = playerViewModel::deleteFromDevice,
            onNavigateToAlbum = {
                onNavigateToAlbum(song)
                showSongInfoSheet = false
            },
            onNavigateToArtist = {
                onNavigateToArtist(song)
                showSongInfoSheet = false
            },
            onNavigateToGenre = {
                onNavigateToGenre(song)
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
            PlaylistBottomSheet(
                playlistUiState = playlistUiState,
                songs = listOf(song),
                onDismiss = { showPlaylistBottomSheet = false },
                bottomBarHeight = bottomBarHeightDp,
                playerViewModel = playerViewModel,
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun YourMixShelfCard(
    songs: ImmutableList<Song>,
    playerViewModel: PlayerViewModel,
    isShuffleEnabled: Boolean,
    onPlayShuffled: () -> Unit,
    onMoreOptionsClick: (Song) -> Unit,
    onSongClick: (Song) -> Unit,
    onCheckOutMix: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val headerSongs = remember(songs) { songs.take(3).toImmutableList() }
    val visibleSongs = remember(songs) { songs.take(4).toImmutableList() }
    val cornerRadius = 32.dp
    val cardShape = AbsoluteSmoothCornerShape(
        cornerRadiusTL = cornerRadius,
        smoothnessAsPercentTL = 60,
        cornerRadiusTR = cornerRadius,
        smoothnessAsPercentTR = 60,
        cornerRadiusBL = cornerRadius,
        smoothnessAsPercentBL = 60,
        cornerRadiusBR = cornerRadius,
        smoothnessAsPercentBR = 60
    )

    // Bouncy pop-in on first appearance for the whole card, same overshoot feel used across Home.
    val cardEntranceScale = remember { Animatable(0.92f) }
    val cardEntranceAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch {
            cardEntranceScale.animateTo(
                1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
        }
        launch { cardEntranceAlpha.animateTo(1f, tween(280)) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardEntranceScale.value
                scaleY = cardEntranceScale.value
                alpha = cardEntranceAlpha.value
            }
    ) {
        Card(
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
            elevation = CardDefaults.elevatedCardElevation(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                YourMixShelfHeader(thumbnails = headerSongs)
                Spacer(Modifier.height(28.dp)) // room for the shuffle FAB overlapping the seam
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    visibleSongs.forEachIndexed { index, song ->
                        YourMixShelfRow(
                            song = song,
                            index = index,
                            playerViewModel = playerViewModel,
                            onClick = { onSongClick(song) },
                            onMoreOptionsClick = onMoreOptionsClick
                        )
                    }
                }
                CheckOutYourMixButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    onClick = onCheckOutMix
                )
            }
        }

        // Centered vertically within the 96dp header itself so it always reads as part of
        // the header, never straddling the seam into the song rows below.
        YourMixShuffleFab(
            isShuffleEnabled = isShuffleEnabled,
            onClick = onPlayShuffled,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 20.dp)
                .offset(y = 20.dp)
        )
    }
}

@Composable
private fun CheckOutYourMixButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.FilledTonalButton(
        modifier = modifier,
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.Transparent
        ),
        shape = AbsoluteSmoothCornerShape(
            cornerRadiusTL = 10.dp,
            cornerRadiusTR = 10.dp,
            smoothnessAsPercentTL = 70,
            smoothnessAsPercentTR = 70,
            cornerRadiusBL = 60.dp,
            cornerRadiusBR = 60.dp,
            smoothnessAsPercentBL = 70,
            smoothnessAsPercentBR = 70
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_your_mix_check_out_action),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface
            )
            Icon(
                painter = painterResource(R.drawable.rounded_arrow_forward_24),
                contentDescription = null,
                tint = colors.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun YourMixShelfHeader(thumbnails: ImmutableList<Song>) {
    val titleStyle = rememberYourMixShelfTitleStyle()
    val colors = MaterialTheme.colorScheme

    val infiniteTransition = rememberInfiniteTransition(label = "YourMixShelfSweep")
    val sweep by infiniteTransition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 6000, easing = LinearEasing)),
        label = "sweep"
    )

    // Hoisted so the drawWithCache block below isn't invalidated by a fresh list identity, and
    // so the header's static backdrop brush isn't rebuilt on every recomposition either.
    val bandColors = remember(colors) {
        listOf(
            Color.Transparent,
            colors.onPrimary.copy(alpha = 0.14f),
            Color.Transparent
        )
    }
    val headerBrush = remember(colors) {
        Brush.horizontalGradient(colors = listOf(colors.primary, colors.tertiary))
    }

    fun thumbnailModifier(index: Int): Modifier = when (index) {
        0 -> Modifier.size(52.dp).padding(top = 4.dp)
        1 -> Modifier.size(46.dp).padding(bottom = 4.dp)
        else -> Modifier.size(50.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(brush = headerBrush)
    ) {
        // Same drifting color band as HomeGreetingCard, dimmer here since it sits over a
        // stronger primary/tertiary gradient rather than a flat surface. Also built with
        // drawWithCache for the same reason: the gradient only moves, so the Brush/Shader is
        // allocated once per size change rather than once per frame for the life of the screen.
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val bandWidth = w * 0.9f // 0.45 half-width either side of centre
                    val bandBrush = Brush.linearGradient(
                        colors = bandColors,
                        start = Offset(0f, 0f),
                        end = Offset(bandWidth, h)
                    )
                    val bandSize = Size(bandWidth, h)
                    onDrawBehind {
                        translate(left = sweep * w - bandWidth * 0.5f) {
                            drawRect(brush = bandBrush, topLeft = Offset.Zero, size = bandSize)
                        }
                    }
                }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 100.dp)
                .align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_your_mix_shelf_title),
                    style = titleStyle,
                    color = colors.onPrimary
                )
                Text(
                    text = stringResource(R.string.home_your_mix_shelf_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onPrimary.copy(alpha = 0.85f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy((-16).dp)) {
                thumbnails.forEachIndexed { index, song ->
                    val entranceScale = remember(song.id) { Animatable(0f) }
                    LaunchedEffect(song.id) {
                        kotlinx.coroutines.delay(70L * index)
                        entranceScale.animateTo(
                            1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                        )
                    }
                    Box(
                        modifier = thumbnailModifier(index)
                            .graphicsLayer {
                                scaleX = entranceScale.value
                                scaleY = entranceScale.value
                            }
                            .clip(CircleShape)
                            .border(2.dp, colors.surface, CircleShape)
                    ) {
                        SmartImage(
                            model = song.albumArtUriString,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YourMixShuffleFab(
    isShuffleEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "yourMixFabPressScale"
    )

    val entranceScale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120L)
        entranceScale.animateTo(
            1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow)
        )
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .graphicsLayer {
                scaleX = entranceScale.value * pressScale
                scaleY = entranceScale.value * pressScale
            }
            .glassPanel(
                shape = CircleShape,
                color = if (isShuffleEnabled) colors.primary else colors.tertiaryContainer,
                effectScale = 0.6f,
                tintAlpha = 0.55f,
                shadow = true
            )
            .glassClickable(
                onClick = onClick,
                enabled = true,
                shape = CircleShape,
                interactionSource = interactionSource
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.rounded_shuffle_24),
            contentDescription = stringResource(R.string.home_your_mix_action_shuffle),
            tint = if (isShuffleEnabled) colors.onPrimary else colors.onTertiaryContainer,
            modifier = Modifier.size(24.dp)
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun YourMixShelfRow(
    song: Song,
    index: Int,
    playerViewModel: PlayerViewModel,
    onClick: () -> Unit,
    onMoreOptionsClick: (Song) -> Unit
) {
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val itemContainerColor = MaterialTheme.colorScheme.surfaceContainerLow

    val entranceAlpha = remember(song.id) { Animatable(0f) }
    val entranceOffset = remember(song.id) { Animatable(24f) }
    LaunchedEffect(song.id) {
        kotlinx.coroutines.delay(50L * index)
        launch { entranceAlpha.animateTo(1f, tween(260)) }
        launch {
            entranceOffset.animateTo(
                0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "yourMixRowPressScale"
    )

    EnhancedSongListItem(
        song = song,
        isCurrentSong = stablePlayerState.currentSong?.id == song.id,
        isPlaying = stablePlayerState.isPlaying && stablePlayerState.currentSong?.id == song.id,
        containerColorOverride = itemContainerColor,
        onMoreOptionsClick = onMoreOptionsClick,
        customShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        showAlbumArt = false,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entranceAlpha.value
                translationY = entranceOffset.value
                scaleX = pressScale
                scaleY = pressScale
            }
    )
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun rememberYourMixShelfTitleStyle(): TextStyle {
    return remember {
        TextStyle(
            fontFamily = FontFamily(
                Font(
                    resId = R.font.gflex_variable,
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(630),
                        FontVariation.width(136f),
                        FontVariation.grade(40),
                        FontVariation.Setting("ROND", 100f),
                        FontVariation.Setting("XTRA", 520f),
                        FontVariation.Setting("YOPQ", 90f),
                        FontVariation.Setting("YTLC", 505f)
                    )
                )
            ),
            fontWeight = FontWeight(630),
            fontSize = 20.sp,
            lineHeight = 22.sp,
            letterSpacing = (-0.35).sp
        )
    }
}

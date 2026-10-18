package com.theveloper.pixelplay.presentation.components.subcomps

import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.theveloper.pixelplay.presentation.utils.horizontalIntentSwipe
import com.theveloper.pixelplay.presentation.components.BlockedBadge
import com.theveloper.pixelplay.presentation.components.PlayBlockedSongDialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import coil.size.Size
import com.theveloper.pixelplay.data.cache.SongCacheStateCache
import com.theveloper.pixelplay.data.database.SongCacheState
import com.theveloper.pixelplay.data.database.SpotifyMatchState
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.spotify.SpotifyMatchStateCache
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.theveloper.pixelplay.presentation.components.AutoScrollingText
import com.theveloper.pixelplay.presentation.components.ShimmerBox
import androidx.compose.ui.res.stringResource
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.SmartImage

@Immutable
private data class EnhancedSongAnimationTarget(
    val isHighlighted: Boolean = false,
    val isSelected: Boolean = false
)

/**
 * Disponibilidad de una canción de streaming: primero si se puede reproducir siquiera, y si sí,
 * si está guardada para escuchar sin conexión.
 *
 * [SongCacheState.CACHED] no se muestra a propósito: esa copia la hizo sola la caché automática
 * y puede desalojarse sin avisar cuando hace falta hueco, así que enseñarla prometería una
 * disponibilidad sin conexión que no está garantizada. Solo se marca lo que el usuario pidió.
 *
 * [SpotifyMatchState.PENDING] tampoco se muestra: recién importada, TODA la biblioteca está
 * pendiente, y marcarla entera sería ruido en vez de información.
 */
@Composable
private fun SongAvailabilityBadge(matchState: Int?, offlineState: Int?, tint: Color) {
    // Sin fuente reproducible no hay nada que descargar ni que reproducir: gana sobre el resto.
    if (matchState == SpotifyMatchState.UNMATCHED) {
        Icon(
            imageVector = Icons.Rounded.CloudOff,
            contentDescription = stringResource(R.string.song_unavailable_unmatched),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(16.dp)
        )
        return
    }

    val icon = when (offlineState) {
        SongCacheState.DOWNLOADED -> Icons.Rounded.DownloadDone
        SongCacheState.DOWNLOADING -> Icons.Rounded.Downloading
        SongCacheState.FAILED -> Icons.Rounded.ErrorOutline
        else -> null
    } ?: return

    val description = when (offlineState) {
        SongCacheState.DOWNLOADED -> stringResource(R.string.song_offline_downloaded)
        SongCacheState.DOWNLOADING -> stringResource(R.string.song_offline_downloading)
        else -> stringResource(R.string.song_offline_failed)
    }

    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = if (offlineState == SongCacheState.FAILED) MaterialTheme.colorScheme.error else tint.copy(alpha = 0.7f),
        modifier = Modifier
            .padding(start = 8.dp)
            .size(16.dp)
    )
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

/**
 * Enhanced song list item with multi-selection support.
 * 
 * @param song The song to display
 * @param isPlaying Whether this song is currently playing
 * @param isCurrentSong Whether this is the current song in the queue (may be paused)
 * @param isLoading Whether to show loading shimmer state
 * @param showAlbumArt Whether to show the album art
 * @param albumArtSize Size of the album art thumbnail when shown
 * @param customShape Optional custom shape for the surface
 * @param isSelected Whether this item is selected in multi-selection mode
 * @param isSelectionMode Whether multi-selection mode is active
 * @param onLongPress Callback for long press gesture (activates selection)
 * @param onMoreOptionsClick Callback for more options button
 * @param onClick Callback for tap gesture
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedSongListItem(
    modifier: Modifier = Modifier,
    song: Song,
    isPlaying: Boolean,
    isCurrentSong: Boolean = false,
    isLoading: Boolean = false,
    showAlbumArt: Boolean = true,
    albumArtSize: Dp = 50.dp,
    customShape: androidx.compose.ui.graphics.Shape? = null,
    containerColorOverride: Color? = null,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    showMoreOptionsButton: Boolean = true,
    onLongPress: () -> Unit = {},
    onMoreOptionsClick: (Song) -> Unit,
    onClick: () -> Unit
) {
    val albumArtTargetSizePx = with(LocalDensity.current) { albumArtSize.roundToPx() }

    // Solo las canciones de streaming tienen una copia local que gestionar: un archivo local ya
    // suena sin conexión y no necesita indicador. Se filtra a la clave de ESTA canción y se
    // aplica distinctUntilChanged para que un cambio en otra canción no recomponga toda la lista.
    val spotifyId = song.spotifyId
    // Las canciones locales no lanzan ningún colector (antes eran dos corrutinas por fila).
    val offlineState = if (spotifyId == null) null else remember(spotifyId) {
        SongCacheStateCache.states.map { it[spotifyId] }.distinctUntilChanged()
    }.collectAsState(initial = null).value

    // Una pista sin vídeo emparejado no se puede reproducir en absoluto, así que pesa más que
    // cualquier estado de descarga. Hasta ahora esto solo se veía como un contador global en el
    // panel de Spotify: no había forma de saber, mirando la lista, CUÁL canción era la que fallaba.
    val matchState = if (spotifyId == null) null else remember(spotifyId) {
        SpotifyMatchStateCache.states.map { it[spotifyId] }.distinctUntilChanged()
    }.collectAsState(initial = null).value

    // Blocked artist: the row stays visible but dimmed with a badge, and a tap offers "Play anyway".
    val blockedUi = com.theveloper.pixelplay.presentation.utils.LocalBlockedArtistsUi.current
    val isBlockedSong = remember(song.id, song.artist, song.artistId, blockedUi?.index) { blockedUi?.index?.isBlocked(song) == true }
    val isBlockedSongState = androidx.compose.runtime.rememberUpdatedState(isBlockedSong)
    var showPlayAnywayDialog by remember { mutableStateOf(false) }

    // Row swipe (Settings > Playback > Gestures): right = Play next, left = Add to queue. Off by default.
    val rowGestures = com.theveloper.pixelplay.presentation.utils.LocalPlayerGestures.current
    val gestureHost = com.theveloper.pixelplay.presentation.utils.LocalPlayerGestureHost.current
    val rowSwipeEnabled = rowGestures.rowSwipeQueue && gestureHost != null && !isSelectionMode && !isLoading
    var rowSwipeDx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val latestSong by androidx.compose.runtime.rememberUpdatedState(song)
    val toastPlayingNext = stringResource(R.string.library_toast_playing_next)
    val toastAddedToQueue = stringResource(R.string.library_toast_added_to_queue)
    val toastAlreadyQueued = stringResource(R.string.row_swipe_already_in_queue)
    val queueSong: (Boolean) -> Unit = { playNext ->
        val host = gestureHost
        if (host != null) {
            val added = host.queueSongIfAbsent(latestSong, playNext)
            host.showToast(
                when {
                    !added -> toastAlreadyQueued
                    playNext -> toastPlayingNext
                    else -> toastAddedToQueue
                }
            )
        }
    }
    val rowHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val actionLabelPlayNext = stringResource(R.string.row_action_play_next)
    val actionLabelAddToQueue = stringResource(R.string.row_action_add_to_queue)

    val isHighlighted = isCurrentSong && !isLoading
    val transition = updateTransition(
        targetState = EnhancedSongAnimationTarget(
            isHighlighted = isHighlighted,
            isSelected = isSelected
        ),
        label = "EnhancedSongListItemTransition"
    )

    // Share one transition across the item and derive the visual properties from a
    // couple of progress values instead of animating each color/radius independently.
    val highlightProgress by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 400) },
        label = "highlightProgress"
    ) { state ->
        if (state.isHighlighted) 1f else 0f
    }
    val selectionVisualProgress by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 250) },
        label = "selectionVisualProgress"
    ) { state ->
        if (state.isSelected) 1f else 0f
    }
    val selectionScaleProgress by transition.animateFloat(
        transitionSpec = {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        },
        label = "selectionScaleProgress"
    ) { state ->
        if (state.isSelected) 1f else 0f
    }

    val animatedCornerRadius = lerpDp(22.dp, 50.dp, highlightProgress)
    val animatedAlbumCornerRadius = lerpDp(10.dp, 50.dp, highlightProgress)
    val selectionScale = lerpFloat(1f, 0.98f, selectionScaleProgress)
    val selectionBorderWidth = lerpDp(0.dp, 2.5.dp, selectionVisualProgress)

    val surfaceShape = remember(animatedCornerRadius, customShape, isHighlighted) {
        if (customShape != null && !isHighlighted) {
            customShape
        } else {
            RoundedCornerShape(animatedCornerRadius)
        }
    }

    val albumShape = remember(animatedAlbumCornerRadius) {
        RoundedCornerShape(animatedAlbumCornerRadius)
    }

    val colors = MaterialTheme.colorScheme
    val baseContainerColor = containerColorOverride ?: colors.surfaceContainerLow
    val playbackContainerColor =
        lerpColor(baseContainerColor, colors.primaryContainer, highlightProgress)
    val containerColor = lerpColor(playbackContainerColor, colors.secondaryContainer, selectionVisualProgress)

    val baseContentColor = colors.onSurface
    val playbackContentColor =
        lerpColor(baseContentColor, colors.onPrimaryContainer, highlightProgress)
    val contentColor = lerpColor(playbackContentColor, colors.onSecondaryContainer, selectionVisualProgress)

    val selectionBorderColor = lerpColor(colors.primary.copy(alpha = 0f), colors.primary, selectionVisualProgress)
    val mvContainerColor = lerpColor(colors.onSurface, colors.primaryContainer, highlightProgress)
    val mvContentColor = lerpColor(colors.surfaceContainerHigh, colors.onPrimaryContainer, highlightProgress)
    val selectionOverlayColor = lerpColor(
        Color.Transparent,
        colors.primary.copy(alpha = 0.7f),
        selectionVisualProgress
    )
    val selectionOverlayContentColor = lerpColor(
        Color.Transparent,
        colors.onPrimary,
        selectionVisualProgress
    )
    val showSelectionDecoration = selectionVisualProgress > 0.001f

    if (showPlayAnywayDialog) {
        PlayBlockedSongDialog(
            song = song,
            onPlayAnyway = {
                showPlayAnywayDialog = false
                blockedUi?.allowOnce?.invoke(song.id)
                onClick()
            },
            onDismiss = { showPlayAnywayDialog = false }
        )
    }

    if (isLoading) {
        // Shimmer Placeholder Layout
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .clip(surfaceShape),
            shape = surfaceShape,
            color = containerColorOverride ?: colors.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if(showAlbumArt) {
                    ShimmerBox(
                        modifier = Modifier
                            .size(albumArtSize)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if(showAlbumArt) 0.dp else 4.dp)
                ) {
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.3f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                ShimmerBox(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                )
            }
        }
    } else {
        // Actual Song Item Layout
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .scale(selectionScale)
                .clip(surfaceShape)
                .then(
                    if (showSelectionDecoration) {
                        Modifier.border(
                            width = selectionBorderWidth,
                            color = selectionBorderColor,
                            shape = surfaceShape
                        )
                    } else {
                        Modifier
                    }
                )
                .then(if (isBlockedSong) Modifier.graphicsLayer { alpha = 0.5f } else Modifier)
                .then(if (rowSwipeEnabled) Modifier.graphicsLayer { translationX = rowSwipeDx } else Modifier)
                .horizontalIntentSwipe(
                    enabled = rowSwipeEnabled,
                    threshold = 72.dp,
                    onDrag = { dx -> rowSwipeDx = (dx / 3f).coerceIn(-96f, 96f) },
                    onCancel = { rowSwipeDx = 0f },
                    onCommit = { direction ->
                        rowSwipeDx = 0f
                        rowHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        queueSong(direction > 0)
                    }
                )
                .then(
                    if (rowSwipeEnabled) {
                        Modifier.semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(actionLabelPlayNext) { queueSong(true); true },
                                CustomAccessibilityAction(actionLabelAddToQueue) { queueSong(false); true }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .pointerInput(isSelectionMode) {
                    detectTapGestures(
                        onTap = { 
                            if (isSelectionMode) {
                                // In selection mode, tap toggles selection
                                onLongPress()
                            } else {
                                if (isBlockedSongState.value) showPlayAnywayDialog = true else onClick()
                            }
                        },
                        onLongPress = {
                            // Long press always activates/toggles selection
                            onLongPress()
                        }
                    )
                },
            shape = surfaceShape,
            color = containerColor,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showAlbumArt) {
                    Box(
                        modifier = Modifier
                            .size(albumArtSize)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    ) {
                        SmartImage(
                            model = song.albumArtUriString,
                            contentDescription = song.title,
                            shape = albumShape,
                            targetSize = Size(albumArtTargetSizePx, albumArtTargetSizePx),
                            modifier = Modifier.fillMaxSize()
                        )
                        
                        // Selection check overlay on album art
                        if (showSelectionDecoration) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        color = selectionOverlayColor,
                                        shape = albumShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selectionIndex != null && selectionIndex >= 0) {
                                    Text(
                                        text = selectionIndex.toString(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = selectionOverlayContentColor
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = stringResource(R.string.common_selected),
                                        tint = selectionOverlayContentColor,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                } else {
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                ) {
                    if (isHighlighted && !isSelectionMode) {
                        AutoScrollingText(
                            text = song.title,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            gradientEdgeColor = containerColor,
                        )

                    } else {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            color = contentColor,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = song.displayArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                val showPlayingIndicator = isCurrentSong && !isSelectionMode
                val showTrailingAction = showMoreOptionsButton && !isSelectionMode

                if (isBlockedSong && !isSelectionMode) {
                    BlockedBadge(modifier = Modifier.padding(start = 8.dp))
                }

                if (!isSelectionMode) {
                    SongAvailabilityBadge(
                        matchState = matchState,
                        offlineState = offlineState,
                        tint = contentColor
                    )
                }

                if (showPlayingIndicator) {
                     PlayingEqIcon(
                         modifier = Modifier
                             .padding(start = 8.dp)
                             .size(width = 18.dp, height = 16.dp),
                         color = contentColor,
                         isPlaying = isPlaying
                     )
                }

                if (showPlayingIndicator || showTrailingAction) {
                    Spacer(modifier = Modifier.width(12.dp))
                }

                if (showTrailingAction) {
                    FilledIconButton(
                        onClick = { onMoreOptionsClick(song) },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = mvContentColor,
                            contentColor = mvContainerColor
                        ),
                        modifier = Modifier
                            .size(36.dp)
                            .padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.common_more_options_for, song.title),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

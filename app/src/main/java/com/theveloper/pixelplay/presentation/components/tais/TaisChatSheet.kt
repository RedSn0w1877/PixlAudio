package com.theveloper.pixelplay.presentation.components.tais

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.tais.dj.DjRouteResult
import com.theveloper.pixelplay.presentation.viewmodel.TaisChatMessage
import com.theveloper.pixelplay.presentation.viewmodel.TaisChatViewModel

/**
 * Taizo — TAIS Engine 3's chat persona. A Material 3 bottom sheet backed by [TaisChatViewModel] /
 * [com.theveloper.pixelplay.data.tais.dj.TaisDjEngine]: a play/queue/find prompt resolves to a
 * [DjRouteResult] with cover-art song rows you can tap to play instantly (or bulk "Play
 * Queue"/"Add to Queue"); anything else is a real question, answered conversationally by whichever
 * AI provider is configured in Settings → AI Integration. Spotify (online) results go through the
 * same tap-to-play/bulk actions as offline ones — [TaisChatViewModel.resolveOnlineTracks] imports
 * them via the same browse-import-then-match pipeline the Spotify browse tab uses
 * (`presentation/spotify/browse`, see CLAUDE.md) before handing them to [onPlaySongs]/[onQueueSongs].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaisChatSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    onPlaySongs: (List<Song>) -> Unit,
    onQueueSongs: (List<Song>) -> Unit,
    viewModel: TaisChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isResolvingOnlineTracks by viewModel.isResolvingOnlineTracks.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    val sparkleBrush = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 620.dp)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(sparkleBrush, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = "Taizo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Your on-device AI DJ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (uiState.messages.isEmpty()) {
                TaizoEmptyState(
                    sparkleBrush = sparkleBrush,
                    modifier = Modifier.weight(1f),
                    onSuggestionClick = { suggestion ->
                        viewModel.onInputChange(suggestion)
                        viewModel.sendPrompt()
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        TaisChatMessageRow(
                            message = message,
                            sparkleBrush = sparkleBrush,
                            onPlaySongs = onPlaySongs,
                            onQueueSongs = onQueueSongs,
                            isResolvingOnlineTracks = isResolvingOnlineTracks,
                            onPlayOnlineTracks = { tracks -> viewModel.resolveOnlineTracks(tracks, onPlaySongs) },
                            onQueueOnlineTracks = { tracks -> viewModel.resolveOnlineTracks(tracks, onQueueSongs) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiState.inputText,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask Taizo anything, or a mood/genre to play…") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = viewModel::sendPrompt,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private val TAIZO_SUGGESTIONS = listOf(
    "Chill acoustic", "Energetic rock", "Sad indie", "Party anthems", "Focus beats", "Feel-good pop"
)

/** Fills the sheet before the first prompt — a big avatar, a welcome line, and tappable suggestions, instead of dead space. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaizoEmptyState(
    sparkleBrush: Brush,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(sparkleBrush, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Hey, I'm Taizo",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Tell me a mood or genre and I'll build you a queue, or just ask me anything " +
                "about music — an artist, a song, recommendations, whatever's on your mind.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            TAIZO_SUGGESTIONS.forEach { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestionClick("Play some $suggestion songs") },
                    label = { Text(suggestion) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            }
        }
    }
}

@Composable
private fun TaizoAvatar(sparkleBrush: Brush) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(sparkleBrush, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun TaisChatMessageRow(
    message: TaisChatMessage,
    sparkleBrush: Brush,
    onPlaySongs: (List<Song>) -> Unit,
    onQueueSongs: (List<Song>) -> Unit,
    isResolvingOnlineTracks: Boolean,
    onPlayOnlineTracks: (List<SpotifyTrack>) -> Unit,
    onQueueOnlineTracks: (List<SpotifyTrack>) -> Unit
) {
    when (message) {
        is TaisChatMessage.User -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        is TaisChatMessage.Thinking -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TaizoAvatar(sparkleBrush)
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    ThinkingDots()
                }
            }
        }

        is TaisChatMessage.TextReply -> {
            Row(verticalAlignment = Alignment.Top) {
                TaizoAvatar(sparkleBrush)
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
                        .background(
                            if (message.isError) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (message.isError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        is TaisChatMessage.DjReply -> {
            Row(verticalAlignment = Alignment.Top) {
                TaizoAvatar(sparkleBrush)
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(14.dp)
                ) {
                    Column {
                        when (val result = message.result) {
                            is DjRouteResult.Offline -> {
                                Text(
                                    text = message.aiIntro ?: "Found ${result.songs.size} songs in your library.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = { onPlaySongs(result.songs) },
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Play Queue")
                                    }
                                    FilledTonalButton(
                                        onClick = { onQueueSongs(result.songs) },
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add to Queue")
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    result.songs.take(8).forEach { song ->
                                        TaizoSongRow(
                                            song = song,
                                            onClick = { onPlaySongs(listOf(song)) }
                                        )
                                    }
                                }
                            }

                            is DjRouteResult.Online -> {
                                Text(
                                    text = message.aiIntro ?: "Found ${result.tracks.size} tracks on Spotify.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = { onPlayOnlineTracks(result.tracks) },
                                        enabled = !isResolvingOnlineTracks,
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Play Queue")
                                    }
                                    FilledTonalButton(
                                        onClick = { onQueueOnlineTracks(result.tracks) },
                                        enabled = !isResolvingOnlineTracks,
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add to Queue")
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    result.tracks.take(8).forEach { track ->
                                        TaizoOnlineTrackRow(
                                            track = track,
                                            enabled = !isResolvingOnlineTracks,
                                            onClick = { onPlayOnlineTracks(listOf(track)) }
                                        )
                                    }
                                }
                            }

                            DjRouteResult.NoResults -> {
                                Text(
                                    text = "Couldn't find anything for that — try a different genre or mood.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One song result: cover art + title/artist, tap anywhere to play it immediately — the "1-tap play" from a Taizo result. */
@Composable
private fun TaizoSongRow(song: Song, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.theveloper.pixelplay.presentation.components.SmartImage(
            model = song.albumArtUriString,
            contentDescription = null,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = song.displayArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = "Play ${song.title}",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * One Spotify search result row — same shape as [TaizoSongRow], tap to import + play. [enabled]
 * disables the row while an import is already in flight, so a rapid double-tap can't fire two
 * imports of the same track.
 */
@Composable
private fun TaizoOnlineTrackRow(track: SpotifyTrack, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.theveloper.pixelplay.presentation.components.SmartImage(
            model = track.album?.images?.firstOrNull()?.url,
            contentDescription = null,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = track.artists?.joinToString { it.name.orEmpty() }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = "Play ${track.name.orEmpty()}",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Three dots pulsing out of phase — Taizo's "typing" indicator while a prompt resolves. */
@Composable
private fun ThinkingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "taizoThinking")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val phase by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing)
                ),
                label = "dot$index"
            )
            val local = (phase + index * 0.33f) % 1f
            val alpha = 0.3f + 0.7f * kotlin.math.sin(local * Math.PI).toFloat().coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape)
            )
        }
    }
}

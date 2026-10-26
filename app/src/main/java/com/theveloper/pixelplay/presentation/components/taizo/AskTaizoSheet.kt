package com.theveloper.pixelplay.presentation.components.taizo

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoPlayer
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.tais.dj.DjRouteResult
import com.theveloper.pixelplay.presentation.components.AdaptiveModalBottomSheet
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.viewmodel.AskTaizoViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.TaizoChatItem
import com.theveloper.pixelplay.presentation.viewmodel.TaizoMode

/**
 * Ask Taizo, the assistant chat (replaces the TAIS DJ chat sheet). Opened from Home, the full
 * player's sparkles button and Settings › AI features. Answers stream in; tool activity (web
 * search, library search...) shows under the bubble; cited pages show as chips; actions that
 * replace the queue or build a very large playlist wait for a tap on a confirmation card.
 * Material 3 Expressive, existing components only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskTaizoSheet(
    playerViewModel: PlayerViewModel,
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    viewModel: AskTaizoViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current

    val player = remember(playerViewModel) { PlayerViewModelTaizoPlayer(playerViewModel) }
    DisposableEffect(player) {
        viewModel.attachPlayer(player)
        viewModel.refreshMode()
        onDispose { viewModel.detachPlayer(player) }
    }

    // Follow the newest row, and the answer as it grows.
    val lastItem = uiState.items.lastOrNull()
    val lastTextLength = (lastItem as? TaizoChatItem.Assistant)?.text?.length ?: 0
    LaunchedEffect(uiState.items.size, lastTextLength / 120) {
        if (uiState.items.isNotEmpty()) listState.animateScrollToItem(uiState.items.lastIndex)
    }

    val sparklePrimary = MaterialTheme.colorScheme.primary
    val sparkleTertiary = MaterialTheme.colorScheme.tertiary
    val sparkleBrush = remember(sparklePrimary, sparkleTertiary) {
        Brush.linearGradient(listOf(sparklePrimary, sparkleTertiary))
    }

    val playSongs: (List<Song>) -> Unit = { songs ->
        songs.firstOrNull()?.let { playerViewModel.playSongs(songs, it, queueName = "Taizo") }
    }
    val queueSongs: (List<Song>) -> Unit = { songs -> songs.forEach { playerViewModel.addSongToQueue(it) } }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            TaizoHeader(
                mode = uiState.mode,
                ownCloudName = uiState.ownCloudName,
                sparkleBrush = sparkleBrush,
                recentChats = uiState.recentChats.map { it.id to it.title },
                onOpenChat = viewModel::openChat,
                onNewChat = viewModel::newChat,
            )
            Spacer(Modifier.height(12.dp))

            if (uiState.items.isEmpty()) {
                TaizoEmptyState(
                    sparkleBrush = sparkleBrush,
                    modifier = Modifier.weight(1f),
                    onSuggestion = { viewModel.send(it) },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(uiState.items, key = { it.id }, contentType = { it::class }) { item ->
                        when (item) {
                            is TaizoChatItem.User -> UserBubble(item.text)
                            is TaizoChatItem.Assistant -> AssistantBubble(
                                item = item,
                                sparkleBrush = sparkleBrush,
                                onOpenSource = { url -> runCatching { uriHandler.openUri(url) } },
                            )
                            is TaizoChatItem.Media -> MediaBubble(
                                item = item,
                                sparkleBrush = sparkleBrush,
                                busy = uiState.isResolvingTracks,
                                onPlaySongs = playSongs,
                                onQueueSongs = queueSongs,
                                onPlayTracks = { tracks -> viewModel.resolveOnlineTracks(tracks, playSongs) },
                                onQueueTracks = { tracks -> viewModel.resolveOnlineTracks(tracks, queueSongs) },
                            )
                            is TaizoChatItem.Confirm -> ConfirmCard(
                                item = item,
                                onAnswer = { accepted -> viewModel.answerConfirmation(item.id, accepted) },
                            )
                            is TaizoChatItem.Note -> NoteRow(stringResource(item.textRes))
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = uiState.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.taizo_input_hint)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                if (uiState.isResponding) {
                    FilledIconButton(
                        onClick = viewModel::stop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.taizo_stop))
                    }
                } else {
                    FilledIconButton(
                        onClick = { viewModel.send() },
                        enabled = uiState.input.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.taizo_send))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TaizoHeader(
    mode: TaizoMode,
    ownCloudName: String?,
    sparkleBrush: Brush,
    recentChats: List<Pair<String, String>>,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    var showRecent by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TaizoAvatar(sparkleBrush, size = 40)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.taizo_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when (mode) {
                    TaizoMode.CLAUDE -> stringResource(R.string.taizo_subtitle_cloud)
                    TaizoMode.ON_DEVICE -> stringResource(R.string.taizo_subtitle_on_device)
                    TaizoMode.OWN_CLOUD -> ownCloudName ?: stringResource(R.string.taizo_subtitle_cloud)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { showRecent = true }) {
                Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.taizo_recent_chats))
            }
            DropdownMenu(expanded = showRecent, onDismissRequest = { showRecent = false }) {
                if (recentChats.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.taizo_no_recent_chats)) },
                        onClick = { showRecent = false },
                        enabled = false,
                    )
                }
                recentChats.forEach { (id, title) ->
                    DropdownMenuItem(
                        text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            showRecent = false
                            onOpenChat(id)
                        },
                    )
                }
            }
        }
        IconButton(onClick = onNewChat) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.taizo_new_chat))
        }
    }
}

private val TAIZO_SUGGESTIONS = listOf(
    "What's this song about?",
    "Play something like this",
    "What have I been listening to this week?",
    "Make me a chill evening playlist",
    "Any news about this artist?",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaizoEmptyState(
    sparkleBrush: Brush,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TaizoAvatar(sparkleBrush, size = 72)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.taizo_empty_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.taizo_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Spacer(Modifier.height(24.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            TAIZO_SUGGESTIONS.forEach { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestion(suggestion) },
                    label = { Text(suggestion) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TaizoAvatar(sparkleBrush: Brush, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(sparkleBrush, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

private val assistantShape = RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantBubble(
    item: TaizoChatItem.Assistant,
    sparkleBrush: Brush,
    onOpenSource: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        TaizoAvatar(sparkleBrush, size = 28)
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(assistantShape)
                .background(
                    if (item.isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .animateContentSize()
        ) {
            val textColor = if (item.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
            if (item.text.isNotBlank()) {
                val suffix = item.suffixRes?.let { " " + stringResource(it) }.orEmpty()
                Text(
                    text = remember(item.text, suffix) { lightMarkdown(item.text + suffix) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
            }
            if (item.streaming) {
                if (item.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThinkingDots()
                    item.activityRes?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (item.sources.isNotEmpty() && !item.streaming) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.taizo_sources),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.sources.take(MAX_SOURCE_CHIPS).forEach { source ->
                        AssistChip(
                            onClick = { onOpenSource(source.url) },
                            label = {
                                Text(
                                    text = source.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp),
                                )
                            },
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_SOURCE_CHIPS = 6

@Composable
private fun ConfirmCard(item: TaizoChatItem.Confirm, onAnswer: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(14.dp)
    ) {
        Text(
            text = item.question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.height(10.dp))
        when (item.answer) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onAnswer(true) }, shape = RoundedCornerShape(20.dp)) {
                    Text(stringResource(R.string.taizo_confirm_yes))
                }
                OutlinedButton(onClick = { onAnswer(false) }, shape = RoundedCornerShape(20.dp)) {
                    Text(stringResource(R.string.taizo_confirm_no))
                }
            }
            true -> Text(stringResource(R.string.taizo_confirmed), style = MaterialTheme.typography.labelLarge)
            false -> Text(stringResource(R.string.taizo_declined), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun NoteRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
    )
}

@Composable
private fun MediaBubble(
    item: TaizoChatItem.Media,
    sparkleBrush: Brush,
    busy: Boolean,
    onPlaySongs: (List<Song>) -> Unit,
    onQueueSongs: (List<Song>) -> Unit,
    onPlayTracks: (List<SpotifyTrack>) -> Unit,
    onQueueTracks: (List<SpotifyTrack>) -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        TaizoAvatar(sparkleBrush, size = 28)
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(assistantShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(14.dp)
        ) {
            when (val result = item.result) {
                is DjRouteResult.Offline -> {
                    Text(
                        text = item.intro ?: stringResource(R.string.taizo_found_library, result.songs.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    MediaActions(enabled = true, onPlay = { onPlaySongs(result.songs) }, onQueue = { onQueueSongs(result.songs) })
                    Spacer(Modifier.height(10.dp))
                    result.songs.take(8).forEach { song ->
                        ResultRow(
                            art = song.albumArtUriString,
                            title = song.title,
                            subtitle = song.displayArtist,
                            enabled = true,
                            onClick = { onPlaySongs(listOf(song)) },
                        )
                    }
                }
                is DjRouteResult.Online -> {
                    Text(
                        text = item.intro ?: stringResource(R.string.taizo_found_spotify, result.tracks.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    MediaActions(enabled = !busy, onPlay = { onPlayTracks(result.tracks) }, onQueue = { onQueueTracks(result.tracks) })
                    Spacer(Modifier.height(10.dp))
                    result.tracks.take(8).forEach { track ->
                        ResultRow(
                            art = track.album?.images?.firstOrNull()?.url,
                            title = track.name.orEmpty(),
                            subtitle = track.artists?.joinToString { it.name.orEmpty() }.orEmpty(),
                            enabled = !busy,
                            onClick = { onPlayTracks(listOf(track)) },
                        )
                    }
                }
                DjRouteResult.NoResults -> Text(
                    text = stringResource(R.string.taizo_found_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MediaActions(enabled: Boolean, onPlay: () -> Unit, onQueue: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onPlay, enabled = enabled, shape = RoundedCornerShape(20.dp)) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.taizo_play_all))
        }
        FilledTonalButton(onClick = onQueue, enabled = enabled, shape = RoundedCornerShape(20.dp)) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.taizo_queue_all))
        }
    }
}

@Composable
private fun ResultRow(art: String?, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmartImage(
            model = art,
            contentDescription = null,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
    }
}

/** Three dots pulsing out of phase; animated at draw time only, so nothing recomposes per frame. */
@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "taizoThinking")
    val dotColor = MaterialTheme.colorScheme.primary
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900, easing = LinearEasing)),
        label = "dots",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .drawWithCache {
                        val outline = CircleShape.createOutline(size, layoutDirection, this)
                        onDrawBehind {
                            val local = (phase.value + index * 0.33f) % 1f
                            val alpha = 0.3f + 0.7f * kotlin.math.sin(local * Math.PI).toFloat().coerceIn(0f, 1f)
                            drawOutline(outline, dotColor.copy(alpha = alpha))
                        }
                    }
            )
        }
    }
}

/** `**bold**` spans and "- " / "* " bullets; everything else is plain text. */
internal fun lightMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.lines()
    lines.forEachIndexed { lineIndex, rawLine ->
        val line = when {
            rawLine.startsWith("- ") || rawLine.startsWith("* ") -> "• " + rawLine.substring(2)
            rawLine.startsWith("#") -> rawLine.trimStart('#').trimStart()
            else -> rawLine
        }
        var i = 0
        while (i < line.length) {
            val start = line.indexOf("**", i)
            if (start < 0) {
                append(line.substring(i))
                break
            }
            val end = line.indexOf("**", start + 2)
            if (end < 0) {
                append(line.substring(i))
                break
            }
            append(line.substring(i, start))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line.substring(start + 2, end)) }
            i = end + 2
        }
        if (lineIndex < lines.lastIndex) append('\n')
    }
}

/** [TaizoPlayer] over PlayerViewModel's own calls, so the assistant plays exactly like the UI does. */
class PlayerViewModelTaizoPlayer(private val playerViewModel: PlayerViewModel) : TaizoPlayer {
    override fun currentSong(): Song? = playerViewModel.stablePlayerState.value.currentSong
    override fun positionMs(): Long = playerViewModel.currentPlaybackPosition.value
    override fun isPlaying(): Boolean = playerViewModel.stablePlayerState.value.isPlaying
    override fun queue(): List<Song> = playerViewModel.playerUiState.value.currentPlaybackQueue
    override fun currentIndex(): Int {
        val state = playerViewModel.stablePlayerState.value
        val queue = playerViewModel.playerUiState.value.currentPlaybackQueue
        val byId = state.currentSong?.let { song -> queue.indexOfFirst { it.id == song.id } } ?: -1
        return if (byId >= 0) byId else state.currentMediaItemIndex
    }

    override fun playSongs(songs: List<Song>, startSong: Song, queueName: String) =
        playerViewModel.playSongs(songs, startSong, queueName = queueName)

    override fun addToQueue(songs: List<Song>, playNext: Boolean) {
        // "Play next" inserts each right after the current song, so go backwards to keep their order.
        if (playNext) songs.asReversed().forEach { playerViewModel.addSongNextToQueue(it) }
        else songs.forEach { playerViewModel.addSongToQueue(it) }
    }

    override fun play() {
        if (!playerViewModel.stablePlayerState.value.playWhenReady) playerViewModel.playPause()
    }

    override fun pause() {
        if (playerViewModel.stablePlayerState.value.playWhenReady) playerViewModel.playPause()
    }

    override fun next() = playerViewModel.nextSong()
    override fun previous() = playerViewModel.previousSong()
    override fun seekTo(positionMs: Long) = playerViewModel.seekTo(positionMs)
    override fun singActive(): Boolean = playerViewModel.studioInstrumentalActive.value
    override fun instrumentalReady(): Boolean = playerViewModel.studioInstrumentalAvailable.value
    override fun toggleSing() = playerViewModel.toggleSing()
}

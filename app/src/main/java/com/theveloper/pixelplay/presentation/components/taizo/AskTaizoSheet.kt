package com.theveloper.pixelplay.presentation.components.taizo

import androidx.compose.animation.animateContentSize
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.toShape
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.presentation.components.VoiceSearchAvailability
import com.theveloper.pixelplay.presentation.components.redesign.BadgeShape
import com.theveloper.pixelplay.presentation.components.redesign.RedesignHeaderButton
import com.theveloper.pixelplay.presentation.components.redesign.RedesignType
import com.theveloper.pixelplay.presentation.components.redesign.ShapeBadge
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.theveloper.pixelplay.presentation.viewmodel.HostChip
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
    /** Opened from "Ask the host" of Endless Listening: the host's chips show above the input and typed text carries the host's addendum. */
    hostMode: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current

    DisposableEffect(hostMode, viewModel) {
        viewModel.setDjMode(hostMode)
        onDispose { viewModel.setDjMode(false) }
    }
    // Opened from the station screen's quick asks or its mic (redesign batch 3): the chip is sent, or speech starts, once.
    val endless = playerViewModel.brainSession.endless
    val pendingChip = remember(hostMode) { if (hostMode) endless.takePendingChip() else null }
    val startVoice = remember(hostMode) { hostMode && endless.takePendingVoice() }
    LaunchedEffect(pendingChip) { pendingChip?.let(viewModel::hostChip) }
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
          CompositionLocalProvider(LocalTaizoHost provides hostMode) {
            if (hostMode) {
                HostHeader(onClose = onDismissRequest)
                Spacer(Modifier.height(16.dp))
                HostNowPlaying(playerViewModel)
                Spacer(Modifier.height(16.dp))
            } else {
                TaizoHeader(
                    mode = uiState.mode,
                    ownCloudName = uiState.ownCloudName,
                    sparkleBrush = sparkleBrush,
                    recentChats = uiState.recentChats.map { it.id to it.title },
                    onOpenChat = viewModel::openChat,
                    onNewChat = viewModel::newChat,
                )
                Spacer(Modifier.height(12.dp))
            }

            if (uiState.items.isEmpty() && hostMode) {
                HostEmptyState(Modifier.weight(1f))
            } else if (uiState.items.isEmpty()) {
                TaizoEmptyState(
                    sparkleBrush = sparkleBrush,
                    modifier = Modifier.weight(1f),
                    onSuggestion = { viewModel.send(it) },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
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
                            is TaizoChatItem.Mix -> MixCardBubble(
                                item = item,
                                onPlay = { viewModel.playMix(item.id) },
                                onSave = { viewModel.saveMix(item.id) },
                                onChip = { chip -> viewModel.applyMixChip(item.id, chip) },
                            )
                            is TaizoChatItem.Less -> LessChipRow(
                                item = item,
                                onTap = { viewModel.tapLess(item.id) },
                                onUndo = { viewModel.undoLess(item.id) },
                            )
                        }
                    }
                }
            }

            if (hostMode) {
                Spacer(Modifier.height(8.dp))
                HostChips(enabled = !uiState.isResponding, onChip = viewModel::hostChip)
            }
            Spacer(Modifier.height(10.dp))
            TaizoInputPills(
                input = uiState.input,
                onInputChange = viewModel::onInputChange,
                isResponding = uiState.isResponding,
                onSend = { viewModel.send() },
                onSendText = { viewModel.send(it) },
                onStop = viewModel::stop,
                onClose = onDismissRequest,
                hostMode = hostMode,
                startVoice = startVoice,
            )
            Spacer(Modifier.height(12.dp))
          }
        }
    }
}

/*
 * Ask Taizo, redesign 2 (project/AndroidAskTaizo.dc.html):
 *   header   52 dp Sunny-shaped avatar (secondaryContainer, sparkle in onSecondaryContainer), 12 dp,
 *            "Taizo" 28/34 sp bold (-0.3 sp) over the mode line 14/20 onSurfaceVariant; Recent chats
 *            and New chat as 48 dp / 16 dp-corner surfaceContainerHigh buttons.
 *   user     primaryContainer bubble, 24/24/6/24 corners, 16/12 dp padding, 16/22 sp, max 290 dp.
 *   Taizo    28 dp avatar + "Taizo" 14/20 medium onSurfaceVariant, then the answer as plain 16/24 sp
 *            text (no bubble; errors keep an errorContainer bubble).
 *   input    the nav's split pills: a 68 dp round Close pill (surfaceContainerHigh) and, 10 dp away,
 *            a 68 dp primaryContainer pill with a 22 dp sparkle, the field (15/20 sp) and a 46 dp
 *            onPrimaryContainer circle: mic when empty (if the phone can listen), send, or stop.
 */
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
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TaizoShapeAvatar(size = 52)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.taizo_name),
                style = RedesignType.CompactTitle,
                color = scheme.onSurface,
            )
            Text(
                text = when (mode) {
                    TaizoMode.CLAUDE -> stringResource(R.string.taizo_subtitle_cloud)
                    TaizoMode.ON_DEVICE -> stringResource(R.string.taizo_subtitle_on_device)
                    TaizoMode.OWN_CLOUD -> ownCloudName ?: stringResource(R.string.taizo_subtitle_cloud)
                },
                style = RedesignType.Label.copy(fontWeight = FontWeight.Normal),
                color = scheme.onSurfaceVariant,
            )
        }
        Box {
            RedesignHeaderButton(
                icon = rememberVectorPainter(Icons.Rounded.History),
                contentDescription = stringResource(R.string.taizo_recent_chats),
                onClick = { showRecent = true },
                containerColor = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurface,
                iconSize = 22.dp,
            )
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
        RedesignHeaderButton(
            icon = rememberVectorPainter(Icons.Rounded.EditNote),
            contentDescription = stringResource(R.string.taizo_new_chat),
            onClick = onNewChat,
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            iconSize = 22.dp,
        )
    }
}

/** Taizo's mark: the sparkle on an expressive Sunny shape (52 dp in the header, 28 dp by answers). */
@Composable
internal fun TaizoShapeAvatar(size: Int) {
    if (LocalTaizoHost.current) {
        com.theveloper.pixelplay.presentation.screens.HostBadge(size.dp)
        return
    }
    ShapeBadge(
        icon = rememberVectorPainter(Icons.Rounded.AutoAwesome),
        shape = BadgeShape.Sunny,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
        size = size.dp,
        iconSize = (size * 0.46f).dp,
    )
}

/** The split-pill input (see the header comment for the numbers). */
@Composable
private fun TaizoInputPills(
    input: String,
    onInputChange: (String) -> Unit,
    isResponding: Boolean,
    onSend: () -> Unit,
    onSendText: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    hostMode: Boolean = false,
    startVoice: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    // Host mode wears the station's colours (tertiary) and its left pill goes back to the station.
    val pill = if (hostMode) scheme.tertiaryContainer else scheme.primaryContainer
    val onPill = if (hostMode) scheme.onTertiaryContainer else scheme.onPrimaryContainer
    val context = LocalContext.current
    val voiceAvailable by produceState(initialValue = VoiceSearchAvailability.cached ?: false, context) {
        value = VoiceSearchAvailability.resolve(context.applicationContext)
    }
    val latestSendText by rememberUpdatedState(onSendText)
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!spoken.isNullOrEmpty()) latestSendText(spoken)
    }
    val hint = stringResource(if (hostMode) R.string.host_input_hint else R.string.taizo_input_hint)
    val textStyle = remember(onPill) {
        TextStyle(fontFamily = GoogleSansRounded, fontSize = 15.sp, lineHeight = 20.sp, color = onPill)
    }
    val cursor = remember(onPill) { SolidColor(onPill) }
    LaunchedEffect(startVoice, voiceAvailable) {
        if (startVoice && voiceAvailable) runCatching { voiceLauncher.launch(VoiceSearchAvailability.intent(hint)) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            if (hostMode) {
                Icon(Icons.Rounded.AllInclusive, contentDescription = stringResource(R.string.host_back_to_station), tint = scheme.onSurface, modifier = Modifier.size(28.dp))
            } else {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close), tint = scheme.onSurface, modifier = Modifier.size(26.dp))
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 68.dp)
                .clip(RoundedCornerShape(34.dp))
                .background(pill)
                .padding(start = 16.dp, end = 11.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = onPill, modifier = Modifier.size(22.dp))
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                textStyle = textStyle,
                cursorBrush = cursor,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank() && !isResponding) onSend() }),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = hint },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) {
                            Text(hint, style = textStyle, color = onPill.copy(alpha = 0.72f), maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            val (icon, cd, action) = when {
                isResponding -> Triple(Icons.Rounded.Stop, stringResource(R.string.taizo_stop), onStop)
                input.isBlank() && voiceAvailable -> Triple(
                    Icons.Rounded.Mic,
                    stringResource(R.string.nav_search_cd_voice),
                    { runCatching { voiceLauncher.launch(VoiceSearchAvailability.intent(hint)) }; Unit },
                )
                else -> Triple(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.taizo_send), onSend)
            }
            val enabled = isResponding || input.isNotBlank() || voiceAvailable
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (enabled) onPill else onPill.copy(alpha = 0.38f))
                    .clickable(enabled = enabled, onClick = action),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = cd, tint = pill, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The host's chips: one row that scrolls sideways, outlined 40 dp chips (12 dp corners) as in AndroidAskHost. Answered on the phone. */
@Composable
private fun HostChips(enabled: Boolean, onChip: (HostChip) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(HOST_CHIPS, key = { it.first.name }, contentType = { "host_chip" }) { (chip, label) ->
            Text(
                text = stringResource(label),
                style = RedesignType.Label,
                color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f),
                maxLines = 1,
                modifier = Modifier
                    .height(40.dp)
                    .clip(shape)
                    .border(1.dp, scheme.outline, shape)
                    .clickable(enabled = enabled) { onChip(chip) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

private val HOST_CHIPS: List<Pair<HostChip, Int>> = listOf(
    HostChip.NEW to R.string.endless_chip_new,
    HostChip.FAMILIAR to R.string.endless_chip_familiar,
    HostChip.WHY to R.string.endless_chip_why,
    HostChip.UPBEAT to R.string.endless_chip_upbeat,
    HostChip.SLOWER to R.string.endless_chip_slower,
    HostChip.SURPRISE to R.string.endless_chip_surprise,
)

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
        TaizoShapeAvatar(size = 72)
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            TAIZO_SUGGESTIONS.forEach { suggestion ->
                TaizoOutlinedChip(text = suggestion, onClick = { onSuggestion(suggestion) })
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
                .widthIn(max = 290.dp)
                .clip(UserBubbleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(text = text, style = RedesignType.RowTitle.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

private val assistantShape = RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
private val UserBubbleShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 6.dp)
private val AnswerTextStyle = TextStyle(fontFamily = GoogleSansRounded, fontSize = 16.sp, lineHeight = 24.sp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantBubble(
    item: TaizoChatItem.Assistant,
    sparkleBrush: Brush,
    onOpenSource: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaizoShapeAvatar(size = 28)
            Text(
                text = stringResource(if (LocalTaizoHost.current) R.string.host_name else R.string.taizo_name),
                style = RedesignType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .then(
                    if (item.isError) {
                        Modifier
                            .clip(assistantShape)
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    } else Modifier
                )
                .animateContentSize()
        ) {
            val textColor = if (item.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
            if (item.text.isNotBlank()) {
                val suffix = item.suffixRes?.let { " " + stringResource(it) }.orEmpty()
                Text(
                    text = remember(item.text, suffix) { lightMarkdown(item.text + suffix) },
                    style = AnswerTextStyle,
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
        TaizoShapeAvatar(size = 28)
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

private val TaizoChipShape = RoundedCornerShape(12.dp)

/** The mockup's refine / suggestion chip: 44 dp, 12 dp corners, 1 dp outline, 14 dp padding, 14/20 sp medium. */
@Composable
internal fun TaizoOutlinedChip(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(TaizoChipShape)
            .border(1.dp, scheme.outline, TaizoChipShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = RedesignType.Label,
            color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** True inside the sheet while it is the host of Endless Listening: avatars and names become the host's. */
internal val LocalTaizoHost = androidx.compose.runtime.staticCompositionLocalOf { false }

/*
 * Ask the host, redesign batch 3 (project/AndroidAskHost.dc.html):
 *   header   52 dp host badge (Flower, tertiary, mic), 12 dp, "Your host" 28/34 sp w800 (wdth 125 + 75 in tertiary) over
 *            "Endless Listening" 14/20 onSurfaceVariant; Back to station as a 48 dp / 16 dp-corner surfaceContainerHigh button.
 *   playing  60 dp strip, 20 dp corners, surfaceContainer, 10/12 dp padding: 40 dp art on a Sunny, 12 dp, title 14/20 medium over
 *            artist 13/18, "ON AIR" 12/16 w700 in tertiary with an 8 dp error dot, a 3 dp progress line 16 dp in, 4 dp up.
 *   chips    outlined 40 dp, 12 dp corners. Input pills in tertiaryContainer, the left pill (infinity) goes back to the station.
 */
@Composable
private fun HostHeader(onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        com.theveloper.pixelplay.presentation.screens.HostBadge(52.dp)
        Column(Modifier.weight(1f)) {
            com.theveloper.pixelplay.presentation.components.redesign.TwoToneTitle(
                wide = stringResource(R.string.host_title_a),
                accent = stringResource(R.string.host_title_b),
                fontSize = 28f,
                lineHeight = 34f,
                letterSpacing = -0.3f,
                accentColor = scheme.tertiary,
                maxLines = 1,
            )
            Text(stringResource(R.string.endless_title), style = RedesignType.Label.copy(fontWeight = FontWeight.Normal), color = scheme.onSurfaceVariant)
        }
        RedesignHeaderButton(
            icon = rememberVectorPainter(Icons.Rounded.KeyboardArrowDown),
            contentDescription = stringResource(R.string.host_back_to_station),
            onClick = onClose,
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        )
    }
}

@Composable
private fun HostNowPlaying(playerViewModel: PlayerViewModel) {
    val scheme = MaterialTheme.colorScheme
    val song by remember(playerViewModel) {
        playerViewModel.stablePlayerState.map { it.currentSong }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = playerViewModel.stablePlayerState.value.currentSong)
    val duration by remember(playerViewModel) {
        playerViewModel.stablePlayerState.map { it.totalDuration }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = 0L)
    val position = playerViewModel.currentPlaybackPosition.collectAsStateWithLifecycle()
    val current = song ?: return
    val track = scheme.surfaceContainerHighest
    val fill = scheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainer)
            .drawBehind {
                val inset = 16.dp.toPx()
                val h = 3.dp.toPx()
                val y = size.height - 4.dp.toPx() - h
                val w = size.width - inset * 2
                val f = if (duration > 0L) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
                val r = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f)
                drawRoundRect(track, androidx.compose.ui.geometry.Offset(inset, y), androidx.compose.ui.geometry.Size(w, h), r)
                drawRoundRect(fill, androidx.compose.ui.geometry.Offset(inset, y), androidx.compose.ui.geometry.Size(w * f, h), r)
            }
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        com.theveloper.pixelplay.presentation.components.SmartImage(
            current.albumArtUriString, null, Modifier.size(40.dp),
            shape = androidx.compose.material3.MaterialShapes.Sunny.toShape(),
        )
        Column(Modifier.weight(1f)) {
            Text(current.title, style = RedesignType.Label, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(current.displayArtist, style = RedesignType.RowSubtitle, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.error))
            Text(
                stringResource(R.string.endless_on_air),
                style = RedesignType.Label.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
                color = scheme.tertiary,
            )
        }
    }
}

@Composable
private fun HostEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        com.theveloper.pixelplay.presentation.screens.HostBadge(72.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.endless_host_sheet_hint),
            style = RedesignType.RowTitle.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.AdaptiveModalBottomSheet
import com.theveloper.pixelplay.presentation.components.SetNoteLines
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.rememberHostFacts
import com.theveloper.pixelplay.presentation.components.rememberHostNotesOff
import com.theveloper.pixelplay.presentation.components.redesign.TwoToneTitle
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.HostChip
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.ui.theme.FlexFitPolicy
import com.theveloper.pixelplay.ui.theme.FlexFitText
import com.theveloper.pixelplay.ui.theme.FlexSpec
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.flexColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * Endless Listening's station screen, redesign batch 3 (project/AndroidEndlessStation.dc.html; 390 x 844, 1 px = 1 dp). Opened from the
 * Home entry once the intro was seen, and right after the intro's "Start listening". Everything on it is the real queue: the song playing,
 * its place in the station, the host's notes for the songs coming up (SetNoteLines, the same notes the player and the queue show).
 *
 *   header    status bar + 20 dp, 16 dp gutter, 48 dp: Minimise (48 dp circle surfaceContainerHigh, chevron down), the centre column
 *             (eyebrow 12/16 w600 0.4 sp tertiary with a 16 dp infinity, 6 dp gap; under it the station's line 16/22 w600 when there
 *             is one), Station options (48 dp circle, ⋮): host notes on/off, how it works, open the player.
 *   artwork   16 dp under, 212 dp tall, 32 dp corners: the song's art. "ON AIR" pill (28 dp, 14 dp corners, surface, 12 sp w700
 *             0.4 sp, 8 dp error dot) at 14/14; "Track N of ∞" pill (surface, onSurfaceVariant 12 sp w600) at right 14 / bottom 14.
 *   title     16 dp under: title 34/40 sp w800 wdth 120 (fitted), artist 16/22 onSurfaceVariant; Like 48 dp circle.
 *   progress  18 dp under: 6 dp track (surfaceContainerHighest) with the primary fill and a 4 x 20 dp handle; times 12/16 under it,
 *             6 dp apart. Tap or drag to seek. Read in draw only: the position never recomposes the screen.
 *   controls  72 dp row, space between: Less like this (56 dp circle surfaceContainerHigh, 22 dp), Previous (56 dp, 28 dp icon),
 *             Play / Pause (104 x 72 dp, 28 dp corners, primary / onPrimary, 32 dp icon), Next, Up next (56 dp circle).
 *   notes     22 dp under: "Host notes" 20/26 w800 (wdth 125 + 75 in tertiary), the count 13/18 on the right; 10 dp; the top note
 *             (tertiaryContainer, 24 dp corners, 16 dp padding, 40 dp host badge, 12 dp gap, eyebrow 12/16 w700, text 15/21) with the
 *             next one peeking 18 dp lower, 14 dp in (secondaryContainer). Swipe a note away; it stays away for this song.
 *   asks      outlined chips 36 dp, 12 dp corners, 1 dp outline, 14 dp padding, 14 sp medium, 8 dp apart: open the host with the chip.
 *   ask pill  12 dp from the sides, 16 dp + nav inset from the bottom, 68 dp, 34 dp corners, tertiaryContainer: 22 dp sparkle,
 *             "Ask the host" 16/22 medium, 52 dp onTertiaryContainer circle with the mic (starts speech right away).
 */

private val StationTitleSpec = FlexSpec(weight = 800f, width = 120f)
private val StationTitlePolicy = FlexFitPolicy(minWidth = 50f, maxWidth = 120f, multiLineMaxWidth = 100f, maxLines = 2)
private val StationTitleBase = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp)

@Composable
fun EndlessStationScreen(navController: NavController, playerViewModel: PlayerViewModel) {
    val scheme = MaterialTheme.colorScheme
    val endless = playerViewModel.brainSession.endless
    val scope = rememberCoroutineScope()
    val currentSong by remember(playerViewModel) { playerViewModel.stablePlayerState.map { it.currentSong }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = playerViewModel.stablePlayerState.value.currentSong)
    val isPlaying by remember(playerViewModel) { playerViewModel.stablePlayerState.map { it.isPlaying }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false)
    val index by remember(playerViewModel) { playerViewModel.stablePlayerState.map { it.currentMediaItemIndex }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = playerViewModel.stablePlayerState.value.currentMediaItemIndex)
    val duration by remember(playerViewModel) { playerViewModel.stablePlayerState.map { it.totalDuration }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = 0L)
    val queue by remember(playerViewModel) { playerViewModel.queueFlow }.collectAsStateWithLifecycle()
    val favorites by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val notesEnabled by endless.hostNotesEnabled.collectAsStateWithLifecycle()

    // The host's notes for the window from the current song on: exactly what the player's note line uses.
    val heard by produceState<Set<String>?>(null, queue, index) {
        value = playerViewModel.brainSession.setNoteHistory.heardSongIds(SetNoteLines.window(queue, index))
    }
    val host = rememberHostFacts(playerViewModel, queue, index)
    val notesOff = rememberHostNotesOff(playerViewModel)
    val notes = remember(queue, index, heard, host, notesOff) {
        val r = SetNoteLines.forQueue(queue, index, heard, host, notesOff)
        r.byIndex.entries.filter { it.key >= index }.sortedBy { it.key }.map { StationNote(it.key, it.value) }
    }
    val dismissed = remember { mutableStateListOf<StationNote>() }
    val shownNotes = remember(notes, dismissed.size) { notes.filter { it !in dismissed } }
    val island = currentSong?.id?.let { host?.islandOf?.get(it) }?.takeIf { it.isNotBlank() }

    var menuOpen by remember { mutableStateOf(false) }
    var showUpNext by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(scheme.surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 120.dp),
        ) {
            // Header
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StationCircle(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.endless_station_minimise), scheme.surfaceContainerHigh, scheme.onSurface) {
                    navController.popBackStack()
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.AllInclusive, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.endless_eyebrow), style = EyebrowStyle, color = scheme.tertiary)
                    }
                    if (island != null) {
                        Text(island, style = StationLineStyle, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    StationCircle(Icons.Rounded.MoreVert, stringResource(R.string.endless_station_options), scheme.surfaceContainerHigh, scheme.onSurface) {
                        menuOpen = true
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (notesEnabled) R.string.endless_notes_on else R.string.endless_notes_off)) },
                            onClick = {
                                menuOpen = false
                                endless.setHostNotes(!notesEnabled)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.endless_how_it_works)) },
                            onClick = {
                                menuOpen = false
                                navController.navigateSafely(Screen.EndlessIntro.route)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.endless_open_player)) },
                            onClick = {
                                menuOpen = false
                                navController.popBackStack()
                                playerViewModel.expandPlayerSheet()
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            // Artwork
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(212.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(scheme.surfaceContainerHigh)
            ) {
                SmartImage(currentSong?.albumArtUriString, null, Modifier.matchParentSize())
                Row(
                    modifier = Modifier
                        .padding(14.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.surface)
                        .padding(start = 10.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.error))
                    Text(stringResource(R.string.endless_on_air), style = PillStyle, color = scheme.onSurface)
                }
                if (index >= 0) {
                    Text(
                        text = stringResource(R.string.endless_track_of, index + 1),
                        style = PillStyle.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(scheme.surface)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            // Title + like
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FlexFitText(
                        text = currentSong?.title.orEmpty(),
                        style = StationTitleBase,
                        color = flexColor(scheme.onSurface),
                        spec = StationTitleSpec,
                        policy = StationTitlePolicy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(currentSong?.displayArtist.orEmpty(), style = ArtistStyle, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val song = currentSong
                val liked = song != null && song.id in favorites
                StationCircle(
                    icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    cd = stringResource(if (liked) R.string.endless_unlike_cd else R.string.endless_like_cd, song?.title.orEmpty()),
                    container = scheme.surfaceContainerHigh,
                    content = if (liked) scheme.primary else scheme.onSurface,
                    enabled = song != null,
                ) { song?.let { playerViewModel.toggleFavoriteSpecificSong(it) } }
            }

            Spacer(Modifier.height(18.dp))
            StationProgress(
                position = playerViewModel.currentPlaybackPosition.collectAsStateWithLifecycle(),
                duration = duration,
                onSeek = { playerViewModel.seekTo(it) },
            )

            Spacer(Modifier.height(10.dp))
            // Controls
            Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                StationCircle(Icons.Rounded.ThumbDown, stringResource(R.string.endless_less_like_this), scheme.surfaceContainerHigh, scheme.onSurface, size = 56.dp, iconSize = 22.dp, enabled = currentSong != null) {
                    currentSong?.let { playerViewModel.notForMe(it) }
                }
                StationCircle(Icons.Rounded.SkipPrevious, stringResource(R.string.endless_previous), Color.Transparent, scheme.onSurface, size = 56.dp, iconSize = 28.dp) {
                    playerViewModel.previousSong()
                }
                Box(
                    modifier = Modifier
                        .size(width = 104.dp, height = 72.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(scheme.primary)
                        .clickable(role = Role.Button) { playerViewModel.playPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.endless_pause else R.string.endless_play),
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(32.dp),
                    )
                }
                StationCircle(Icons.Rounded.SkipNext, stringResource(R.string.endless_next), Color.Transparent, scheme.onSurface, size = 56.dp, iconSize = 28.dp) {
                    playerViewModel.nextSong()
                }
                StationCircle(Icons.Rounded.PlaylistPlay, stringResource(R.string.endless_up_next), scheme.surfaceContainerHigh, scheme.onSurface, size = 56.dp, iconSize = 22.dp) {
                    showUpNext = true
                }
            }

            if (!notesOff) {
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TwoToneTitle(
                        wide = stringResource(R.string.endless_host_notes_a),
                        accent = stringResource(R.string.endless_host_notes_b),
                        fontSize = 20f,
                        lineHeight = 26f,
                        letterSpacing = -0.2f,
                        accentColor = scheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                    if (shownNotes.isNotEmpty()) {
                        Text(pluralStringResource(R.plurals.endless_notes_count, shownNotes.size, shownNotes.size), style = CountStyle, color = scheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                HostNotesStack(
                    notes = shownNotes,
                    onDismiss = { dismissed.add(it) },
                )
            }

            Spacer(Modifier.height(18.dp))
            QuickAsks { chip -> endless.openHostSheet(chip) }
        }

        // Ask the host
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 12.dp, end = 12.dp, bottom = 16.dp)
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(34.dp))
                .background(scheme.tertiaryContainer)
                .clickable(role = Role.Button) { endless.openHostSheet(null) }
                .padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.endless_ask_host), style = AskStyle, color = scheme.onTertiaryContainer, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(scheme.onTertiaryContainer)
                    .clickable(role = Role.Button) { endless.openHostSheet(null, voice = true) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = stringResource(R.string.nav_search_cd_voice), tint = scheme.tertiaryContainer, modifier = Modifier.size(22.dp))
            }
        }
    }

    if (showUpNext) {
        UpNextSheet(
            upcoming = remember(queue, index) { if (index < 0) emptyList() else queue.drop(index + 1).take(20) },
            onDismiss = { showUpNext = false },
        )
    }
}

/** One host note and the queue row it belongs to (so the same text on two rows stays two notes). */
private data class StationNote(val index: Int, val text: String)

@Composable
private fun StationCircle(
    icon: ImageVector,
    cd: String,
    container: Color,
    content: Color,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = cd, tint = if (enabled) content else content.copy(alpha = 0.38f), modifier = Modifier.size(iconSize))
    }
}

/** The 6 dp progress bar and its times. Position is read in draw and in the small time row only. */
@Composable
private fun StationProgress(position: State<Long>, duration: Long, onSeek: (Long) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.surfaceContainerHighest
    val fill = scheme.primary
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val positionText by remember(duration) { derivedStateOf { formatTime(position.value) } }
    val durationText = remember(duration) { formatTime(duration) }
    val seekCd = stringResource(R.string.endless_seek_cd, positionText, durationText)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .semantics { contentDescription = seekCd }
                .pointerInput(duration) {
                    if (duration <= 0L) return@pointerInput
                    detectTapGestures { offset -> onSeek(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
                }
                .pointerInput(duration) {
                    if (duration <= 0L) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { f -> onSeek((f * duration).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
                }
                .drawBehind {
                    val f = dragFraction ?: if (duration > 0L) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    val h = 6.dp.toPx()
                    val top = (size.height - h) / 2f
                    val r = CornerRadius(h / 2f, h / 2f)
                    drawRoundRect(track, Offset(0f, top), Size(size.width, h), r)
                    drawRoundRect(fill, Offset(0f, top), Size(size.width * f, h), r)
                    val handleW = 4.dp.toPx()
                    drawRoundRect(fill, Offset((size.width * f - handleW / 2f).coerceIn(0f, size.width - handleW), 0f), Size(handleW, size.height), CornerRadius(handleW / 2f))
                }
        )
        Row(Modifier.fillMaxWidth()) {
            Text(positionText, style = TimeStyle, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(durationText, style = TimeStyle, color = scheme.onSurfaceVariant)
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val m = total / 60
    val s = total % 60
    return if (s < 10) "$m:0$s" else "$m:$s"
}

/** The top note with the next one peeking under it; a horizontal swipe sends the top one away. */
@Composable
private fun HostNotesStack(notes: List<StationNote>, onDismiss: (StationNote) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val top = notes.firstOrNull()
    if (top == null) {
        Text(
            stringResource(R.string.endless_no_note),
            style = NoteStyle,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(scheme.surfaceContainer).padding(16.dp),
        )
        return
    }
    val offsetX = remember(top) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissCd = stringResource(R.string.endless_note_dismiss_cd)
    Box(Modifier.fillMaxWidth().padding(bottom = if (notes.size > 1) 12.dp else 0.dp)) {
        if (notes.size > 1) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(start = 14.dp, end = 14.dp, top = 18.dp)
                    .graphicsLayer { translationY = 12.dp.toPx() }
                    .clip(RoundedCornerShape(24.dp))
                    .background(scheme.secondaryContainer)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .graphicsLayer {
                    translationX = offsetX.value
                    alpha = 1f - (abs(offsetX.value) / (size.width.coerceAtLeast(1f))).coerceIn(0f, 1f)
                }
                .pointerInput(top) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(offsetX.value) > size.width * 0.33f) {
                                    offsetX.animateTo(if (offsetX.value > 0) size.width.toFloat() else -size.width.toFloat(), tween(160))
                                    onDismiss(top)
                                } else {
                                    offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                }
                            }
                        },
                    ) { _, dragAmount -> scope.launch { offsetX.snapTo(offsetX.value + dragAmount) } }
                }
                .semantics { contentDescription = dismissCd }
                .clip(RoundedCornerShape(24.dp))
                .background(scheme.tertiaryContainer)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HostBadge(40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.endless_note_eyebrow), style = NoteEyebrowStyle, color = scheme.onTertiaryContainer)
                Text(top.text, style = NoteStyle, color = scheme.onTertiaryContainer)
            }
        }
    }
}

private val QuickAskChips = listOf(
    HostChip.WHY to R.string.endless_chip_why,
    HostChip.SURPRISE to R.string.endless_chip_surprise,
    HostChip.NEW to R.string.endless_chip_new,
    HostChip.UPBEAT to R.string.endless_chip_upbeat,
    HostChip.SLOWER to R.string.endless_chip_slower,
    HostChip.FAMILIAR to R.string.endless_chip_familiar,
)

@Composable
private fun QuickAsks(onChip: (HostChip) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 16.dp)) {
        items(QuickAskChips, key = { it.first.name }, contentType = { "quick_ask" }) { (chip, label) ->
            Text(
                text = stringResource(label),
                style = ChipStyle,
                color = scheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .height(36.dp)
                    .clip(shape)
                    .border(1.dp, scheme.outline, shape)
                    .clickable(role = Role.Button) { onChip(chip) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** "Up next": the station's next songs, read only (the queue itself is in the full player). */
@Composable
private fun UpNextSheet(upcoming: List<Song>, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss, containerColor = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.endless_up_next), style = SheetTitleStyle, color = scheme.onSurface, modifier = Modifier.padding(bottom = 12.dp))
            if (upcoming.isEmpty()) {
                Text(stringResource(R.string.endless_up_next_empty), style = NoteStyle, color = scheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(upcoming, key = { it.id }, contentType = { "up_next" }) { song ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SmartImage(song.albumArtUriString, null, Modifier.size(44.dp), shape = RoundedCornerShape(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = RowTitleStyle, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.displayArtist, style = CountStyle, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val EyebrowStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp)
private val StationLineStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
private val PillStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp)
private val ArtistStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
private val TimeStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
private val CountStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
private val NoteEyebrowStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp)
private val NoteStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
private val ChipStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
private val AskStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
private val SheetTitleStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp)
private val RowTitleStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)

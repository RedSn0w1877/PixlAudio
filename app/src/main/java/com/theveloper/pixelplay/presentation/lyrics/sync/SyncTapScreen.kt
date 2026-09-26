package com.theveloper.pixelplay.presentation.lyrics.sync

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.lyrics.sync.LyricsTapSync
import com.theveloper.pixelplay.data.lyrics.sync.SyncDraft
import com.theveloper.pixelplay.presentation.viewmodel.LyricsSyncEditorStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.SyncUiState
import com.theveloper.pixelplay.ui.theme.LyricsDisplayFamily
import com.theveloper.pixelplay.ui.theme.lyricsFamilyAtSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The tap screen (spec §2.5): context lines on top, the next word big, one huge button, and
 * Undo · Play/Pause · Back 5 s. Recomposes only when the draft (a tap), the phase or the play
 * state changes; the music-break countdown reads the position in draw.
 */
@Composable
internal fun SyncTapScreen(
    ui: SyncUiState,
    holder: LyricsSyncEditorStateHolder,
    palette: SyncEditorPalette,
    modifier: Modifier = Modifier,
) {
    val draft = ui.draft ?: return
    val view = derivedTapView(draft, ui.fixLine)
    val wordFont = remember(draft.songId, draft.lines.size) {
        if (draft.lines.all { isCoveredByAppFont(it.text) }) LyricsDisplayFamily else null
    }
    val inFixLine = ui.fixLine != null
    val finished = !inFixLine && draft.isFinished

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        SyncTopBar(
            title = ui.title,
            palette = palette,
            onClose = holder::requestClose,
            speed = ui.speed,
            onSpeedChange = holder::setSpeed,
        )
        // The bar reads the fraction in draw, so a tap redraws it without recomposing the row
        // (which re-runs only when the line number changes).
        val progress by rememberUpdatedState(view.progress)
        val progressProvider = remember { { progress } }
        ProgressRow(
            lineNumber = view.lineNumber,
            lineCount = view.lineCount,
            fraction = progressProvider,
            palette = palette,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val breakInfo = view.musicBreak
            if (breakInfo != null && ui.isPlaying) {
                MusicBreakOrContext(
                    breakInfo = breakInfo,
                    holder = holder,
                    palette = palette,
                ) {
                    LyricContext(draft, view, palette, wordFont, holder, allowJump = !inFixLine)
                }
            } else {
                LyricContext(draft, view, palette, wordFont, holder, allowJump = !inFixLine)
            }
        }

        AnimatedVisibility(visible = !inFixLine && view.canSkip && !finished) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.lyrics_sync_skip_line),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = holder::skipLine)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }

        NextWordLabel(
            word = if (finished) null else view.nextWord,
            fontFamily = wordFont,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )

        val label = when {
            finished -> stringResource(R.string.lyrics_sync_see_result)
            !ui.isPlaying && !ui.started -> stringResource(R.string.lyrics_sync_start_song)
            !ui.isPlaying -> stringResource(R.string.lyrics_sync_paused)
            else -> stringResource(R.string.lyrics_sync_tap_hint)
        }
        val showTip = !finished && ui.isPlaying && ui.sessionTaps < HOLD_TIP_TAPS
        TapPad(
            label = label,
            tip = if (showTip) stringResource(R.string.lyrics_sync_hold_tip) else null,
            haptics = ui.haptics,
            palette = palette,
            onDown = holder::onTapDown,
            onUp = holder::onTapUp,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.25f)
                .heightIn(min = 200.dp),
        )

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EditorStackedButton(
                onClick = holder::undo,
                icon = Icons.AutoMirrored.Rounded.Undo,
                label = stringResource(R.string.lyrics_sync_undo),
                palette = palette,
                enabled = view.canUndo,
                modifier = Modifier.weight(1f),
            )
            EditorStackedButton(
                onClick = holder::togglePlay,
                icon = if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                label = stringResource(if (ui.isPlaying) R.string.lyrics_sync_pause else R.string.lyrics_sync_play),
                palette = palette,
                modifier = Modifier.weight(1f),
            )
            EditorStackedButton(
                onClick = holder::rewind,
                icon = Icons.Rounded.Replay5,
                label = stringResource(R.string.lyrics_sync_back5),
                palette = palette,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// What the screen shows, derived once per draft change
// ─────────────────────────────────────────────────────────────────────────────────────────────

@Immutable
private class MusicBreak(val anchorMs: Long, val fromMs: Long)

@Immutable
private class TapView(
    val currentLine: Int,
    val previousLine: Int?,
    val nextLine: Int?,
    /** Next token to tap, or `tokens.size` when finished. */
    val nextIndex: Int,
    /** The word being sung: the last stamped token before [nextIndex] in the current line. */
    val sungIndex: Int,
    val nextWord: String?,
    val lineNumber: Int,
    val lineCount: Int,
    val canUndo: Boolean,
    val canSkip: Boolean,
    val musicBreak: MusicBreak?,
    /** Tapped share of the tappable words, 0..1 (1 when there is nothing to tap). */
    val progress: Float,
)

@Composable
private fun derivedTapView(draft: SyncDraft, fixLine: Int?): TapView = remember(draft, fixLine) {
    val nextIndex = LyricsTapSync.nextTappable(draft, draft.cursor)
    val currentLine = draft.tokens.getOrNull(nextIndex)?.line ?: fixLine ?: draft.lines.lastIndex
    val openLines = draft.lines.indices.filter { !draft.lines[it].locked && draft.lines[it].tokenCount > 0 }
    val position = openLines.indexOf(currentLine).coerceAtLeast(0)
    val previousLine = openLines.getOrNull(position - 1)
    val nextLine = openLines.getOrNull(position + 1)
    val line = draft.lines[currentLine]
    var sung = -1
    for (i in minOf(nextIndex, line.endToken) - 1 downTo line.firstToken) {
        if (draft.tokens[i].rawStartMs != null) {
            sung = i
            break
        }
    }
    val floor = fixLine?.let { draft.lines[it].firstToken } ?: 0
    var canUndo = false
    for (i in minOf(draft.cursor, draft.tokens.size) - 1 downTo floor) {
        if (draft.tokens[i].rawStartMs != null && !draft.lines[draft.tokens[i].line].locked) {
            canUndo = true
            break
        }
    }
    // Music break: the next word opens a line whose anchor is far from the last tap.
    val musicBreak = if (nextIndex < draft.tokens.size && nextIndex == line.firstToken && line.anchorMs != null) {
        var lastStart = 0L
        for (i in nextIndex - 1 downTo 0) {
            val start = LyricsTapSync.builtStartMs(draft, i, 0)
            if (start != null) {
                lastStart = start
                break
            }
        }
        val anchor = line.anchorMs!!
        if (anchor - lastStart > MUSIC_BREAK_MIN_MS) MusicBreak(anchor, lastStart) else null
    } else null
    TapView(
        currentLine = currentLine,
        previousLine = previousLine,
        nextLine = nextLine,
        nextIndex = nextIndex,
        sungIndex = sung,
        nextWord = draft.tokens.getOrNull(nextIndex)?.text?.trim(),
        lineNumber = position + 1,
        lineCount = openLines.size.coerceAtLeast(1),
        canUndo = canUndo,
        canSkip = LyricsTapSync.canSkipLine(draft),
        musicBreak = musicBreak,
        progress = draft.tappableCount.let { total -> if (total == 0) 1f else draft.tappedCount / total.toFloat() },
    )
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Pieces
// ─────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProgressRow(lineNumber: Int, lineCount: Int, fraction: () -> Float, palette: SyncEditorPalette) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = stringResource(R.string.lyrics_sync_line_of, lineNumber, lineCount),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .drawBehind {
                    val fill = fraction().coerceIn(0f, 1f)
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = radius)
                    drawRoundRect(palette.accent, size = Size(size.width * fill, size.height), cornerRadius = radius)
                },
        )
    }
}

@Composable
private fun LyricContext(
    draft: SyncDraft,
    view: TapView,
    palette: SyncEditorPalette,
    fontFamily: FontFamily?,
    holder: LyricsSyncEditorStateHolder,
    allowJump: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        view.previousLine?.let { index ->
            ContextLine(
                text = draft.lines[index].text,
                fontFamily = fontFamily,
                onClick = if (allowJump) ({ holder.jumpToLine(index) }) else null,
            )
        }
        val line = draft.lines[view.currentLine]
        val compact = line.tokenCount > COMPACT_TOKENS
        val wordSize = if (compact) 24.sp else 30.sp
        // Not tappable: it sits right above the pad, where a novice naturally taps along, and a
        // tap here would erase the line's taps. "Redo from here" is the previous line's job.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (i in line.firstToken until line.endToken) {
                key(i) {
                    val token = draft.tokens[i]
                    val state = when {
                        i == view.nextIndex -> WordState.NEXT
                        i == view.sungIndex -> WordState.SUNG
                        token.rawStartMs != null -> WordState.DONE
                        else -> WordState.LATER
                    }
                    WordChip(
                        text = token.text.trim(),
                        trailingSpace = token.text.endsWith(' '),
                        state = state,
                        fontSize = wordSize,
                        fontFamily = fontFamily,
                        palette = palette,
                    )
                }
            }
        }
        view.nextLine?.let { index ->
            ContextLine(text = draft.lines[index].text, fontFamily = fontFamily, onClick = null)
        }
    }
}

@Composable
private fun ContextLine(text: String, fontFamily: FontFamily?, onClick: (() -> Unit)?) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.35f),
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = lyricsFamilyAtSize(fontFamily, 18.sp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onClick)
                else Modifier
            ),
    )
}

private enum class WordState { DONE, SUNG, NEXT, LATER }

/**
 * One word of the current line. The white box marks the word being sung (the last one tapped).
 * Tapped words are coloured in: the accent sweeps across the letters from the first to the last
 * when the word is tapped, and stays. Untapped words are white (the next one) or dim (later).
 *
 * The sweep is read only in draw, so it never recomposes; the base and the accent copy are each
 * clipped to their own side of the edge, so no glyph is drawn twice.
 */
@Composable
private fun WordChip(
    text: String,
    trailingSpace: Boolean,
    state: WordState,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily?,
    palette: SyncEditorPalette,
) {
    val tapped = state == WordState.DONE || state == WordState.SUNG
    val fill = remember { Animatable(if (tapped) 1f else 0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(state) {
        when (state) {
            WordState.SUNG -> {
                if (fill.value < 1f) {
                    launch { pop.snapTo(BOX_POP_SCALE); pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 900f)) }
                    fill.animateTo(1f, tween(FILL_SWEEP_MS, easing = FastOutSlowInEasing))
                }
            }
            // A tap that lands mid-sweep cancels it: finish the fill and settle the box at once.
            WordState.DONE -> {
                pop.snapTo(1f)
                fill.snapTo(1f)
            }
            else -> {
                pop.snapTo(1f)
                fill.snapTo(0f)
            }
        }
    }
    val rtl = remember(text) { isRtlWord(text) }
    val family = lyricsFamilyAtSize(fontFamily, fontSize)
    val baseColor = if (state == WordState.LATER) Color.White.copy(alpha = 0.4f) else Color.White

    Box(
        modifier = Modifier
            .padding(end = if (trailingSpace) 4.dp else 0.dp)
            .then(
                if (state == WordState.SUNG) Modifier
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    }
                    .border(1.5.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                else Modifier
            )
            .padding(horizontal = 6.dp),
    ) {
        Text(
            text = text,
            color = baseColor,
            fontSize = fontSize,
            lineHeight = fontSize * 1.2f,
            fontWeight = FontWeight.SemiBold,
            fontFamily = family,
            modifier = if (tapped) Modifier.drawWithContent { clipFill(fill.value, rtl, filledSide = false) } else Modifier,
        )
        if (tapped) {
            Text(
                text = text,
                color = palette.accent,
                fontSize = fontSize,
                lineHeight = fontSize * 1.2f,
                fontWeight = FontWeight.SemiBold,
                fontFamily = family,
                modifier = Modifier.drawWithContent { clipFill(fill.value, rtl, filledSide = true) },
            )
        }
    }
}

/** Draws the part of the text on one side of the fill edge ([fraction] of the width from the start). */
private fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.clipFill(
    fraction: Float,
    rtl: Boolean,
    filledSide: Boolean,
) {
    val f = fraction.coerceIn(0f, 1f)
    if (f >= 1f) { if (filledSide) drawContent(); return }
    if (f <= 0f) { if (!filledSide) drawContent(); return }
    val edge = if (rtl) size.width * (1f - f) else size.width * f
    val startSide = filledSide != rtl
    if (startSide) clipRect(right = edge) { this@clipFill.drawContent() }
    else clipRect(left = edge) { this@clipFill.drawContent() }
}

private fun isRtlWord(text: String): Boolean {
    val first = text.firstOrNull { it.isLetter() } ?: return false
    val d = Character.getDirectionality(first)
    return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
}

@Composable
private fun NextWordLabel(word: String?, fontFamily: FontFamily?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().height(NEXT_BLOCK_HEIGHT)) {
        if (word != null) {
            Text(
                text = stringResource(R.string.lyrics_sync_next_label).uppercase(),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            AnimatedContent(
                targetState = word,
                transitionSpec = { fadeIn(tween(90)) togetherWith fadeOut(tween(60)) },
                label = "nextWord",
            ) { value ->
                Text(
                    text = value,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    // Auto-sized between 24 and 40 sp: cut for the middle of that range.
                    fontFamily = lyricsFamilyAtSize(fontFamily, 32.sp),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 40.sp, stepSize = 2.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Replaces the context with "Music break" and a ring counting down to the next line. */
@Composable
private fun MusicBreakOrContext(
    breakInfo: MusicBreak,
    holder: LyricsSyncEditorStateHolder,
    palette: SyncEditorPalette,
    context: @Composable () -> Unit,
) {
    val position = remember { mutableLongStateOf(holder.positionMs()) }
    LaunchedEffect(breakInfo) {
        while (isActive) {
            val now = holder.positionMs()
            val moved = now != position.longValue
            position.longValue = now
            // Per-frame only while the countdown ring is on screen and moving; otherwise (paused,
            // or outside the break) a slow poll that still notices a seek back into it.
            if (moved && breakInfo.anchorMs - now > MUSIC_BREAK_END_MS) withFrameNanos { } else delay(BREAK_IDLE_POLL_MS)
        }
    }
    val inBreak by remember(breakInfo) {
        derivedStateOf { breakInfo.anchorMs - position.longValue > MUSIC_BREAK_END_MS }
    }
    val secondsLeft by remember(breakInfo) {
        derivedStateOf { ((breakInfo.anchorMs - position.longValue + 999) / 1000).coerceAtLeast(0L) }
    }
    AnimatedContent(
        targetState = inBreak,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        label = "musicBreak",
    ) { showBreak ->
        if (!showBreak) {
            context()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                    // Strokes and geometry cached per size; the per-frame position read stays in
                    // draw, and its own layer keeps the re-record to the ring alone.
                    Spacer(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer()
                            .drawWithCache {
                                val stroke = 5.dp.toPx()
                                val track = Stroke(stroke)
                                val arc = Stroke(stroke, cap = StrokeCap.Round)
                                val topLeft = Offset(stroke / 2f, stroke / 2f)
                                val arcSize = Size(size.width - stroke, size.height - stroke)
                                val span = (breakInfo.anchorMs - breakInfo.fromMs).coerceAtLeast(1L).toFloat()
                                onDrawBehind {
                                    val left = ((breakInfo.anchorMs - position.longValue) / span).coerceIn(0f, 1f)
                                    drawArc(RING_TRACK, 0f, 360f, false, topLeft, arcSize, style = track)
                                    drawArc(palette.accent, -90f, 360f * left, false, topLeft, arcSize, style = arc)
                                }
                            },
                    )
                    Text(
                        text = secondsLeft.toString(),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.lyrics_sync_music_break),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The giant button. Stamps on pointer *down* with the event's own `uptimeMillis` (so the time is
 * the moment the finger landed, not when the frame ran); a hold ≥ 350 ms marks the word's end on
 * release. Extra fingers landing during a hold are taps too. Press feedback (scale 0.97, glow,
 * haptic) starts before any other work.
 */
@Composable
private fun TapPad(
    label: String,
    tip: String?,
    haptics: Boolean,
    palette: SyncEditorPalette,
    onDown: (Long) -> Int,
    onUp: (Int, Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnUp by rememberUpdatedState(onUp)
    val currentHaptics by rememberUpdatedState(haptics)
    val shape = palette.padShape

    fun pressFeedback() {
        if (currentHaptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        scope.launch { scale.animateTo(PRESSED_SCALE, PRESS_SPRING) }
        scope.launch {
            glow.snapTo(1f)
        }
    }

    fun releaseFeedback() {
        scope.launch { scale.animateTo(1f, RELEASE_SPRING) }
        scope.launch { glow.animateTo(0f, tween(220)) }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(shape)
            .background(palette.padContainer)
            .then(if (palette.rim != null) Modifier.border(1.dp, palette.rim, shape) else Modifier)
            .drawWithContent {
                drawContent()
                val g = glow.value
                if (g > 0f) drawRect(palette.onPad.copy(alpha = 0.10f * g))
            }
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick {
                    val now = SystemClock.uptimeMillis()
                    val index = currentOnDown(now)
                    currentOnUp(index, now, now)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressFeedback()
                    val index = currentOnDown(down.uptimeMillis)
                    var upTime = SystemClock.uptimeMillis()
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (change.id != down.id && change.pressed && !change.previousPressed) {
                                // A second finger while the first is held: its own tap.
                                change.consume()
                                pressFeedback()
                                currentOnDown(change.uptimeMillis)
                            }
                        }
                        val primary = event.changes.firstOrNull { it.id == down.id }
                        if (primary == null || !primary.pressed) {
                            upTime = primary?.uptimeMillis ?: SystemClock.uptimeMillis()
                            primary?.consume()
                            break
                        }
                        // A hold that drifts is still a hold: claim the moves so no ancestor
                        // (the player sheet's drag) turns it into a gesture.
                        primary.consume()
                    }
                    releaseFeedback()
                    currentOnUp(index, down.uptimeMillis, upTime)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            AnimatedContent(
                targetState = label,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "padLabel",
            ) { text ->
                Text(
                    text = text,
                    color = palette.onPad,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            AnimatedVisibility(visible = tip != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text = tip.orEmpty(),
                    color = palette.onPad.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private const val HOLD_TIP_TAPS = 20
private const val COMPACT_TOKENS = 12
private const val FILL_SWEEP_MS = 260
private const val BOX_POP_SCALE = 1.06f
private const val MUSIC_BREAK_MIN_MS = 5_000L
private const val MUSIC_BREAK_END_MS = 1_500L
private const val PRESSED_SCALE = 0.97f
private val NEXT_BLOCK_HEIGHT = 70.dp
private val PRESS_SPRING = spring<Float>(dampingRatio = 1f, stiffness = 1_400f)
private val RELEASE_SPRING = spring<Float>(dampingRatio = 0.7f, stiffness = 900f)

private const val BREAK_IDLE_POLL_MS = 150L
private val RING_TRACK = Color.White.copy(alpha = 0.16f)

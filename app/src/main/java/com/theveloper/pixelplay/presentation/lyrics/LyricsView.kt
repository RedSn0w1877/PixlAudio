package com.theveloper.pixelplay.presentation.lyrics

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLine
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyrics
import com.theveloper.pixelplay.presentation.lyrics.model.Row
import com.theveloper.pixelplay.ui.theme.LyricsDisplayFamily
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.withFrameNanos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How the karaoke lyrics look. Everything here changes rarely (a preference, the art, a system
 * setting), so it is read in composition; nothing in here is per-frame.
 *
 * @param fontFamily [LyricsDisplayFamily], or `null` (system) for scripts the app font lacks —
 *   only the family changes then, never the size.
 * @param textScale multiplier on the 34 sp main size (the user's lyrics text-size setting).
 * @param brightArt the artwork is bright (§1.2): normal blending, inactive alpha 0.50.
 * @param highContrast increased contrast (§1.2): normal blending, inactive 0.55, no gradient, no blur.
 * @param blurStrength multiplier on the §1.3 σ table (1 = the spec's 1.6 / 2.4 / … dp).
 */
@Immutable
data class KaraokeLyricsAppearance(
    val fontFamily: FontFamily? = LyricsDisplayFamily,
    val textScale: Float = 1f,
    val alignment: KaraokeAlignment = KaraokeAlignment.START,
    val brightArt: Boolean = false,
    val highContrast: Boolean = false,
    val blurEnabled: Boolean = true,
    val blurStrength: Float = 1f,
    val showTranslation: Boolean = true,
    val showRomanization: Boolean = true,
)

/**
 * The lyrics time base for a [KaraokeLyricsView]. [positionProvider] must be cheap and
 * frame-accurate (e.g. `PlayerViewModel.currentPositionForLyrics`), and is called on the main
 * thread once per frame; [offsetMsProvider] is the additive lyric sync offset.
 */
@Composable
fun rememberLyricsClock(positionProvider: () -> Long, offsetMsProvider: () -> Long = { 0L }): LyricsClock {
    val position by rememberUpdatedState(positionProvider)
    val offset by rememberUpdatedState(offsetMsProvider)
    return remember { LyricsClock(positionProvider = { position() }, offsetMsProvider = { offset() }) }
}

@Composable
fun rememberLyricsEngine(clock: LyricsClock): LyricsEngine = remember(clock) { LyricsEngine(clock) }

/** Idle poll while paused and settled: notices a seek or an offset change without running frames. */
private const val IDLE_POLL_MS = 150L

/**
 * Apple-style karaoke lyrics (spec §1, §3.4, §4, §5).
 *
 * One custom [Layout] composes every row once per lyrics change; per-line motion comes from
 * [engine] (a single `withFrameNanos` loop, no `Animatable` per line) and is applied in
 * placement (y) and in each row's layer block (scale, blur, presence). The whole list is drawn
 * into one offscreen layer composited with `BlendMode.Plus` (normal blending over bright art or
 * under increased contrast), with the edge fade applied as a `DstIn` mask in that same layer.
 *
 * Nothing here recomposes per frame: composition reads only [prepared], [appearance] and
 * [isPlaying].
 *
 * @param songKey identifies the song; a change runs the first-show cascade, a rebuild of the
 *   same song's lyrics snaps into place instead.
 * @param onSeekLine tap on a line (the caller seeks to `line.startMs − offset`).
 * @param topInset height of whatever overlays the top of this view (the header); the active
 *   line's anchor is kept at least 16 dp below it and the top fade covers it.
 * @param onInteraction any touch on the lyrics (e.g. to reset an immersive-mode timer).
 * @param footer optional content placed right after the last line (e.g. the lyrics source).
 */
@Composable
fun KaraokeLyricsView(
    prepared: PreparedLyrics,
    clock: LyricsClock,
    engine: LyricsEngine,
    isPlaying: Boolean,
    onSeekLine: (PreparedLine) -> Unit,
    modifier: Modifier = Modifier,
    songKey: Any? = null,
    appearance: KaraokeLyricsAppearance = KaraokeLyricsAppearance(),
    topInset: Dp = 0.dp,
    onInteraction: () -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val reducedMotion = remember(context) { isReducedMotion(context) }
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val onSeek by rememberUpdatedState(onSeekLine)
    val onTouch by rememberUpdatedState(onInteraction)

    // ---- engine inputs (no snapshot writes) ------------------------------------------------
    val config = LyricsEngineConfig(
        density = density.density,
        blurSupported = blurSupported,
        blurEnabled = appearance.blurEnabled && !appearance.highContrast,
        blurStrength = appearance.blurStrength,
        reducedMotion = reducedMotion,
    )
    if (engine.config != config) {
        engine.setConfig(config)
        wake.trySend(Unit)
    }
    SideEffect {
        if (clock.isPlaying != isPlaying) {
            clock.isPlaying = isPlaying
            wake.trySend(Unit)
        }
    }

    val songTracker = remember(engine) { SongTracker() }
    val motions = remember(engine, prepared) {
        val first = !songTracker.seen
        val songChanged = first || songTracker.key != songKey
        songTracker.seen = true
        songTracker.key = songKey
        if (songChanged) clock.reset()
        engine.setLyrics(prepared, animateIn = songChanged)
        wake.trySend(Unit)
        engine.rows
    }

    // ---- shared text style -----------------------------------------------------------------
    val style = remember(textMeasurer, density, appearance, prepared.hasDuet, reducedMotion) {
        buildRenderStyle(textMeasurer, density, appearance, prepared.hasDuet, reducedMotion)
    }

    // Pivots depend only on the alignment / duet layout, so a bright-art or blur change keeps
    // the measured heights and press state.
    val holder = remember(prepared, motions, appearance.alignment) { RowsHolder(prepared, motions, style) }
    val pressedRow = holder.pressedRow
    val clickLabel = stringResource(R.string.lyrics_play_from_here)
    val topInsetPx = with(density) { topInset.toPx() }
    val anchorMinPx = topInsetPx + with(density) { 16.dp.toPx() }
    val blurCache = remember { BlurCache() }

    // Per-row layer blocks: allocated once per lyrics, read only their own row's state.
    val layerBlocks = remember(holder, blurSupported) {
        Array(holder.rowCount) { r ->
            val m = holder.motions[r]
            val pivot = TransformOrigin(holder.pivots[r], 0.5f)
            val block: GraphicsLayerScope.() -> Unit = {
                val sc = m.scale
                scaleX = sc
                scaleY = sc
                transformOrigin = pivot
                val radius = m.blurRadiusPx
                renderEffect = if (blurSupported && radius > 0f) blurCache[radius] else null
                alpha = (m.depthAlpha * m.presence).coerceIn(0f, 1f)
                clip = false
            }
            block
        }
    }

    // ---- the single frame loop (§3.4) --------------------------------------------------------
    LaunchedEffect(engine, clock) {
        while (isActive) {
            if (!engine.needsFrame) {
                clock.rebase()
                while (isActive && !engine.needsFrame) {
                    val woke = withTimeoutOrNull(IDLE_POLL_MS) { wake.receive() } != null
                    if (woke) break
                    if (clock.peekMs() != clock.currentMs) break
                }
            }
            withFrameNanos { now ->
                clock.tick(now)
                engine.step(now)
            }
        }
    }

    val measurePolicy = remember(engine, holder, anchorMinPx) {
        MeasurePolicy { measurables, constraints ->
            val w = constraints.maxWidth
            val h = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
            val child = Constraints(minWidth = w, maxWidth = w)
            val placeables = measurables.map { it.measure(child) }
            var changed = false
            val n = holder.rowCount
            for (r in 0 until n) {
                val ph = placeables[r].height.toFloat()
                if (holder.heights[r] != ph) {
                    holder.heights[r] = ph
                    engine.setRowHeight(r, ph)
                    changed = true
                }
            }
            val anchor = max(h * ANCHOR_FRACTION, anchorMinPx)
            if (holder.viewportHeight != h.toFloat() || holder.anchor != anchor) {
                holder.viewportHeight = h.toFloat()
                holder.anchor = anchor
                engine.setViewport(h.toFloat(), anchor)
                changed = true
            }
            if (changed) wake.trySend(Unit)

            layout(w, h) {
                val off = engine.scrollOffset
                val margin = h * CULL_MARGIN_FRACTION
                var lastBottom = Float.NaN
                for (r in 0 until n) {
                    val m = holder.motions[r]
                    val y = m.y
                    if (y >= LyricsEngine.OFFSCREEN_Y / 2f) continue
                    val top = y + off
                    val p = placeables[r]
                    if (r == n - 1) lastBottom = top + p.height * holder.heightFactor(r)
                    if (top > h + margin || top + p.height < -margin) continue
                    p.placeWithLayer(0, top.roundToInt(), layerBlock = layerBlocks[r])
                }
                if (placeables.size > n && !lastBottom.isNaN() && lastBottom < h + margin) {
                    placeables[n].place(0, lastBottom.roundToInt())
                }
            }
        }
    }

    val blend = if (appearance.brightArt || appearance.highContrast) BlendMode.SrcOver else BlendMode.Plus

    Layout(
        content = {
            val lines = prepared.lines
            prepared.rows.forEachIndexed { r, row ->
                key(r) {
                    when (row) {
                        is Row.Line -> Spacer(
                            Modifier.then(
                                LyricLineElement(
                                    line = lines[row.lineIndex],
                                    motion = holder.motions[r],
                                    clock = clock,
                                    style = style,
                                    pressed = holder.pressedStates[r],
                                    clickLabel = clickLabel,
                                    onClick = holder.clickHandler(r) { lineIndex ->
                                        engine.onLineTapped(lineIndex)
                                        wake.trySend(Unit)
                                        onSeek(lines[lineIndex])
                                    },
                                )
                            )
                        )
                        is Row.Interlude -> Spacer(
                            Modifier.then(InterludeDotsElement(row, holder.motions[r], clock, style))
                        )
                    }
                }
            }
            footer?.invoke()
        },
        modifier = modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                blendMode = blend
            }
            .lyricsEdgeFade(topInsetPx)
            .semantics {
                collectionInfo = CollectionInfo(rowCount = holder.rowCount, columnCount = 1)
                scrollBy { _, y ->
                    engine.scrollBy(-y)
                    wake.trySend(Unit)
                    true
                }
                scrollToIndex { index ->
                    if (index in 0 until holder.rowCount) {
                        val top = holder.motions[index].y + engine.scrollOffset
                        engine.scrollBy(holder.anchor - top)
                        wake.trySend(Unit)
                    }
                    true
                }
            }
            .pointerInput(engine, holder) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onTouch()
                    val row = holder.hitRow(down.position.y, engine.scrollOffset)
                    val tappable = row >= 0 && prepared.rows[row] is Row.Line
                    if (tappable) pressedRow.intValue = row
                    var overSlop = 0f
                    val dragStart = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume()
                        overSlop = over
                    }
                    if (dragStart == null) {
                        pressedRow.intValue = -1
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (tappable && up != null && up.changedToUpIgnoreConsumed() && !up.isConsumed &&
                            up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                        ) {
                            up.consume()
                            val lineRow = prepared.rows[row] as Row.Line
                            engine.onLineTapped(lineRow.lineIndex)
                            wake.trySend(Unit)
                            onSeek(prepared.lines[lineRow.lineIndex])
                        }
                        return@awaitEachGesture
                    }
                    pressedRow.intValue = -1
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(dragStart)
                    engine.onDragStart()
                    engine.onDrag(overSlop)
                    wake.trySend(Unit)
                    val completed = verticalDrag(dragStart.id) { change ->
                        tracker.addPointerInputChange(change)
                        engine.onDrag(change.positionChange().y)
                        change.consume()
                        wake.trySend(Unit)
                    }
                    engine.onDragEnd(if (completed) tracker.calculateVelocity().y else 0f)
                    wake.trySend(Unit)
                }
            },
        measurePolicy = measurePolicy,
    )
}

/**
 * The lyrics edge fade (§1.1) as a `DstIn` mask: alpha 0 → 1 over the top
 * `max(10 % of the height, topInsetPx)`, and 1 → 0 over the bottom 12 %. Must sit inside an
 * offscreen layer (`CompositingStrategy.Offscreen`) so it masks only the lyrics.
 */
fun Modifier.lyricsEdgeFade(topInsetPx: Float = 0f): Modifier = drawWithCache {
    val h = size.height
    val top = if (h > 0f) (max(h * TOP_FADE_FRACTION, topInsetPx) / h).coerceIn(0f, 0.45f) else 0f
    val bottom = 1f - BOTTOM_FADE_FRACTION
    val mask = Brush.verticalGradient(
        0f to Color.Transparent,
        top to Color.Black,
        bottom to Color.Black,
        1f to Color.Transparent,
    )
    onDrawWithContent {
        drawContent()
        drawRect(mask, blendMode = BlendMode.DstIn)
    }
}

private const val ANCHOR_FRACTION = 0.25f
private const val CULL_MARGIN_FRACTION = 0.5f
private const val TOP_FADE_FRACTION = 0.10f
private const val BOTTOM_FADE_FRACTION = 0.12f

private class SongTracker {
    var seen = false
    var key: Any? = null
}

/** `RenderEffect` blur instances by quantised radius (0.25 px steps), so layers reuse them. */
private class BlurCache {
    private val cache = HashMap<Int, RenderEffect>()

    operator fun get(radiusPx: Float): RenderEffect {
        val k = (radiusPx * 4f).roundToInt()
        return cache.getOrPut(k) { BlurEffect(k / 4f, k / 4f, TileMode.Decal) }
    }
}

/** Per-lyrics bookkeeping for the view: measured heights, pivots, press state, hit testing. */
private class RowsHolder(
    prepared: PreparedLyrics,
    val motions: List<LyricRowMotion>,
    style: LyricsRenderStyle,
) {
    val rowCount = prepared.rows.size
    val heights = FloatArray(rowCount) { -1f }
    var viewportHeight = 0f
    var anchor = 0f
    private val collapsible = BooleanArray(rowCount)
    val pivots = FloatArray(rowCount)
    val pressedRow: MutableIntState = mutableIntStateOf(-1)
    val pressedStates: Array<State<Boolean>> = Array(rowCount) { r -> derivedStateOf { pressedRow.intValue == r } }
    private val clicks = arrayOfNulls<() -> Unit>(rowCount)
    private val lineOfRow = IntArray(rowCount) { -1 }

    init {
        prepared.rows.forEachIndexed { r, row ->
            when (row) {
                is Row.Line -> {
                    val line = prepared.lines[row.lineIndex]
                    lineOfRow[r] = row.lineIndex
                    collapsible[r] = line.isGroupedBackground
                    pivots[r] = style.pivotX(line)
                }
                is Row.Interlude -> {
                    collapsible[r] = true
                    pivots[r] = if (row.alignEnd) 1f else 0f
                }
            }
        }
    }

    /** Height multiplier (interlude / background expand) for layout and hit testing. */
    fun heightFactor(r: Int): Float = if (collapsible[r]) motions[r].expand.coerceIn(0f, 1f) else 1f

    fun clickHandler(r: Int, onLine: (Int) -> Unit): () -> Unit =
        clicks[r] ?: { onLine(lineOfRow[r]) }.also { clicks[r] = it }

    /** The row under [y] (px from the top of the view), or -1. */
    fun hitRow(y: Float, scrollOffset: Float): Int {
        for (r in 0 until rowCount) {
            val m = motions[r]
            val top = m.y + scrollOffset
            val h = heights[r].coerceAtLeast(0f) * heightFactor(r)
            if (h > 0f && y >= top && y < top + h) return r
        }
        return -1
    }
}

private fun buildRenderStyle(
    measurer: androidx.compose.ui.text.TextMeasurer,
    densityScope: Density,
    appearance: KaraokeLyricsAppearance,
    hasDuet: Boolean,
    reducedMotion: Boolean,
): LyricsRenderStyle {
    val size = MAIN_FONT_SP * appearance.textScale.coerceIn(0.6f, 2f)
    val main = TextStyle(
        color = Color.White,
        fontFamily = appearance.fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = size.sp,
        lineHeight = LyricsRenderStyle.LINE_HEIGHT_EM.em,
        letterSpacing = (-0.01).em,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        lineBreak = LineBreak.Heading,
        hyphens = Hyphens.None,
        textMotion = TextMotion.Animated,
    )
    val emPx = with(densityScope) { size.sp.toPx() }
    val density = densityScope.density
    val inactive = when {
        appearance.highContrast -> KaraokeAlpha.INACTIVE_HIGH_CONTRAST
        appearance.brightArt -> KaraokeAlpha.INACTIVE_BRIGHT_ART
        else -> KaraokeAlpha.INACTIVE
    }
    return LyricsRenderStyle(
        textMeasurer = measurer,
        main = main,
        background = main.copy(fontSize = (size * LyricsRenderStyle.BACKGROUND_EM).sp),
        translation = main.copy(
            fontSize = (size * LyricsRenderStyle.TRANSLATION_EM).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.em,
            lineBreak = LineBreak.Paragraph,
        ),
        romanization = main.copy(
            fontSize = (size * LyricsRenderStyle.ROMANIZATION_EM).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.em,
            lineBreak = LineBreak.Paragraph,
        ),
        emPx = emPx,
        density = density,
        padVerticalPx = 15f * density,
        padStartPx = 24f * density,
        padEndPx = 44f * density,
        extraGapPx = 4f * density,
        alignment = appearance.alignment,
        hasDuet = hasDuet,
        inactiveAlpha = inactive,
        highContrast = appearance.highContrast,
        reducedMotion = reducedMotion,
        showTranslation = appearance.showTranslation,
        showRomanization = appearance.showRomanization,
    )
}

private const val MAIN_FONT_SP = 34f

private fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

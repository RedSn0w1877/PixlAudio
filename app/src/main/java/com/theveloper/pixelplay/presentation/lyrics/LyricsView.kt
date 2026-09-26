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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.isSpecified
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
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
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
import com.theveloper.pixelplay.ui.theme.lyricsFamilyAtSize
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
fun rememberLyricsClock(positionProvider: LongSource, offsetMsProvider: LongSource = LongSource { 0L }): LyricsClock {
    val position by rememberUpdatedState(positionProvider)
    val offset by rememberUpdatedState(offsetMsProvider)
    return remember {
        LyricsClock(
            positionProvider = { position.getAsLong() },
            offsetMsProvider = { offset.getAsLong() },
        )
    }
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
 * @param topInset height of whatever overlays the top of this view (the header). With
 *   [topFadeLength] set, lines are fully hidden above it and fade in over that length below it
 *   (so they dissolve before they reach the chrome); otherwise the fade simply covers
 *   `max(10 %, topInset)`. The active line's anchor is kept at least 16 dp below the fade.
 * @param topFadeLength see [topInset]. [Dp.Unspecified] keeps the plain 10 % fade.
 * @param bottomInsetPx height (px, from the bottom) of whatever overlays the bottom of this view,
 *   read in the draw phase only, so it may animate. Lines are hidden below it and fade out over
 *   [bottomFadeLength] above it. Taps under either inset never seek.
 * @param bottomFadeLength [Dp.Unspecified] keeps the plain 12 % fade.
 * @param fadeAlpha extra alpha for the whole lyrics layer, read in the draw phase (a song-change
 *   fade). Applied to the offscreen layer itself, so `Plus` blending survives the fade.
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
    topFadeLength: Dp = Dp.Unspecified,
    bottomInsetPx: () -> Float = NoInset,
    bottomFadeLength: Dp = Dp.Unspecified,
    fadeAlpha: (() -> Float)? = null,
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
    // Keyed only on what [buildRenderStyle] reads. `brightArt` only moves the inactive alpha (a
    // draw value) and the blur prefs only reach the engine config, so a track change onto
    // brighter/darker art or a blur setting never replaces the style and re-measures the lines.
    val style = remember(
        textMeasurer, density, appearance.fontFamily, appearance.textScale, appearance.alignment,
        appearance.highContrast, appearance.showTranslation, appearance.showRomanization,
        prepared.hasDuet, reducedMotion,
    ) {
        buildRenderStyle(textMeasurer, density, appearance, prepared.hasDuet, reducedMotion)
    }
    val inactiveAlpha = inactiveAlphaFor(appearance)
    SideEffect { style.inactiveAlphaState.floatValue = inactiveAlpha }

    // Pivots depend only on the alignment / duet layout, so a bright-art or blur change keeps
    // the measured heights and press state.
    val holder = remember(prepared, motions, appearance.alignment) { RowsHolder(prepared, motions, style) }
    val pressedRow = holder.pressedRow
    val clickLabel = stringResource(R.string.lyrics_play_from_here)
    val topInsetPx = with(density) { topInset.toPx() }
    val topFadePx = if (topFadeLength.isSpecified) with(density) { topFadeLength.toPx() } else -1f
    val bottomFadePx = if (bottomFadeLength.isSpecified) with(density) { bottomFadeLength.toPx() } else -1f
    val anchorMinPx = topInsetPx + topFadePx.coerceAtLeast(0f) + with(density) { 16.dp.toPx() }
    val bottomInset by rememberUpdatedState(bottomInsetPx)
    val chromeTop by rememberUpdatedState(topInsetPx)
    val layerAlpha by rememberUpdatedState(fadeAlpha)
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
                // A row's text never overdraws itself, so modulating the alpha looks identical and
                // skips a per-row offscreen buffer (API 30 depth falloff: 8-10 rows below alpha 1).
                // A blurred row needs its layer anyway.
                compositingStrategy = if (renderEffect == null) CompositingStrategy.ModulateAlpha else CompositingStrategy.Auto
                clip = false
            }
            block
        }
    }

    // ---- the single frame loop (§3.4) --------------------------------------------------------
    val currentRows by rememberUpdatedState(holder)
    LaunchedEffect(engine, clock) {
        while (isActive) {
            if (!engine.needsFrame && !currentRows.fillPending) {
                clock.rebase()
                while (isActive && !engine.needsFrame && !currentRows.fillPending) {
                    val woke = withTimeoutOrNull(IDLE_POLL_MS) { wake.receive() } != null
                    if (woke) break
                    if (clock.peekMs() != clock.currentMs) break
                }
            }
            withFrameNanos { now ->
                clock.tick(now)
                engine.step(now)
                // Rows not measured yet: re-run this frame's measure pass for the next slice.
                val rows = currentRows
                if (rows.fillPending) rows.fillTick.intValue++
            }
        }
    }

    // Rows are measured window-first (see [RowsHolder.measureRows]): the rows the first layout
    // shows now, the rest a slice per frame, so a song's first frame never measures every line.
    val measurePolicy = remember(engine, clock, holder, anchorMinPx, style) {
        MeasurePolicy { measurables, constraints ->
            val w = constraints.maxWidth
            val h = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
            val child = Constraints(minWidth = w, maxWidth = w)
            val n = holder.rowCount
            holder.fillTick.intValue // observed: the frame loop bumps it while rows are pending
            var changed = false
            val anchor = max(h * ANCHOR_FRACTION, anchorMinPx)
            if (holder.viewportHeight != h.toFloat() || holder.anchor != anchor) {
                holder.viewportHeight = h.toFloat()
                holder.anchor = anchor
                engine.setViewport(h.toFloat(), anchor)
                changed = true
            }
            val anchorRow = engine.layoutAnchorRow.let { row ->
                // Before the first step: the row the first layout will anchor on. Not observed,
                // so the position provider's state never re-triggers this measure.
                if (row >= 0) row else Snapshot.withoutReadObservation { engine.predictScrollTargetRow(clock.peekMs()) }
            }
            if (holder.measureRows(measurables, child, h, anchor, anchorRow, engine, style, LyricsEngine.SNAP_MARGIN_DP * density.density)) {
                changed = true
            }
            val footer = if (measurables.size > n) measurables[n].measure(child) else null
            if (changed || holder.fillPending) wake.trySend(Unit)

            layout(w, h) {
                val off = engine.scrollOffset
                val margin = h * CULL_MARGIN_FRACTION
                var lastBottom = Float.NaN
                for (r in 0 until n) {
                    // Not measured yet: off-screen by construction (never shown unmeasured).
                    val p = holder.placeables[r] ?: continue
                    val m = holder.motions[r]
                    val y = m.y
                    if (y >= LyricsEngine.OFFSCREEN_Y / 2f) continue
                    val top = y + off
                    if (r == n - 1) lastBottom = top + p.height * holder.heightFactor(r)
                    if (top > h + margin || top + p.height < -margin) continue
                    p.placeWithLayer(0, top.roundToInt(), layerBlock = layerBlocks[r])
                }
                if (footer != null && !lastBottom.isNaN() && lastBottom < h + margin) {
                    footer.place(0, lastBottom.roundToInt())
                }
            }
        }
    }

    val blend = if (appearance.brightArt || appearance.highContrast) BlendMode.SrcOver else BlendMode.Plus
    val onLineTap: (Int) -> Unit = remember(engine, prepared) {
        { lineIndex ->
            engine.onLineTapped(lineIndex)
            wake.trySend(Unit)
            onSeek(prepared.lines[lineIndex])
        }
    }

    Layout(
        content = {
            // Its own restart scope: a play/pause, bright-art or inset change recomposes the view
            // without walking every row again.
            LyricRows(prepared, holder, clock, style, clickLabel, onLineTap)
            footer?.invoke()
        },
        modifier = modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                blendMode = blend
                this.alpha = layerAlpha?.invoke()?.coerceIn(0f, 1f) ?: 1f
            }
            .lyricsEdgeFade(
                topInsetPx = if (topFadePx >= 0f) topInsetPx else 0f,
                topFadePx = if (topFadePx >= 0f) topFadePx else -max(topInsetPx, 1f),
                bottomInsetPx = bottomInsetPx,
                bottomFadePx = bottomFadePx,
            )
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
                    // Lines hidden under the chrome (fully faded) are never tap targets.
                    val underChrome = down.position.y < chromeTop ||
                        down.position.y > size.height - bottomInset()
                    val tappable = !underChrome && row >= 0 && prepared.rows[row] is Row.Line
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
 * The lyrics edge fade (§1.1, glass spec §3) as a mask that must sit inside an offscreen layer
 * (`CompositingStrategy.Offscreen`) so it masks only the lyrics.
 *
 * - Top: everything above [topInsetPx] is cleared, then alpha 0 → 1 over [topFadePx]. A negative
 *   [topFadePx] means "10 % of the height, but at least `-topFadePx`" from the very top (the
 *   plain fade for a view with nothing over it).
 * - Bottom: everything below `height − bottomInsetPx()` is cleared, with alpha 1 → 0 over
 *   [bottomFadePx] above that edge (negative = 12 % of the height).
 *
 * [bottomInsetPx] is read in the draw phase, so an animating control cluster only replays this
 * draw; the gradients are built once per size and only translated, never re-allocated. A node
 * element (compared by value), so a recomposition of the caller keeps the cached gradients.
 */
fun Modifier.lyricsEdgeFade(
    topInsetPx: Float = 0f,
    topFadePx: Float = -1f,
    bottomInsetPx: () -> Float = NoInset,
    bottomFadePx: Float = -1f,
): Modifier = this then LyricsEdgeFadeElement(topInsetPx, topFadePx, bottomInsetPx, bottomFadePx)

private data class LyricsEdgeFadeElement(
    val topInsetPx: Float,
    val topFadePx: Float,
    val bottomInsetPx: () -> Float,
    val bottomFadePx: Float,
) : ModifierNodeElement<LyricsEdgeFadeNode>() {
    override fun create() = LyricsEdgeFadeNode(topInsetPx, topFadePx, bottomInsetPx, bottomFadePx)

    override fun update(node: LyricsEdgeFadeNode) {
        node.update(topInsetPx, topFadePx, bottomInsetPx, bottomFadePx)
    }
}

private class LyricsEdgeFadeNode(
    private var topInsetPx: Float,
    private var topFadePx: Float,
    private var bottomInsetPx: () -> Float,
    private var bottomFadePx: Float,
) : Modifier.Node(), DrawModifierNode {

    override val shouldAutoInvalidate: Boolean get() = false

    // Gradient cache, rebuilt only when the size or a fade input changes.
    private var cacheW = -1f
    private var cacheH = -1f
    private var topFade = 0f
    private var bottomFade = 0f
    private var topMask: Brush? = null
    private var bottomMask: Brush? = null

    fun update(topInsetPx: Float, topFadePx: Float, bottomInsetPx: () -> Float, bottomFadePx: Float) {
        if (topInsetPx != this.topInsetPx || topFadePx != this.topFadePx || bottomFadePx != this.bottomFadePx) {
            this.topInsetPx = topInsetPx
            this.topFadePx = topFadePx
            this.bottomFadePx = bottomFadePx
            cacheW = -1f
        }
        this.bottomInsetPx = bottomInsetPx
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val w = size.width
        val h = size.height
        if (w != cacheW || h != cacheH || topMask == null) {
            cacheW = w
            cacheH = h
            topFade = if (topFadePx >= 0f) topFadePx else max(h * TOP_FADE_FRACTION, -topFadePx).coerceAtMost(h * 0.45f)
            bottomFade = if (bottomFadePx >= 0f) bottomFadePx else h * BOTTOM_FADE_FRACTION
            topMask = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Color.Black,
                startY = topInsetPx,
                endY = topInsetPx + topFade.coerceAtLeast(1f),
            )
            // Drawn in its own 0..bottomFade space and translated to the (possibly moving) edge.
            bottomMask = Brush.verticalGradient(
                0f to Color.Black,
                1f to Color.Transparent,
                startY = 0f,
                endY = bottomFade.coerceAtLeast(1f),
            )
        }
        val top = topMask!!
        val bottom = bottomMask!!
        val topFade = topFade
        val bottomFade = bottomFade
        val topInsetPx = topInsetPx

        drawContent()
        if (topInsetPx > 0f) {
            drawRect(Color.Black, size = Size(w, topInsetPx), blendMode = BlendMode.Clear)
        }
        drawRect(top, topLeft = Offset(0f, topInsetPx), size = Size(w, topFade), blendMode = BlendMode.DstIn)
        val edge = (h - bottomInsetPx().coerceAtLeast(0f)).coerceIn(0f, h)
        translate(0f, edge - bottomFade) {
            drawRect(bottom, size = Size(w, bottomFade), blendMode = BlendMode.DstIn)
        }
        if (edge < h) {
            drawRect(Color.Black, topLeft = Offset(0f, edge), size = Size(w, h - edge), blendMode = BlendMode.Clear)
        }
    }
}

/**
 * The lyric rows, in their own restart scope so a recomposition of [KaraokeLyricsView] (play /
 * pause, bright art, insets) skips them. One empty layout per row carries its node.
 */
@Composable
private fun LyricRows(
    prepared: PreparedLyrics,
    holder: RowsHolder,
    clock: LyricsClock,
    style: LyricsRenderStyle,
    clickLabel: String,
    onLineTap: (Int) -> Unit,
) {
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
                            onClick = holder.clickHandler(r, onLineTap),
                        )
                    )
                )
                is Row.Interlude -> Spacer(
                    Modifier.then(InterludeDotsElement(row, holder.motions[r], clock, style))
                )
            }
        }
    }
}

/** A zero inset, shared so default arguments allocate nothing. */
val NoInset: () -> Float = { 0f }

private const val ANCHOR_FRACTION = 0.25f
private const val CULL_MARGIN_FRACTION = 0.5f
private const val TOP_FADE_FRACTION = 0.10f
private const val BOTTOM_FADE_FRACTION = 0.12f
/** Main-thread time a measure pass may spend filling in rows beyond the visible window. */
private const val FILL_BUDGET_NANOS = 2_000_000L

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
    /** The height the engine has for each row: measured, or an estimate while [measured] is false. */
    val heights = FloatArray(rowCount) { -1f }
    var viewportHeight = 0f
    var anchor = 0f

    // ---- window-first measuring ------------------------------------------------------------
    /** Measured at the current width and style (so [placeables] holds it and it may be placed). */
    private val measured = BooleanArray(rowCount)
    /** [heights] holds a placeholder, not a measurement at any width / style. */
    private val estimated = BooleanArray(rowCount)
    val placeables = arrayOfNulls<Placeable>(rowCount)
    private var measuredStyle: LyricsRenderStyle? = null
    private var measuredWidth = -1

    /** Some rows are still unmeasured: the frame loop keeps bumping [fillTick] until they are done. */
    var fillPending = true
        private set
    val fillTick: MutableIntState = mutableIntStateOf(0)
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

    /**
     * Measures the rows for one measure pass, window-first, so the first frame of a song (or of
     * a new width / style) never measures every line at once (each is 1-3 text layouts of a
     * large bold face, ~0.5 ms: a whole song was a 20-40 ms frame).
     *
     * 1. Rows measured before are measured again (a cached no-op unless the node asked).
     * 2. The window around [anchorRow] (the row the layout anchors at [anchorPx]): down until a
     *    row's top is past the viewport bottom (plus the anchor row's height, in case the target
     *    advances before the first step), and up until a row's bottom is above `-marginPx` (the
     *    engine's snap margin). Collapsible rows count as zero height, so the walk under-states
     *    distances and the window is never too small.
     * 3. Rows whose current position is near the viewport (a drag, a fling or a seek outrunning
     *    the fill).
     * 4. The nearest remaining rows while this pass stays under [FILL_BUDGET_NANOS].
     *
     * Rows left unmeasured get an estimated height (the mean measured line) and are neither
     * placed nor hit-tested. That can't change what is seen: the engine stacks rows from the
     * anchor (`targetY[r] = anchorY − (prefix[anchor] − prefix[r])`), so every row of the window
     * sits exactly where it would with all heights known; the row above the window has its
     * bottom (= the window's top, exact) past the snap margin, so the first layout and the
     * cascade treat all rows above as off-screen exactly as before; rows below start below the
     * viewport either way and only take stagger steps after the visible rows. When a real height
     * replaces an estimate, only off-screen rows move (and the engine snaps those).
     *
     * @return whether a height the engine sees changed.
     */
    fun measureRows(
        measurables: List<Measurable>,
        child: Constraints,
        viewportH: Int,
        anchorPx: Float,
        anchorRow: Int,
        engine: LyricsEngine,
        style: LyricsRenderStyle,
        marginPx: Float,
    ): Boolean {
        val start = System.nanoTime()
        var changed = false
        if (measuredStyle !== style || measuredWidth != child.maxWidth) {
            // Re-measure window-first; the old heights stand in for rows not re-measured yet.
            measuredStyle = style
            measuredWidth = child.maxWidth
            measured.fill(false)
            placeables.fill(null)
        }
        // 1.
        for (r in 0 until rowCount) {
            if (measured[r] && measureRow(measurables, r, child, engine)) changed = true
        }
        if (anchorRow !in 0 until rowCount) {
            // No anchor to measure around (can't happen with rows): measure everything.
            for (r in 0 until rowCount) {
                if (!measured[r] && measureRow(measurables, r, child, engine)) changed = true
            }
        } else {
            // 2.
            val offset = engine.rawScrollOffset
            if (!measured[anchorRow] && measureRow(measurables, anchorRow, child, engine)) changed = true
            val bottomLimit = viewportH + heights[anchorRow]
            var y = anchorPx + offset
            var r = anchorRow
            while (r < rowCount && y <= bottomLimit) {
                if (!measured[r] && measureRow(measurables, r, child, engine)) changed = true
                if (!collapsible[r]) y += heights[r]
                r++
            }
            y = anchorPx + offset
            r = anchorRow - 1
            while (r >= 0 && y >= -marginPx) {
                if (!measured[r] && measureRow(measurables, r, child, engine)) changed = true
                if (!collapsible[r]) y -= heights[r]
                r--
            }
            // 3.
            val near = viewportH + marginPx
            for (i in 0 until rowCount) {
                if (measured[i]) continue
                val springY = engine.rowSpringY(i)
                if (springY >= LyricsEngine.OFFSCREEN_Y / 2f) continue
                val top = springY + offset
                if (top > -near && top < near && measureRow(measurables, i, child, engine)) changed = true
            }
            // 4.
            var d = 1
            while (d < rowCount && System.nanoTime() - start < FILL_BUDGET_NANOS) {
                val below = anchorRow + d
                if (below < rowCount && !measured[below] && measureRow(measurables, below, child, engine)) changed = true
                val above = anchorRow - d
                if (above >= 0 && !measured[above] && System.nanoTime() - start < FILL_BUDGET_NANOS &&
                    measureRow(measurables, above, child, engine)
                ) changed = true
                d++
            }
        }

        // Estimates for the rest: only off-screen rows ever carry one.
        var pending = false
        var sum = 0f
        var count = 0
        for (r in 0 until rowCount) {
            if (!measured[r]) {
                pending = true
            } else if (!collapsible[r]) {
                sum += heights[r]
                count++
            }
        }
        if (pending) {
            val estimate = if (count > 0) sum / count else 2f * style.padVerticalPx + style.emPx * LyricsRenderStyle.LINE_HEIGHT_EM
            for (r in 0 until rowCount) {
                if (measured[r] || !(estimated[r] || heights[r] < 0f)) continue
                estimated[r] = true
                if (heights[r] != estimate) {
                    heights[r] = estimate
                    engine.setRowHeight(r, estimate)
                    changed = true
                }
            }
        }
        fillPending = pending
        return changed
    }

    private fun measureRow(measurables: List<Measurable>, r: Int, child: Constraints, engine: LyricsEngine): Boolean {
        val p = measurables[r].measure(child)
        placeables[r] = p
        measured[r] = true
        estimated[r] = false
        val ph = p.height.toFloat()
        if (heights[r] == ph) return false
        heights[r] = ph
        engine.setRowHeight(r, ph)
        return true
    }

    /** The row under [y] (px from the top of the view), or -1. Unmeasured rows are never hit. */
    fun hitRow(y: Float, scrollOffset: Float): Int {
        for (r in 0 until rowCount) {
            if (!measured[r]) continue
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
        fontFamily = lyricsFamilyAtSize(appearance.fontFamily, size.sp),
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
    val inactive = inactiveAlphaFor(appearance)
    return LyricsRenderStyle(
        textMeasurer = measurer,
        main = main,
        background = main.copy(
            fontSize = (size * LyricsRenderStyle.BACKGROUND_EM).sp,
            fontFamily = lyricsFamilyAtSize(appearance.fontFamily, (size * LyricsRenderStyle.BACKGROUND_EM).sp),
        ),
        translation = main.copy(
            fontSize = (size * LyricsRenderStyle.TRANSLATION_EM).sp,
            fontFamily = lyricsFamilyAtSize(appearance.fontFamily, (size * LyricsRenderStyle.TRANSLATION_EM).sp),
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.em,
            lineBreak = LineBreak.Paragraph,
        ),
        romanization = main.copy(
            fontSize = (size * LyricsRenderStyle.ROMANIZATION_EM).sp,
            fontFamily = lyricsFamilyAtSize(appearance.fontFamily, (size * LyricsRenderStyle.ROMANIZATION_EM).sp),
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

private fun inactiveAlphaFor(appearance: KaraokeLyricsAppearance): Float = when {
    appearance.highContrast -> KaraokeAlpha.INACTIVE_HIGH_CONTRAST
    appearance.brightArt -> KaraokeAlpha.INACTIVE_BRIGHT_ART
    else -> KaraokeAlpha.INACTIVE
}

private fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

package com.theveloper.pixelplay.presentation.lyrics

import android.graphics.LinearGradient
import android.graphics.Matrix
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLine
import com.theveloper.pixelplay.presentation.lyrics.model.VoiceRole
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Horizontal placement of the lyric lines (the "lyrics alignment" preference). */
enum class KaraokeAlignment { START, CENTER, END }

/**
 * Everything a line needs to measure and draw, shared by every row of one [KaraokeLyricsView].
 * Rebuilt only when a preference, the font size or the density changes; nodes compare it by
 * identity and re-measure when it is replaced.
 *
 * All lengths are px.
 */
@Stable
internal class LyricsRenderStyle(
    val textMeasurer: TextMeasurer,
    val main: TextStyle,
    val background: TextStyle,
    val translation: TextStyle,
    val romanization: TextStyle,
    /** Main line font size (1 em). */
    val emPx: Float,
    val density: Float,
    val padVerticalPx: Float,
    val padStartPx: Float,
    val padEndPx: Float,
    val extraGapPx: Float,
    val alignment: KaraokeAlignment,
    val hasDuet: Boolean,
    /**
     * Inactive line alpha at construction: 0.20, 0.50 over bright art, 0.55 under increased
     * contrast (§1.2). Draw-only, so it lives in snapshot state read in draw: the bright-art flag
     * flipping on a track change redraws the rows instead of replacing this style (which would
     * re-measure every line).
     */
    inactiveAlpha: Float,
    /** Increased contrast: no word gradient; unsung words 0.6, sung 1.0. */
    val highContrast: Boolean,
    /** No lift and no emphasis (activeness still fades). */
    val reducedMotion: Boolean,
    val showTranslation: Boolean,
    val showRomanization: Boolean,
) {
    val bgEmPx: Float = emPx * BACKGROUND_EM

    internal val inactiveAlphaState = androidx.compose.runtime.mutableFloatStateOf(inactiveAlpha)

    /** Snapshot read: call in draw only. */
    val inactiveAlpha: Float get() = inactiveAlphaState.floatValue

    /** §1.1: in duet songs lead lines keep 15% free on the end side, duet lines on the start side. */
    fun startPadding(role: VoiceRole, widthPx: Int): Float = when {
        hasDuet && role == VoiceRole.DUET -> max(padStartPx, widthPx * DUET_INSET_FRACTION)
        hasDuet -> padStartPx
        alignment == KaraokeAlignment.CENTER -> (padStartPx + padEndPx) / 2f
        alignment == KaraokeAlignment.END -> padEndPx
        else -> padStartPx
    }

    fun endPadding(role: VoiceRole, widthPx: Int): Float = when {
        hasDuet && role == VoiceRole.DUET -> padStartPx
        hasDuet -> max(padEndPx, widthPx * DUET_INSET_FRACTION)
        alignment == KaraokeAlignment.CENTER -> (padStartPx + padEndPx) / 2f
        alignment == KaraokeAlignment.END -> padStartPx
        else -> padEndPx
    }

    fun textAlign(role: VoiceRole): TextAlign = when {
        hasDuet -> if (role == VoiceRole.DUET) TextAlign.End else TextAlign.Start
        alignment == KaraokeAlignment.CENTER -> TextAlign.Center
        alignment == KaraokeAlignment.END -> TextAlign.End
        else -> TextAlign.Start
    }

    /** Scale pivot X (0 = start edge, 1 = end edge) for a line: duet, RTL and end-aligned lines pivot on the right. */
    fun pivotX(line: PreparedLine): Float {
        val rtl = isRtlText(line.text)
        return when {
            hasDuet && line.role == VoiceRole.DUET -> 1f
            hasDuet -> if (rtl) 1f else 0f
            alignment == KaraokeAlignment.CENTER -> 0.5f
            alignment == KaraokeAlignment.END -> 1f
            rtl -> 1f
            else -> 0f
        }
    }

    companion object {
        const val BACKGROUND_EM = 0.65f
        const val TRANSLATION_EM = 0.54f
        const val ROMANIZATION_EM = 0.64f
        const val LINE_HEIGHT_EM = 1.2059f
        const val DUET_INSET_FRACTION = 0.15f
        const val PRESS_RADIUS_EM = 0.25f
        const val PRESS_INSET_H_EM = 0.3f
        const val PRESS_INSET_V_EM = 0.15f
        const val HIGH_CONTRAST_UNSUNG = 0.6f

        /**
         * Scripts whose letters change shape with their neighbours (Arabic, Syriac, N'Ko,
         * Mongolian) or build clusters across syllables (Indic, Myanmar, Khmer, Tibetan). Cutting
         * such a word into separately measured pieces would break its shaping, so these lines
         * draw each piece as a clipped window onto the whole shaped line instead, and skip the
         * per-letter emphasis.
         */
        fun needsShapedPieces(text: String): Boolean {
            for (ch in text) {
                val c = ch.code
                if (c < 0x0590) continue
                if (c in 0x0600..0x08FF || c in 0x0900..0x0DFF || c in 0x0F00..0x0FFF ||
                    c in 0x1000..0x109F || c in 0x1780..0x18AF || c in 0xA8E0..0xA8FF ||
                    c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFF
                ) return true
            }
            return false
        }

        /** First strong directional character decides the line's direction. */
        fun isRtlText(text: String): Boolean {
            for (ch in text) {
                when (Character.getDirectionality(ch)) {
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                }
            }
            return false
        }
    }
}

/**
 * One lyric line as a single `Modifier.Node` (spec §5.2): measures its text once per width /
 * style, draws it, and exposes its semantics. Put it on an empty layout (a `Spacer`).
 *
 * Draw-phase reads: [LyricRowMotion.hot], [LyricRowMotion.activeness], the press state, and
 * [LyricsClock.nowMs] **only while the line is hot** — so only the one or two hot lines redraw
 * per frame. Position, scale, blur and presence are applied by the parent's layer block.
 */
internal data class LyricLineElement(
    val line: PreparedLine,
    val motion: LyricRowMotion,
    val clock: LyricsClock,
    val style: LyricsRenderStyle,
    val pressed: State<Boolean>,
    val clickLabel: String,
    val onClick: () -> Unit,
) : ModifierNodeElement<LyricLineNode>() {
    override fun create() = LyricLineNode(line, motion, clock, style, pressed, clickLabel, onClick)

    override fun update(node: LyricLineNode) {
        node.update(line, motion, clock, style, pressed, clickLabel, onClick)
    }
}

internal class LyricLineNode(
    private var line: PreparedLine,
    private var motion: LyricRowMotion,
    private var clock: LyricsClock,
    private var style: LyricsRenderStyle,
    private var pressed: State<Boolean>,
    private var clickLabel: String,
    private var onClick: () -> Unit,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode, SemanticsModifierNode {

    override val shouldAutoInvalidate: Boolean get() = false

    // ---- measure cache ----------------------------------------------------------------------
    private var measuredWidth = -1
    private var measuredHeight = 0
    private var layout: TextLayoutResult? = null
    private var romanLayout: TextLayoutResult? = null
    private var transLayout: TextLayoutResult? = null
    private var pieceStyle: TextStyle? = null
    private var textLeft = 0f
    private var textTop = 0f
    private var romanTop = 0f
    private var transTop = 0f
    private var hlLeft = 0f
    private var hlTop = 0f
    private var hlRight = 0f
    private var hlBottom = 0f

    /**
     * Word-sweep pieces (§5.2). Built off the hot frame: when the engine flags the row as about
     * to turn hot ([LyricRowMotion.prefetch]) a posted task builds them, so measuring the pieces
     * never lands on the line-change frame. Built inline only as a fallback (e.g. right after a
     * seek straight into the line).
     */
    private var segments: LineSegments? = null
    private var segmentsBuildPending = false
    /** Bumped on every re-measure, so a posted build for stale text is dropped. */
    private var measureGeneration = 0

    // ---- sweep brush cache --------------------------------------------------------------------
    private var sweepBrush: SweepBrush? = null
    private var sweepKey = Long.MIN_VALUE

    fun update(
        line: PreparedLine,
        motion: LyricRowMotion,
        clock: LyricsClock,
        style: LyricsRenderStyle,
        pressed: State<Boolean>,
        clickLabel: String,
        onClick: () -> Unit,
    ) {
        val old = this.line
        // Only what [measureText] reads. A timing-only change (the sync editor's Earlier/Later
        // nudge rebuilds every line with shifted times) keeps the text layouts and just rebuilds
        // the word pieces, which carry the times.
        val remeasure = style !== this.style || line.text != old.text || line.role != old.role ||
            line.romanization != old.romanization || line.translation != old.translation
        val retimed = !remeasure && line != old
        val redraw = remeasure || retimed || motion !== this.motion || clock !== this.clock || pressed !== this.pressed
        val semantics = line.text != old.text || clickLabel != this.clickLabel || onClick !== this.onClick
        this.line = line
        this.motion = motion
        this.clock = clock
        this.style = style
        this.pressed = pressed
        this.clickLabel = clickLabel
        this.onClick = onClick
        if (remeasure) {
            measuredWidth = -1
            measureGeneration++
            segments = null
            sweepBrush = null
            sweepKey = Long.MIN_VALUE
            invalidateMeasurement()
        } else if (retimed) {
            // Same text and style: layouts, heights and highlight extents stand. The pieces
            // (and the sweep brush keyed on them) are rebuilt with the new times, and a posted
            // build for the old times is dropped.
            measureGeneration++
            segments = null
            sweepBrush = null
            sweepKey = Long.MIN_VALUE
        }
        if (redraw) invalidateDraw()
        if (semantics) invalidateSemantics()
    }

    // =========================================================================================
    // Measure
    // =========================================================================================

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        if (width != measuredWidth || layout == null) measureText(width)
        val h = measuredHeight
        val placeable = measurable.measure(Constraints.fixed(width, h))
        return layout(width, h) { placeable.place(0, 0) }
    }

    private fun measureText(width: Int) {
        val s = style
        val isBg = line.role == VoiceRole.BACKGROUND
        val align = s.textAlign(line.role)
        val padStart = s.startPadding(line.role, width)
        val padEnd = s.endPadding(line.role, width)
        val contentWidth = (width - padStart - padEnd).roundToInt().coerceAtLeast(1)
        val textConstraints = Constraints(maxWidth = contentWidth)
        val base = if (isBg) s.background else s.main
        val mainStyle = base.copy(textAlign = align)
        pieceStyle = base.copy(textAlign = TextAlign.Start)

        val main = s.textMeasurer.measure(line.text, mainStyle, constraints = textConstraints)
        val roman = line.romanization?.takeIf { s.showRomanization && it.isNotBlank() }?.let {
            s.textMeasurer.measure(it, s.romanization.copy(textAlign = align), constraints = textConstraints)
        }
        val trans = line.translation?.takeIf { s.showTranslation && it.isNotBlank() }?.let {
            s.textMeasurer.measure(it, s.translation.copy(textAlign = align), constraints = textConstraints)
        }

        val padV = if (isBg) s.padVerticalPx * 0.5f else s.padVerticalPx
        textLeft = padStart
        textTop = padV
        var y = padV + main.size.height
        if (roman != null) {
            y += s.extraGapPx
            romanTop = y
            y += roman.size.height
        }
        if (trans != null) {
            y += s.extraGapPx
            transTop = y
            y += trans.size.height
        }
        val textBottom = y
        y += padV

        // Press highlight: the ink extent of every text block, inflated a little.
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        fun extent(l: TextLayoutResult) {
            for (i in 0 until l.lineCount) {
                minX = min(minX, l.getLineLeft(i))
                maxX = max(maxX, l.getLineRight(i))
            }
        }
        extent(main)
        roman?.let(::extent)
        trans?.let(::extent)
        if (minX > maxX) {
            minX = 0f
            maxX = 0f
        }
        val insetH = s.emPx * LyricsRenderStyle.PRESS_INSET_H_EM
        val insetV = s.emPx * LyricsRenderStyle.PRESS_INSET_V_EM
        hlLeft = (textLeft + minX - insetH).coerceAtLeast(0f)
        hlRight = (textLeft + maxX + insetH).coerceAtMost(width.toFloat())
        hlTop = (textTop - insetV).coerceAtLeast(0f)
        hlBottom = (textBottom + insetV).coerceAtMost(y)

        layout = main
        romanLayout = roman
        transLayout = trans
        measuredWidth = width
        measuredHeight = y.roundToInt()
        segments = null
        measureGeneration++
    }

    /** Builds the word pieces in a posted task (outside any draw pass), once. */
    private fun requestSegments() {
        if (segmentsBuildPending || !isAttached) return
        segmentsBuildPending = true
        val generation = measureGeneration
        coroutineScope.launch {
            segmentsBuildPending = false
            val main = layout
            if (segments == null && main != null && generation == measureGeneration) {
                segments = buildSegments(main)
            }
        }
    }

    // =========================================================================================
    // Draw
    // =========================================================================================

    override fun ContentDrawScope.draw() {
        val main = layout ?: return
        val s = style
        val m = motion

        if (pressed.value) {
            val r = s.emPx * LyricsRenderStyle.PRESS_RADIUS_EM
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(hlLeft, hlTop),
                size = Size(hlRight - hlLeft, hlBottom - hlTop),
                cornerRadius = CornerRadius(r, r),
                alpha = KaraokeAlpha.PRESS_HIGHLIGHT,
            )
        }

        val a = m.activeness
        val hot = m.hot
        val isBg = line.role == VoiceRole.BACKGROUND
        val inactive = s.inactiveAlpha
        val unsung: Float
        val sung: Float
        when {
            isBg -> {
                unsung = KaraokeAlpha.BACKGROUND_UNSUNG
                sung = KaraokeAlpha.lerp(KaraokeAlpha.BACKGROUND_UNSUNG, KaraokeAlpha.BACKGROUND_SUNG, a)
            }
            s.highContrast -> {
                unsung = KaraokeAlpha.lerp(inactive, LyricsRenderStyle.HIGH_CONTRAST_UNSUNG, a)
                sung = KaraokeAlpha.sung(a, inactive)
            }
            else -> {
                unsung = KaraokeAlpha.unsung(a, inactive)
                sung = KaraokeAlpha.sung(a, inactive)
            }
        }

        val animateWords = line.hasWordTiming && (hot || a > ACTIVENESS_EPS)
        if (!animateWords && line.hasWordTiming && segments == null && m.prefetch) requestSegments()
        if (animateWords) {
            val segs = segments ?: buildSegments(main).also { segments = it }
            // Hot: subscribe to the clock. Fading out: sample it without subscribing — the
            // activeness tween already invalidates this draw every frame until it settles.
            val t = if (hot) clock.nowMs else clock.currentMs
            drawSegments(segs, t, a, sung, unsung, isBg)
        } else {
            drawText(
                main,
                color = Color.White,
                topLeft = Offset(textLeft, textTop),
                alpha = if (line.hasWordTiming) unsung else sung,
            )
        }

        romanLayout?.let {
            drawText(it, color = Color.White, topLeft = Offset(textLeft, romanTop), alpha = unsung)
        }
        transLayout?.let {
            val active = max(KaraokeAlpha.TRANSLATION_ACTIVE, inactive + 0.15f)
            val inact = min(inactive, KaraokeAlpha.TRANSLATION_ACTIVE)
            drawText(it, color = Color.White, topLeft = Offset(textLeft, transTop), alpha = KaraokeAlpha.lerp(inact, active, a))
        }
    }

    private fun DrawScope.drawSegments(segs: LineSegments, t: Long, a: Float, sung: Float, unsung: Float, isBg: Boolean) {
        val s = style
        val em = if (isBg) s.bgEmPx else s.emPx
        val motionOn = !s.reducedMotion
        val fade = segs.fadePx
        val clip = segs.clipped
        for (i in 0 until segs.count) {
            val piece = segs.layouts[i]
            val x = segs.left[i]
            val y = segs.top[i]
            if (!segs.timed[i]) {
                if (clip) {
                    drawClippedPiece(segs, i, 0f, piece, 0, null, unsung, unsung)
                } else {
                    drawText(piece, color = Color.White, topLeft = Offset(x, y), alpha = unsung)
                }
                continue
            }
            val lift = if (motionOn) {
                EmphasisMath.liftEm(t, segs.liftStart[i], segs.liftDuration[i], isBg) * em * a
            } else 0f

            val start = segs.start[i]
            val end = segs.end[i]
            // 0 = solid unsung, 1 = solid sung, 2 = gradient.
            val mode = when {
                t >= end -> 1
                t < start -> 0
                s.highContrast -> 1
                else -> 2
            }
            var brush: SweepBrush? = null
            if (mode == 2) {
                val p = EmphasisMath.syllableProgress(t, start, end)
                val l = segs.sweepLeft[i]
                val r = segs.sweepRight[i]
                val rtl = segs.rtl[i]
                val xc = if (rtl) {
                    r + fade / 2f - (r - l + fade) * p
                } else {
                    EmphasisMath.sweepEdgeCenterPx(l, r, fade, p)
                }
                val ratio = if (sung > 0f) (unsung / sung).coerceIn(0f, 1f) else 1f
                brush = sweepBrush(fade, ratio, rtl)
                // Clipped pieces draw the whole line from its own origin, not from the piece.
                brush.moveTo(xc - fade / 2f - if (clip) 0f else x)
            }

            if (clip) {
                drawClippedPiece(segs, i, lift, piece, mode, brush, sung, unsung)
            } else if (motionOn && segs.emphasis[i]) {
                val du = segs.wordDurationEff[i]
                val n = segs.graphemeCount[i]
                val gi = segs.graphemeIndex[i]
                val charStart = EmphasisMath.graphemeStartMs(segs.wordStart[i], du, n, gi)
                val e = EmphasisMath.envelope(EmphasisMath.graphemeProgress(t, charStart, du))
                val amount = segs.amount[i]
                val sc = EmphasisMath.scale(e, amount)
                // Letters spread out from the word's visual middle: mirror the index for RTL.
                val visualIndex = if (segs.rtl[i]) n - 1 - gi else gi
                val dx = EmphasisMath.offsetXEm(e, amount, n, visualIndex) * em
                val dy = EmphasisMath.offsetYEm(e, amount) * em
                val hop = EmphasisMath.hopEm(t, charStart, du) * em * a
                val glowAlpha = EmphasisMath.glowAlpha(e, segs.glow[i]) * a
                val shadow = if (glowAlpha > 0.01f) segs.glowShadow(i, glowAlpha) else null
                val w = piece.size.width
                val h = piece.size.height
                withTransform({
                    translate(x + dx, y - lift + dy - hop)
                    scale(sc, sc, Offset(w / 2f, h / 2f))
                }) {
                    drawPiece(piece, mode, brush, sung, unsung, shadow)
                }
            } else {
                withTransform({ translate(x, y - lift) }) {
                    drawPiece(piece, mode, brush, sung, unsung, null)
                }
            }
        }
    }

    /** A piece of a shaped line: the whole line, clipped to the piece's box and lifted with it. */
    private fun DrawScope.drawClippedPiece(
        segs: LineSegments,
        i: Int,
        lift: Float,
        main: TextLayoutResult,
        mode: Int,
        brush: SweepBrush?,
        sung: Float,
        unsung: Float,
    ) {
        withTransform({
            translate(0f, -lift)
            clipRect(segs.sweepLeft[i], segs.top[i], segs.clipRight[i], segs.clipBottom[i])
        }) {
            val origin = Offset(textLeft, textTop)
            if (mode == 2 && brush != null) {
                drawText(main, brush = brush, topLeft = origin, alpha = sung)
            } else {
                drawText(main, color = Color.White, topLeft = origin, alpha = if (mode == 1) sung else unsung)
            }
        }
    }

    private fun DrawScope.drawPiece(
        piece: TextLayoutResult,
        mode: Int,
        brush: SweepBrush?,
        sung: Float,
        unsung: Float,
        shadow: Shadow?,
    ) {
        if (mode == 2 && brush != null) {
            drawText(piece, brush = brush, alpha = sung, shadow = shadow)
        } else {
            drawText(piece, color = Color.White, alpha = if (mode == 1) sung else unsung, shadow = shadow)
        }
    }

    /**
     * The cached soft-edge brush: one `LinearGradient(0 → fade, [sung, unsung])` whose local
     * matrix is the only thing that changes per frame. Its colours are relative (1 and
     * `unsung/sung`, with the draw alpha = `sung`), so it is rebuilt only when that ratio moves
     * by a 1/64 step — i.e. during the activeness tween, not during a sweep.
     */
    private fun sweepBrush(fade: Float, ratio: Float, rtl: Boolean): SweepBrush {
        val q = (ratio * 64f).roundToInt()
        val key = (q.toLong() shl 33) or ((if (rtl) 1L else 0L) shl 32) or (fade.toRawBits().toLong() and 0xffffffffL)
        val cached = sweepBrush
        if (cached != null && sweepKey == key) return cached
        val sungArgb = Color.White.toArgb()
        val unsungArgb = Color.White.copy(alpha = q / 64f).toArgb()
        val colors = if (rtl) intArrayOf(unsungArgb, sungArgb) else intArrayOf(sungArgb, unsungArgb)
        val shader = LinearGradient(0f, 0f, fade.coerceAtLeast(1f), 0f, colors, null, android.graphics.Shader.TileMode.CLAMP)
        return SweepBrush(shader).also {
            sweepBrush = it
            sweepKey = key
        }
    }

    // =========================================================================================
    // Word pieces
    // =========================================================================================

    private fun buildSegments(main: TextLayoutResult): LineSegments {
        val s = style
        val text = line.text
        val syllables = line.syllables.orEmpty()
        val pStyle = pieceStyle ?: s.main
        val isBg = line.role == VoiceRole.BACKGROUND
        val em = if (isBg) s.bgEmPx else s.emPx
        val lineHeight = em * LyricsRenderStyle.LINE_HEIGHT_EM
        val out = ArrayList<Piece>(syllables.size + 4)
        val covered = BooleanArray(text.length)
        val shaped = LyricsRenderStyle.needsShapedPieces(text)

        fun lineOf(offset: Int) = main.getLineForOffset(offset.coerceIn(0, (text.length - 1).coerceAtLeast(0)))

        /** Box of `[a, b)` that sits on one visual line, as (left, right) in text coordinates. */
        fun boxLeft(a: Int, b: Int): Float {
            val r1 = main.getBoundingBox(a)
            val r2 = main.getBoundingBox((b - 1).coerceAtLeast(a))
            return min(r1.left, r2.left)
        }

        fun boxRight(a: Int, b: Int): Float {
            val r1 = main.getBoundingBox(a)
            val r2 = main.getBoundingBox((b - 1).coerceAtLeast(a))
            return max(r1.right, r2.right)
        }

        fun rtlAt(a: Int) = main.getBidiRunDirection(a) == ResolvedTextDirection.Rtl

        /** Splits `[a, b)` into runs that each stay on one visual line. */
        fun runs(a: Int, b: Int, onRun: (Int, Int, Int) -> Unit) {
            var runStart = a
            var runLine = lineOf(a)
            for (c in a + 1 until b) {
                val l = lineOf(c)
                if (l != runLine) {
                    onRun(runStart, c, runLine)
                    runStart = c
                    runLine = l
                }
            }
            if (b > runStart) onRun(runStart, b, runLine)
        }

        var k = 0
        while (k < syllables.size) {
            val syl = syllables[k]
            val cs = syl.charStart.coerceIn(0, text.length)
            val ce = syl.charEnd.coerceIn(cs, text.length)
            if (syl.emphasis && !shaped) {
                var j = k
                while (j + 1 < syllables.size && syllables[j + 1].wordIndex == syl.wordIndex) j++
                val ws = cs
                val we = syllables[j].charEnd.coerceIn(ws, text.length)
                var wordEnd = syl.endMs
                for (q in k..j) wordEnd = max(wordEnd, syllables[q].endMs)
                val wordStart = syl.startMs
                val durationMs = wordEnd - wordStart
                val isLast = syl.wordIndex == line.lastWordIndex
                val duEff = EmphasisMath.effectiveDurationMs(durationMs, isLast)
                val amount = EmphasisMath.amount(durationMs, isLast)
                val glow = EmphasisMath.glow(durationMs, isLast)
                val glowRadius = LyricsBlurMath.cssShadowBlurToRadiusPx(
                    EmphasisMath.glowBlurEm(glow) * em / s.density, s.density
                )
                val bounds = EmphasisMath.graphemeBoundaries(text.substring(ws, we))
                var n = 0
                for (g in 0 until bounds.size - 1) {
                    if (text.substring(ws + bounds[g], ws + bounds[g + 1]).isNotBlank()) n++
                }
                var gi = 0
                for (g in 0 until bounds.size - 1) {
                    val ga = ws + bounds[g]
                    val gb = ws + bounds[g + 1]
                    if (text.substring(ga, gb).isBlank()) continue
                    var owner = syl
                    for (q in k..j) {
                        val o = syllables[q]
                        if (ga >= o.charStart && ga < o.charEnd) {
                            owner = o
                            break
                        }
                    }
                    val gl = lineOf(ga)
                    // Sweep box: the owning syllable's characters on this grapheme's visual line.
                    var sa = owner.charStart.coerceIn(0, text.length)
                    var sb = owner.charEnd.coerceIn(sa, text.length)
                    while (sa < ga && lineOf(sa) != gl) sa++
                    while (sb > gb && lineOf(sb - 1) != gl) sb--
                    out += Piece(
                        a = ga, b = gb, line = gl, timed = true,
                        start = owner.startMs, end = owner.endMs,
                        liftStart = owner.startMs, liftDuration = owner.endMs - owner.startMs,
                        sweepLeft = boxLeft(sa, sb), sweepRight = boxRight(sa, sb), rtl = rtlAt(ga),
                        emphasis = true, graphemeIndex = gi, graphemeCount = n.coerceAtLeast(1),
                        wordStart = wordStart, wordDurationEff = duEff, amount = amount, glow = glow,
                        glowRadius = glowRadius,
                    )
                    gi++
                }
                for (c in ws until we) covered[c] = true
                k = j + 1
                continue
            }

            if (ce > cs) {
                // Split across visual lines; each run gets a share of the time by width.
                var total = 0f
                runs(cs, ce) { a, b, _ -> total += boxRight(a, b) - boxLeft(a, b) }
                val duration = (syl.endMs - syl.startMs).coerceAtLeast(1L)
                var acc = syl.startMs.toFloat()
                runs(cs, ce) { a, b, l ->
                    val left = boxLeft(a, b)
                    val right = boxRight(a, b)
                    val share = if (total > 0f) (right - left) / total else 1f
                    val runStart = acc
                    acc += duration * share
                    out += Piece(
                        a = a, b = b, line = l, timed = true,
                        start = runStart.toLong(), end = acc.toLong().coerceAtLeast(runStart.toLong() + 1L),
                        liftStart = syl.startMs, liftDuration = syl.endMs - syl.startMs,
                        sweepLeft = left, sweepRight = right, rtl = rtlAt(a),
                    )
                }
                for (c in cs until ce) covered[c] = true
            }
            k++
        }

        // Text no syllable covers (punctuation, untimed tails): drawn in the unsung colour.
        var c = 0
        while (c < text.length) {
            if (covered[c] || text[c].isWhitespace()) {
                c++
                continue
            }
            var e = c + 1
            while (e < text.length && !covered[e] && !text[e].isWhitespace()) e++
            runs(c, e) { a, b, l ->
                out += Piece(
                    a = a, b = b, line = l, timed = false, start = Long.MAX_VALUE, end = Long.MAX_VALUE,
                    liftStart = 0L, liftDuration = 0L,
                    sweepLeft = boxLeft(a, b), sweepRight = boxRight(a, b), rtl = rtlAt(a),
                )
            }
            c = e
        }

        return LineSegments.from(
            out, text, main, pStyle, s.textMeasurer, textLeft, textTop,
            fadePx = EmphasisMath.fadeWidthPx(lineHeight), boxLeft = ::boxLeft, clipped = shaped,
        )
    }

    // =========================================================================================
    // Semantics
    // =========================================================================================

    override fun SemanticsPropertyReceiver.applySemantics() {
        text = AnnotatedString(line.text)
        onClick(label = clickLabel) {
            onClick()
            true
        }
    }

    private companion object {
        const val ACTIVENESS_EPS = 0.002f
    }
}

/** Soft-edge gradient; the shader instance never changes, only its local matrix. */
private class SweepBrush(private val shader: LinearGradient) : ShaderBrush() {
    private val matrix = Matrix()

    fun moveTo(x: Float) {
        matrix.setTranslate(x, 0f)
        shader.setLocalMatrix(matrix)
    }

    override fun createShader(size: Size): Shader = shader
}

/** Build-time description of one drawn piece of a word-synced line. */
private class Piece(
    val a: Int,
    val b: Int,
    val line: Int,
    val timed: Boolean,
    val start: Long,
    val end: Long,
    val liftStart: Long,
    val liftDuration: Long,
    val sweepLeft: Float,
    val sweepRight: Float,
    val rtl: Boolean,
    val emphasis: Boolean = false,
    val graphemeIndex: Int = 0,
    val graphemeCount: Int = 1,
    val wordStart: Long = 0L,
    val wordDurationEff: Float = 1f,
    val amount: Float = 0f,
    val glow: Float = 0f,
    val glowRadius: Float = 0f,
)

/**
 * The per-piece draw data of a hot word-synced line, in primitive arrays so the per-frame draw
 * allocates nothing. Coordinates are node-local px.
 */
private class LineSegments(
    val count: Int,
    val layouts: Array<TextLayoutResult>,
    val left: FloatArray,
    val top: FloatArray,
    val timed: BooleanArray,
    val start: LongArray,
    val end: LongArray,
    val liftStart: LongArray,
    val liftDuration: LongArray,
    val sweepLeft: FloatArray,
    val sweepRight: FloatArray,
    val rtl: BooleanArray,
    val emphasis: BooleanArray,
    val graphemeIndex: IntArray,
    val graphemeCount: IntArray,
    val wordStart: LongArray,
    val wordDurationEff: FloatArray,
    val amount: FloatArray,
    val glow: FloatArray,
    val glowRadius: FloatArray,
    val fadePx: Float,
    /** Shaped-script line: [layouts] all hold the full line, drawn clipped to each piece. */
    val clipped: Boolean,
    val clipRight: FloatArray,
    val clipBottom: FloatArray,
) {
    /** Glow shadows by alpha in 1/32 steps, per piece, built on first use: no per-frame allocation. */
    private val shadows = arrayOfNulls<Array<Shadow?>>(count)

    fun glowShadow(i: Int, alpha: Float): Shadow {
        val q = (alpha.coerceIn(0f, 1f) * SHADOW_STEPS).roundToInt()
        val row = shadows[i] ?: arrayOfNulls<Shadow>(SHADOW_STEPS + 1).also { shadows[i] = it }
        return row[q] ?: Shadow(Color.White.copy(alpha = q / SHADOW_STEPS.toFloat()), Offset.Zero, glowRadius[i])
            .also { row[q] = it }
    }

    companion object {
        private const val SHADOW_STEPS = 32

        fun from(
            pieces: List<Piece>,
            text: String,
            main: TextLayoutResult,
            pieceStyle: TextStyle,
            measurer: TextMeasurer,
            originX: Float,
            originY: Float,
            fadePx: Float,
            boxLeft: (Int, Int) -> Float,
            clipped: Boolean,
        ): LineSegments {
            val n = pieces.size
            val layouts = Array(n) { i ->
                val p = pieces[i]
                if (clipped) main else measurer.measure(text.substring(p.a, p.b), pieceStyle, softWrap = false, maxLines = 1)
            }
            return LineSegments(
                count = n,
                layouts = layouts,
                left = FloatArray(n) { originX + boxLeft(pieces[it].a, pieces[it].b) },
                top = FloatArray(n) { originY + main.getLineTop(pieces[it].line) },
                timed = BooleanArray(n) { pieces[it].timed },
                start = LongArray(n) { pieces[it].start },
                end = LongArray(n) { pieces[it].end },
                liftStart = LongArray(n) { pieces[it].liftStart },
                liftDuration = LongArray(n) { pieces[it].liftDuration },
                sweepLeft = FloatArray(n) { originX + pieces[it].sweepLeft },
                sweepRight = FloatArray(n) { originX + pieces[it].sweepRight },
                rtl = BooleanArray(n) { pieces[it].rtl },
                emphasis = BooleanArray(n) { pieces[it].emphasis },
                graphemeIndex = IntArray(n) { pieces[it].graphemeIndex },
                graphemeCount = IntArray(n) { pieces[it].graphemeCount },
                wordStart = LongArray(n) { pieces[it].wordStart },
                wordDurationEff = FloatArray(n) { pieces[it].wordDurationEff },
                amount = FloatArray(n) { pieces[it].amount },
                glow = FloatArray(n) { pieces[it].glow },
                glowRadius = FloatArray(n) { pieces[it].glowRadius },
                fadePx = fadePx,
                clipped = clipped,
                clipRight = FloatArray(n) { originX + pieces[it].sweepRight },
                clipBottom = FloatArray(n) { originY + main.getLineBottom(pieces[it].line) },
            )
        }
    }
}

package com.theveloper.pixelplay.presentation.lyrics

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.unit.Constraints
import com.theveloper.pixelplay.presentation.lyrics.model.Row
import kotlin.math.roundToInt

/**
 * The interlude pseudo-row (spec §1.6): three dots that expand in, fill one by one, breathe,
 * pulse just before the next line and collapse. Every value is a pure function of the lyrics
 * time ([InterludeTimeline]), so a seek into a gap recomputes from `t` and never animates from a
 * stale state.
 *
 * The node always measures to its **full** height (dots + 0.4 em margins); the engine multiplies
 * it by the expand factor when stacking rows, so the lines below slide rather than re-measure.
 * The clock is read in draw only while the gap is in progress ([LyricRowMotion.hot]).
 */
internal data class InterludeDotsElement(
    val row: Row.Interlude,
    val motion: LyricRowMotion,
    val clock: LyricsClock,
    val style: LyricsRenderStyle,
) : ModifierNodeElement<InterludeDotsNode>() {
    override fun create() = InterludeDotsNode(row, motion, clock, style)

    override fun update(node: InterludeDotsNode) {
        node.update(row, motion, clock, style)
    }
}

internal class InterludeDotsNode(
    private var row: Row.Interlude,
    private var motion: LyricRowMotion,
    private var clock: LyricsClock,
    private var style: LyricsRenderStyle,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode {

    override val shouldAutoInvalidate: Boolean get() = false

    private var width = 0

    fun update(row: Row.Interlude, motion: LyricRowMotion, clock: LyricsClock, style: LyricsRenderStyle) {
        val remeasure = style !== this.style || row.alignEnd != this.row.alignEnd
        this.row = row
        this.motion = motion
        this.clock = clock
        this.style = style
        if (remeasure) invalidateMeasurement()
        invalidateDraw()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val em = style.emPx
        val h = (em * (InterludeTimeline.DOT_SIZE_EM + 2f * InterludeTimeline.ROW_MARGIN_EM)).roundToInt()
        width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val placeable = measurable.measure(Constraints.fixed(width, h))
        return layout(width, h) { placeable.place(0, 0) }
    }

    override fun ContentDrawScope.draw() {
        if (!motion.hot) return
        val t = clock.nowMs
        val g0 = row.startMs
        val g1 = row.endMs
        val presence = InterludeTimeline.presence(t, g0, g1)
        if (presence <= 0.001f) return

        val s = style
        val em = s.emPx
        val dot = em * InterludeTimeline.DOT_SIZE_EM
        val gap = em * InterludeTimeline.DOT_GAP_EM
        val groupWidth = dot * InterludeTimeline.DOT_COUNT + gap * (InterludeTimeline.DOT_COUNT - 1)
        val alignEnd = row.alignEnd || s.alignment == KaraokeAlignment.END
        val left = when {
            alignEnd -> size.width - s.padStartPx - groupWidth
            s.alignment == KaraokeAlignment.CENTER && !s.hasDuet -> (size.width - groupWidth) / 2f
            else -> s.padStartPx
        }
        // Centre the dots in the row's *current* (expanding) height so they never overlap the
        // line below while it slides down.
        val cy = size.height * presence / 2f
        val pivot = Offset(
            when {
                alignEnd -> left + groupWidth
                s.alignment == KaraokeAlignment.CENTER && !s.hasDuet -> left + groupWidth / 2f
                else -> left
            },
            cy,
        )
        val groupScale = InterludeTimeline.scale(t, g0, g1)
        val groupAlpha = InterludeTimeline.alpha(t, g0, g1)
        val r = dot / 2f
        scale(groupScale, groupScale, pivot) {
            for (k in 0 until InterludeTimeline.DOT_COUNT) {
                val cx = left + r + k * (dot + gap)
                drawCircle(
                    color = Color.White,
                    radius = r,
                    center = Offset(cx, cy),
                    alpha = (groupAlpha * InterludeTimeline.dotAlpha(t, g0, g1, k)).coerceIn(0f, 1f),
                )
            }
        }
    }
}

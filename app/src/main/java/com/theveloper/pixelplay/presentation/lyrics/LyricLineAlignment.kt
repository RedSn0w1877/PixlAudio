package com.theveloper.pixelplay.presentation.lyrics

import androidx.compose.ui.text.style.TextAlign
import kotlin.math.max

/**
 * Where a lyric line sits horizontally: its side margins, its text alignment inside them and the
 * pivot it scales around. Pure, so the per-voice decisions are unit-tested
 * (`LyricLineAlignmentTest`); [LyricsRenderStyle] forwards to it.
 *
 * Duet songs (§1.1): lead lines sit flush left at the normal start margin and keep 15% of the
 * width free on the right; duet lines mirror that, flush right at the same margin. A background
 * vocal takes the side of the line it belongs to (`PreparedLine.duetEnd`). Duet sides are
 * physical (left / right), never start / end, so a right-to-left script cannot flip a singer to
 * the other side.
 *
 * The text is laid out at exactly the content width (see `LyricLineNode.measureText`): with a
 * looser width the paragraph would shrink to its own width and every alignment but "left" would
 * be lost for lines that fit on one row.
 */
internal object LyricLineAlignment {

    const val DUET_INSET_FRACTION = 0.15f

    fun startPadding(
        hasDuet: Boolean,
        alignment: KaraokeAlignment,
        duetEnd: Boolean,
        widthPx: Int,
        padStartPx: Float,
        padEndPx: Float,
    ): Float = when {
        hasDuet && duetEnd -> max(padStartPx, widthPx * DUET_INSET_FRACTION)
        hasDuet -> padStartPx
        alignment == KaraokeAlignment.CENTER -> (padStartPx + padEndPx) / 2f
        alignment == KaraokeAlignment.END -> padEndPx
        else -> padStartPx
    }

    fun endPadding(
        hasDuet: Boolean,
        alignment: KaraokeAlignment,
        duetEnd: Boolean,
        widthPx: Int,
        padStartPx: Float,
        padEndPx: Float,
    ): Float = when {
        hasDuet && duetEnd -> padStartPx
        hasDuet -> max(padEndPx, widthPx * DUET_INSET_FRACTION)
        alignment == KaraokeAlignment.CENTER -> (padStartPx + padEndPx) / 2f
        alignment == KaraokeAlignment.END -> padStartPx
        else -> padEndPx
    }

    fun textAlign(hasDuet: Boolean, alignment: KaraokeAlignment, duetEnd: Boolean): TextAlign = when {
        hasDuet -> if (duetEnd) TextAlign.Right else TextAlign.Left
        alignment == KaraokeAlignment.CENTER -> TextAlign.Center
        alignment == KaraokeAlignment.END -> TextAlign.End
        else -> TextAlign.Start
    }

    /** Scale pivot X (0 = left edge, 1 = right edge): the edge the line is aligned to. */
    fun pivotX(hasDuet: Boolean, alignment: KaraokeAlignment, duetEnd: Boolean, rtl: Boolean): Float = when {
        hasDuet -> if (duetEnd) 1f else 0f
        alignment == KaraokeAlignment.CENTER -> 0.5f
        alignment == KaraokeAlignment.END -> if (rtl) 0f else 1f
        rtl -> 1f
        else -> 0f
    }
}

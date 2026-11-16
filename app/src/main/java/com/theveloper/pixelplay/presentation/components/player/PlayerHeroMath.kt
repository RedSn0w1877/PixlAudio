package com.theveloper.pixelplay.presentation.components.player

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometry and pixel math for the now-playing hero (new UI, stage 3): the cover fills the top of
 * the player edge to edge, sharp at the top, then a progressively blurred copy melts it into the
 * player's background under the title. Pure Kotlin, no Android: unit-tested.
 *
 * Layout, in the hero's own pixels (width W, height H):
 * - the sharp cover is a W×W square at the top (no crop for square art);
 * - the hero ends [TAIL_FRACTION]·W below the title row's top, so `contentTop = H − tail`;
 * - the blurred copy fades in from [Stops.rampStart] to [Stops.rampEnd] (fully blurred before the
 *   title and never later than the bottom of the square, so no gap opens between the two layers);
 *   below the square it continues as the blurred cover mirrored, which reads as more of the blur;
 * - a background-colour gradient darkens the very top for the header, stays clear over the cover,
 *   then rises from the blur ramp to [TITLE_SCRIM] at the title and to solid at the hero's end.
 */
internal object PlayerHeroMath {
    /** How far the hero runs past the title row's top, as a fraction of the width. */
    const val TAIL_FRACTION = 0.45f

    /** Length of the sharp-to-blurred ramp, as a fraction of the width. */
    const val RAMP_FRACTION = 0.32f

    /** The blur is fully in this far (fraction of the width) above the title row. */
    const val RAMP_END_GAP_FRACTION = 0.02f

    /** The ramp never ends higher than this (fraction of the width): the top stays sharp. */
    const val MIN_RAMP_END_FRACTION = 0.30f

    /** The ramp ends by this fraction of the square, so the blur covers the square's bottom edge. */
    const val MAX_RAMP_END_FRACTION = 0.98f

    /** Background alpha at the very top, for the collapse / output / more buttons. */
    const val TOP_SCRIM = 0.35f

    /** The top scrim is gone by this fraction of the title row's top. */
    const val TOP_SCRIM_END_FRACTION = 0.14f

    /** Background alpha right at the title row (the mockup's 58 % → 78 % ramp sampled there). */
    const val TITLE_SCRIM = 0.40f

    /** Blur of the baked cover, in texels of the 96 px decode (≈ 5.7 % of the width on screen). */
    const val BLUR_SIGMA_TEXELS = 5.5f

    /** Saturation of the blurred copy (the mockup's `saturate(120%)`), baked into the pixels. */
    const val BLUR_SATURATION = 1.2f

    /** One set of positions for a hero of [width]×[height] px. */
    class Stops(
        val width: Float,
        val height: Float,
        val contentTop: Float,
        val rampStart: Float,
        val rampEnd: Float,
        val topScrimEnd: Float,
    ) {
        /** Fraction of [height] for [y], for gradient colour stops (0 when the hero is empty). */
        fun fraction(y: Float): Float = if (height <= 0f) 0f else (y / height).coerceIn(0f, 1f)
    }

    /** Height of the hero for a title row at [contentTop] in a player [width] px wide. */
    fun heroHeight(width: Int, contentTop: Int): Int =
        max(0, contentTop) + (width * TAIL_FRACTION).roundToInt()

    fun stops(width: Float, height: Float): Stops {
        val w = max(0f, width)
        val h = max(0f, height)
        val contentTop = max(0f, h - w * TAIL_FRACTION)
        val rampEnd = min(contentTop - w * RAMP_END_GAP_FRACTION, w * MAX_RAMP_END_FRACTION)
            .coerceAtLeast(w * MIN_RAMP_END_FRACTION)
        val rampStart = max(0f, rampEnd - w * RAMP_FRACTION)
        return Stops(
            width = w,
            height = h,
            contentTop = contentTop,
            rampStart = rampStart,
            rampEnd = rampEnd,
            topScrimEnd = min(contentTop * TOP_SCRIM_END_FRACTION, rampStart),
        )
    }

    /**
     * Raises the saturation of opaque ARGB [pixels] in place by [amount] (1 = unchanged), around
     * Rec.709 luma like CSS `saturate()`. Alpha is kept.
     */
    fun saturate(pixels: IntArray, amount: Float) {
        if (amount == 1f) return
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val nr = (luma + (r - luma) * amount).roundToInt().coerceIn(0, 255)
            val ng = (luma + (g - luma) * amount).roundToInt().coerceIn(0, 255)
            val nb = (luma + (b - luma) * amount).roundToInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    /**
     * Mean relative luminance (0..1, Rec.709 on sRGB-ish values) of rows [fromRow] until [toRow]
     * of a [size]-wide ARGB image: what the title sits on, for its GRAD bump.
     */
    fun meanLuma(pixels: IntArray, size: Int, fromRow: Int, toRow: Int): Float {
        if (size <= 0 || pixels.isEmpty()) return 0f
        val rows = pixels.size / size
        val from = fromRow.coerceIn(0, rows)
        val to = toRow.coerceIn(from, rows)
        if (to == from) return 0f
        var sum = 0.0
        for (y in from until to) {
            val row = y * size
            for (x in 0 until size) {
                val c = pixels[row + x]
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                sum += 0.2126f * r + 0.7152f * g + 0.0722f * b
            }
        }
        return (sum / ((to - from) * size)).toFloat()
    }

    /** Luminance under the title: the blurred cover with the background laid over at [TITLE_SCRIM]. */
    fun titleBackdropLuma(coverLuma: Float, backgroundLuma: Float): Float =
        coverLuma + (backgroundLuma - coverLuma) * TITLE_SCRIM

    /** Rubber band for a sideways drag on the hero: it follows at a third, up to a quarter width. */
    fun dragOffset(dx: Float, width: Float): Float {
        if (width <= 0f) return 0f
        val limit = width * 0.25f
        return (dx / 3f).coerceIn(-limit, limit)
    }
}

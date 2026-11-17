package com.theveloper.pixelplay.presentation.components.player

import com.theveloper.pixelplay.presentation.components.hero.HeroBlurMath

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

    /** Background alpha the scrim reaches at the hero's end, where the whole hero has faded out. */
    const val END_SCRIM = 0.62f

    /**
     * The whole hero fades out (to whatever the player draws behind it: the ambient background or
     * the plain colour) from this far below the title row's top (fraction of the width) to its end.
     * It used to end on a solid band of the background colour, which stopped in a hard line over the
     * ambient background (Hoa, 2026-11-16: "an abrupt line ruining the immersion").
     */
    const val FADE_START_GAP_FRACTION = 0.04f

    /**
     * The fade-out's opacity at evenly spaced points from [Stops.fadeStart] to the end: a
     * smootherstep, so neither end of the fade shows as an edge.
     */
    val FADE_OUT_ALPHAS: FloatArray = FloatArray(9) { i ->
        val t = i / 8f
        1f - t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    /** Blur of the baked cover (shared with the page heroes, see [HeroBlurMath]). */
    const val BLUR_SIGMA_TEXELS = HeroBlurMath.BLUR_SIGMA_TEXELS

    /** Saturation of the blurred copy (shared with the page heroes). */
    const val BLUR_SATURATION = HeroBlurMath.BLUR_SATURATION

    /** One set of positions for a hero of [width]×[height] px. */
    class Stops(
        val width: Float,
        val height: Float,
        val contentTop: Float,
        val rampStart: Float,
        val rampEnd: Float,
        val topScrimEnd: Float,
        /** Where the whole hero starts fading out (it is gone at [height]). */
        val fadeStart: Float = height,
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
            fadeStart = min(contentTop + w * FADE_START_GAP_FRACTION, h),
        )
    }

    /** See [HeroBlurMath.saturate]; kept here for the player's own callers and tests. */
    fun saturate(pixels: IntArray, amount: Float) = HeroBlurMath.saturate(pixels, amount)

    /** See [HeroBlurMath.meanLuma]. */
    fun meanLuma(pixels: IntArray, size: Int, fromRow: Int, toRow: Int): Float =
        HeroBlurMath.meanLuma(pixels, size, fromRow, toRow)

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

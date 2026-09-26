package com.theveloper.pixelplay.presentation.lyrics

import androidx.compose.animation.core.CubicBezierEasing
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Word-level motion maths for word-synced lines (spec §1.4): the karaoke sweep, the lift, and
 * the long-word emphasis ("glow"). Pure functions of time so the renderer can evaluate them in
 * draw without keeping any animation state, and a seek simply recomputes everything from `t`.
 *
 * Units: times in ms, lengths in `em` unless the name says px.
 */
object EmphasisMath {

    /** `ease-out` (0, 0, 0.58, 1): the lift, and the line activeness tweens. */
    val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** Rising half of the emphasis curve. */
    val EmphasisRise = CubicBezierEasing(0.2f, 0.4f, 0.58f, 1f)

    /** Falling half of the emphasis curve (applied as `1 - fall(x)`). */
    val EmphasisFall = CubicBezierEasing(0.3f, 0f, 0.58f, 1f)

    // ---- Sweep -----------------------------------------------------------------------------

    /** Soft-edge width of the karaoke fill: half a line height (AMLL's iPad value). */
    const val FADE_WIDTH_LINE_HEIGHTS = 0.5f

    fun fadeWidthPx(lineHeightPx: Float): Float = FADE_WIDTH_LINE_HEIGHTS * lineHeightPx

    /** Linear progress of `t` through `[startMs, endMs)`, clamped to 0..1. */
    fun syllableProgress(tMs: Long, startMs: Long, endMs: Long): Float {
        if (endMs <= startMs) return if (tMs >= startMs) 1f else 0f
        return ((tMs - startMs).toFloat() / (endMs - startMs).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Centre of the soft edge for a syllable box `[leftPx, rightPx]` at progress [p]: travels from
     * `L - fade/2` (all unsung) to `R + fade/2` (all sung), so the edge fully enters and leaves.
     */
    fun sweepEdgeCenterPx(leftPx: Float, rightPx: Float, fadePx: Float, p: Float): Float {
        val from = leftPx - fadePx / 2f
        val to = rightPx + fadePx / 2f
        return from + (to - from) * p
    }

    // ---- Lift ------------------------------------------------------------------------------

    const val LIFT_EM = 0.05f
    const val BACKGROUND_LIFT_EM = 0.10f
    const val LIFT_MIN_DURATION_MS = 1_000L

    /**
     * Upward lift of a syllable in em (positive = up): `0.05 em × easeOut(progress)` over
     * `max(1000, duration)`, 0.10 em for background vocals. The caller multiplies it by the
     * line's activeness so words settle back down as the line deactivates.
     */
    fun liftEm(tMs: Long, startMs: Long, durationMs: Long, background: Boolean = false): Float {
        val span = max(LIFT_MIN_DURATION_MS, durationMs).toFloat()
        val x = ((tMs - startMs).toFloat() / span).coerceIn(0f, 1f)
        return (if (background) BACKGROUND_LIFT_EM else LIFT_EM) * EaseOut.transform(x)
    }

    // ---- Emphasis --------------------------------------------------------------------------

    const val EMPHASIS_MIN_DURATION_MS = 1_000f
    const val LAST_WORD_AMOUNT_BOOST = 1.6f
    const val LAST_WORD_GLOW_BOOST = 1.5f
    const val LAST_WORD_DURATION_BOOST = 1.2f
    const val MAX_AMOUNT = 1.2f
    const val MAX_GLOW = 0.8f
    const val HOP_EM = 0.05f
    const val HOP_LEAD_MS = 400f
    const val HOP_DURATION_FACTOR = 1.4f

    /** `f(v) = v > 1 ? √v : v³` — gentle for short words, slowly growing for long ones. */
    fun strengthCurve(v: Float): Float = if (v > 1f) sqrt(v) else v * v * v

    /** Base duration `du = max(1000, wordDur)`, before any last-word boost. */
    fun baseDurationMs(wordDurationMs: Long): Float = max(EMPHASIS_MIN_DURATION_MS, wordDurationMs.toFloat())

    /** Effective animation duration: `du`, ×1.2 for the last word of the line. */
    fun effectiveDurationMs(wordDurationMs: Long, isLastWord: Boolean): Float =
        baseDurationMs(wordDurationMs) * (if (isLastWord) LAST_WORD_DURATION_BOOST else 1f)

    /** Scale / push strength: `f(du/2000) × 0.6` (×1.6 for the last word), capped at 1.2. */
    fun amount(wordDurationMs: Long, isLastWord: Boolean): Float {
        val du = baseDurationMs(wordDurationMs)
        val a = strengthCurve(du / 2000f) * 0.6f * (if (isLastWord) LAST_WORD_AMOUNT_BOOST else 1f)
        return min(MAX_AMOUNT, a)
    }

    /** Glow strength: `f(du/3000) × 0.5` (×1.5 for the last word), capped at 0.8. */
    fun glow(wordDurationMs: Long, isLastWord: Boolean): Float {
        val du = baseDurationMs(wordDurationMs)
        val g = strengthCurve(du / 3000f) * 0.5f * (if (isLastWord) LAST_WORD_GLOW_BOOST else 1f)
        return min(MAX_GLOW, g)
    }

    /** Start of grapheme [i] of [n]: `wordStart + (du / 2.5 / N) × i`. */
    fun graphemeStartMs(wordStartMs: Long, effectiveDurationMs: Float, n: Int, i: Int): Float =
        wordStartMs + (effectiveDurationMs / 2.5f / n.coerceAtLeast(1)) * i

    /** `x = clamp((t − charStart) / du)`. */
    fun graphemeProgress(tMs: Long, charStartMs: Float, effectiveDurationMs: Float): Float =
        ((tMs - charStartMs) / effectiveDurationMs).coerceIn(0f, 1f)

    /**
     * The emphasis envelope `e(x)`: rises with [EmphasisRise] over the first half, falls as
     * `1 − EmphasisFall` over the second. 0 at both ends, 1 at `x = 0.5`.
     */
    fun envelope(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return if (c < 0.5f) EmphasisRise.transform(c * 2f) else 1f - EmphasisFall.transform((c - 0.5f) * 2f)
    }

    /** Scale about the grapheme centre: `1 + e × 0.1 × amount`. */
    fun scale(e: Float, amount: Float): Float = 1f + e * 0.1f * amount

    /** Horizontal push in em: `−e × 0.03 × amount × (N/2 − i)`, spreading letters from the middle. */
    fun offsetXEm(e: Float, amount: Float, n: Int, i: Int): Float = -e * 0.03f * amount * (n / 2f - i)

    /** Vertical offset in em (negative = up): `−e × 0.025 × amount`. */
    fun offsetYEm(e: Float, amount: Float): Float = -e * 0.025f * amount

    /** Glow shadow alpha: `e × glow`. */
    fun glowAlpha(e: Float, glow: Float): Float = (e * glow).coerceIn(0f, 1f)

    /** CSS text-shadow blur of the glow in em: `min(0.3, glow × 0.3)`. Convert with [LyricsBlurMath.cssShadowBlurToRadiusPx]. */
    fun glowBlurEm(glow: Float): Float = min(0.3f, glow * 0.3f)

    /**
     * The extra hop in em (positive = up): `sin(π·x′) × 0.05`, with
     * `x′ = clamp((t − (charStart − 400)) / (du × 1.4))`. Added on top of the normal lift.
     */
    fun hopEm(tMs: Long, charStartMs: Float, effectiveDurationMs: Float): Float {
        val x = ((tMs - (charStartMs - HOP_LEAD_MS)) / (effectiveDurationMs * HOP_DURATION_FACTOR)).coerceIn(0f, 1f)
        return sin(PI.toFloat() * x) * HOP_EM
    }

    /** Peak scale reached by a word of this duration (the envelope's maximum is 1). */
    fun peakScale(wordDurationMs: Long, isLastWord: Boolean = false): Float = scale(1f, amount(wordDurationMs, isLastWord))

    /** Peak glow alpha reached by a word of this duration. */
    fun peakGlowAlpha(wordDurationMs: Long, isLastWord: Boolean = false): Float = glowAlpha(1f, glow(wordDurationMs, isLastWord))

    /**
     * Grapheme boundaries of [text] as offsets `[0, b1, …, text.length]` (so grapheme `i` is
     * `[out[i], out[i+1])`). Uses `java.text.BreakIterator`, so combining marks and surrogate
     * pairs stay whole.
     */
    fun graphemeBoundaries(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf(0)
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val out = ArrayList<Int>(text.length + 1)
        out += 0
        var b = iterator.next()
        while (b != BreakIterator.DONE) {
            out += b
            b = iterator.next()
        }
        return out.toIntArray()
    }
}

/**
 * Line and word alphas (spec §1.2). All text is white; brightness comes only from alpha.
 * `a` is the line's activeness in 0..1.
 */
object KaraokeAlpha {
    const val INACTIVE = 0.20f
    const val ACTIVE_LINE_ONLY = 1.0f
    const val UNSUNG_ACTIVE = 0.35f
    const val SUNG = 1.0f
    const val BACKGROUND_UNSUNG = 0.175f
    const val BACKGROUND_SUNG = 0.35f
    const val TRANSLATION_ACTIVE = 0.45f
    const val TRANSLATION_INACTIVE = 0.20f
    const val PRESS_HIGHLIGHT = 0.07f

    /** Inactive alpha for the bright-artwork exception (§1.2). */
    const val INACTIVE_BRIGHT_ART = 0.50f

    /** Inactive alpha under increased contrast (§1.2). */
    const val INACTIVE_HIGH_CONTRAST = 0.55f

    fun lerp(from: Float, to: Float, f: Float): Float = from + (to - from) * f

    /** How far the unsung words of the active line sit above the inactive lines, at least. */
    const val UNSUNG_MIN_LIFT = 0.10f

    /**
     * Unsung words of a word-synced line: `lerp(inactive, 0.35, a)`. The active target never
     * drops below `inactive + 0.10`, so over bright art (inactive 0.50) the active line's unsung
     * words brighten to 0.60 instead of dimming below the lines around them.
     */
    fun unsung(a: Float, inactive: Float = INACTIVE): Float =
        lerp(inactive, kotlin.math.max(UNSUNG_ACTIVE, inactive + UNSUNG_MIN_LIFT), a)

    /** Sung words, or a whole line-synced-only line: `lerp(inactive, 1.0, a)`. */
    fun sung(a: Float, inactive: Float = INACTIVE): Float = lerp(inactive, SUNG, a)

    fun translation(a: Float, inactive: Float = TRANSLATION_INACTIVE): Float = lerp(inactive, TRANSLATION_ACTIVE, a)
}

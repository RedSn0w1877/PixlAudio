package com.theveloper.pixelplay.ui.theme

import androidx.compose.runtime.Immutable
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The six variation axes of the bundled Google Sans Flex (`res/font/gflex_variable.ttf`), as its
 * `fvar` table declares them. Anything outside these ranges is clamped by the font anyway; the
 * toolkit clamps first so two specs that render identically also share one cache entry.
 *
 * Pure Kotlin on purpose: everything in this file is unit-tested without Android (see
 * `FlexTypeMathTest`). The Compose / Typeface side lives in `FlexType.kt`.
 */
object FlexAxis {
    /** `opsz` — optical size. 6 (caption cut: open, loose, sturdy) .. 144 (display cut: tight, high contrast). */
    const val OPSZ_MIN = 6f
    const val OPSZ_MAX = 144f
    const val OPSZ_DEFAULT = 18f

    /** `wdth` — width ("wideness"). 25 (ultra condensed) .. 151 (ultra expanded). */
    const val WDTH_MIN = 25f
    const val WDTH_MAX = 151f
    const val WDTH_DEFAULT = 100f

    /** `wght` — weight. 1 (hairline) .. 1000 (black). Changes advance widths, so it reflows text. */
    const val WGHT_MIN = 1f
    const val WGHT_MAX = 1000f
    const val WGHT_DEFAULT = 400f

    /** `GRAD` — grade. 0 .. 100: heavier strokes WITHOUT changing advance widths (no reflow). */
    const val GRAD_MIN = 0f
    const val GRAD_MAX = 100f

    /** `ROND` — roundness of terminals and corners. 0 (Google Sans) .. 100 (Google Sans Rounded). */
    const val ROND_MIN = 0f
    const val ROND_MAX = 100f

    /** `slnt` — slant in degrees. -10 (oblique) .. 0 (upright). */
    const val SLNT_MIN = -10f
    const val SLNT_MAX = 0f

    // Quantisation grid. Every spec is snapped to it before it becomes a cache key, which keeps
    // the number of distinct Typeface instances small and bounded however values are animated.
    // The steps sit below what the eye can tell apart at UI sizes.
    const val WGHT_STEP = 5f
    const val WDTH_STEP = 1f
    const val OPSZ_STEP = 1f
    const val GRAD_STEP = 5f
    const val ROND_STEP = 5f
    const val SLNT_STEP = 1f
}

/**
 * One point in Google Sans Flex's design space. Immutable and cheap; the Typeface it maps to is
 * built once per process by `FlexFonts` and keyed by [cacheKey].
 *
 * Defaults are the app's house style: ROND 100 like [GoogleSansRounded], everything else at the
 * font's own default.
 */
@Immutable
data class FlexSpec(
    val weight: Float = FlexAxis.WGHT_DEFAULT,
    val width: Float = FlexAxis.WDTH_DEFAULT,
    val opticalSize: Float = FlexAxis.OPSZ_DEFAULT,
    val grade: Float = 0f,
    val roundness: Float = FlexAxis.ROND_MAX,
    val slant: Float = 0f,
) {
    /** This spec clamped to the font's ranges and snapped to the [FlexAxis] grid. */
    fun quantized(): FlexSpec = FlexSpec(
        weight = FlexMath.snap(weight, FlexAxis.WGHT_STEP, FlexAxis.WGHT_MIN, FlexAxis.WGHT_MAX),
        width = FlexMath.snap(width, FlexAxis.WDTH_STEP, FlexAxis.WDTH_MIN, FlexAxis.WDTH_MAX),
        opticalSize = FlexMath.snap(opticalSize, FlexAxis.OPSZ_STEP, FlexAxis.OPSZ_MIN, FlexAxis.OPSZ_MAX),
        grade = FlexMath.snap(grade, FlexAxis.GRAD_STEP, FlexAxis.GRAD_MIN, FlexAxis.GRAD_MAX),
        roundness = FlexMath.snap(roundness, FlexAxis.ROND_STEP, FlexAxis.ROND_MIN, FlexAxis.ROND_MAX),
        slant = FlexMath.snap(slant, FlexAxis.SLNT_STEP, FlexAxis.SLNT_MIN, FlexAxis.SLNT_MAX),
    )

    /**
     * A single Long identifying the quantised spec: two specs with the same key draw identical
     * glyphs. Packed as weight/5 (8 bits) | width (8) | opsz (8) | grade/5 (5) | rond/5 (5) | -slnt (4).
     */
    val cacheKey: Long
        get() {
            val q = quantized()
            val w = (q.weight / FlexAxis.WGHT_STEP).roundToInt().toLong()
            val wd = q.width.roundToInt().toLong()
            val o = q.opticalSize.roundToInt().toLong()
            val g = (q.grade / FlexAxis.GRAD_STEP).roundToInt().toLong()
            val r = (q.roundness / FlexAxis.ROND_STEP).roundToInt().toLong()
            val s = (-q.slant).roundToInt().toLong()
            return (w shl 30) or (wd shl 22) or (o shl 14) or (g shl 9) or (r shl 4) or s
        }

    /** Linear interpolation of every axis toward [to]. */
    fun lerp(to: FlexSpec, t: Float): FlexSpec = FlexSpec(
        weight = FlexMath.lerp(weight, to.weight, t),
        width = FlexMath.lerp(width, to.width, t),
        opticalSize = FlexMath.lerp(opticalSize, to.opticalSize, t),
        grade = FlexMath.lerp(grade, to.grade, t),
        roundness = FlexMath.lerp(roundness, to.roundness, t),
        slant = FlexMath.lerp(slant, to.slant, t),
    )

    /** opsz tied to the size the text is drawn at — see [FlexMath.opticalSizeFor]. */
    fun atSize(fontSizeSp: Float): FlexSpec = copy(opticalSize = FlexMath.opticalSizeFor(fontSizeSp))

    /** The CSS-style variation string the platform takes (`'wght' 600.0, 'wdth' 100.0, ...`). */
    fun toVariationSettings(): String {
        val q = quantized()
        return "'wght' ${q.weight}, 'wdth' ${q.width}, 'opsz' ${q.opticalSize}, " +
            "'GRAD' ${q.grade}, 'ROND' ${q.roundness}, 'slnt' ${q.slant}"
    }
}

/**
 * How [FlexMath.fitWidthAxis] trades width for line count on hero titles (artist name, playlist
 * title, the player's song title). The font size never changes; only `wdth` does.
 *
 * @property minWidth narrowest cut allowed (75 still reads as the same face, below that it turns
 *   into a different, poster-like voice).
 * @property maxWidth widest cut for short titles (125: presence without looking stretched).
 * @property multiLineMaxWidth widest cut once the title has to wrap; wide letters over two lines
 *   make a heavy block, so wrapped titles cap at the regular width.
 * @property maxLines lines the title may use before it is ellipsised.
 * @property step granularity of the multi-line search (and therefore of the cache).
 */
@Immutable
data class FlexFitPolicy(
    val minWidth: Float = 75f,
    val maxWidth: Float = 125f,
    val multiLineMaxWidth: Float = 100f,
    val maxLines: Int = 2,
    val step: Float = 5f,
) {
    init {
        require(minWidth <= multiLineMaxWidth && multiLineMaxWidth <= maxWidth) { "minWidth <= multiLineMaxWidth <= maxWidth" }
        require(maxLines >= 1) { "maxLines >= 1" }
        require(step > 0f) { "step > 0" }
    }

    /** Multi-line candidates, widest first: multiLineMaxWidth, -step, ..., minWidth. */
    fun multiLineCandidates(): FloatArray {
        val count = floor((multiLineMaxWidth - minWidth) / step).toInt() + 1
        val out = FloatArray(count) { multiLineMaxWidth - it * step }
        if (out.last() > minWidth) return out + minWidth
        return out
    }

    /**
     * [axis] snapped DOWN onto the single-line grid (minWidth + k·step, and maxWidth itself), so a
     * fitted title never needs an instance outside [widthGrid]. Down, not nearest: narrower still
     * fits.
     */
    fun snapDown(axis: Float): Float {
        if (axis >= maxWidth) return maxWidth
        if (axis <= minWidth) return minWidth
        val k = floor((axis - minWidth) / step)
        return (minWidth + k * step).coerceIn(minWidth, maxWidth)
    }

    /**
     * Every wdth the fit can measure or settle at: the single-line grid from minWidth to maxWidth
     * and the multi-line candidates (11 values with the defaults). Warm these and a fit never
     * builds a font in layout.
     */
    fun widthGrid(): FloatArray {
        val out = LinkedHashSet<Float>()
        var k = 0
        while (true) {
            val w = minWidth + k * step
            if (w >= maxWidth) break
            out += w
            k++
        }
        out += maxWidth
        for (c in multiLineCandidates()) out += c
        return out.toFloatArray()
    }
}

/** Pure helpers behind the Flex type toolkit. No Android, no Compose runtime, no allocation. */
object FlexMath {

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Snaps [value] to a multiple of [step] and clamps it to [min]..[max]. */
    fun snap(value: Float, step: Float, min: Float, max: Float): Float {
        val snapped = (value / step).roundToInt() * step
        return snapped.coerceIn(min, max)
    }

    /**
     * opsz for text drawn at [fontSizeSp]: the size itself, rounded and clamped to 6..144.
     * Google Sans Flex's optical axis is calibrated in points at 1x, which is what sp is, so
     * opsz = size gives 48 sp headlines the tight display cut and 11 sp labels the open text cut.
     */
    fun opticalSizeFor(fontSizeSp: Float): Float =
        if (fontSizeSp.isNaN() || fontSizeSp <= 0f) FlexAxis.OPSZ_DEFAULT
        else fontSizeSp.roundToInt().toFloat().coerceIn(FlexAxis.OPSZ_MIN, FlexAxis.OPSZ_MAX)

    /**
     * The step of an N-step ramp closest to an animated [fraction]. Spring overshoot (fraction
     * outside 0..1) is clamped, so a bouncy spec cannot index out of the ramp.
     */
    fun stepIndex(fraction: Float, steps: Int): Int {
        if (steps <= 1) return 0
        val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        return (f * (steps - 1)).roundToInt()
    }

    /** The fraction (0..1) at which ramp step [index] sits. */
    fun stepFraction(index: Int, steps: Int): Float =
        if (steps <= 1) 0f else index.coerceIn(0, steps - 1).toFloat() / (steps - 1)

    /**
     * Visual scale that turns a ramp step's font size into the continuous size at [fraction]
     * (applied in graphicsLayer, so size moves every frame while the font only changes per step).
     */
    fun continuousScale(fraction: Float, fromSize: Float, toSize: Float, steps: Int): Float {
        val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        val stepSize = lerp(fromSize, toSize, stepFraction(stepIndex(f, steps), steps))
        if (stepSize <= 0f) return 1f
        return lerp(fromSize, toSize, f) / stepSize
    }

    /**
     * Widest `wdth` in [lo]..[hi] at which a single line fits [available] px, from the line's
     * width measured at both ends (advance widths of Google Sans Flex grow close to linearly with
     * `wdth`). Returns [hi] when even the widest cut fits and `null` when even the narrowest does
     * not. The result is floored to a whole axis unit so the linear estimate errs toward fitting.
     */
    fun solveSingleLineWidth(widthAtLo: Float, widthAtHi: Float, lo: Float, hi: Float, available: Float): Float? {
        if (available <= 0f) return null
        if (widthAtHi <= available) return hi
        if (widthAtLo > available) return null
        val span = widthAtHi - widthAtLo
        if (span <= 0f) return lo
        val axis = lo + (available - widthAtLo) * (hi - lo) / span
        return floor(axis).coerceIn(lo, hi)
    }

    /**
     * Binary search over [candidates] (widest first, so "fits" is monotone false..true) for the
     * first one that [fits]. Returns the index, or -1 when none fits. At most log2(n)+1 probes.
     */
    inline fun firstFitting(candidates: FloatArray, fits: (Float) -> Boolean): Int {
        var lo = 0
        var hi = candidates.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (fits(candidates[mid])) {
                found = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return found
    }

    /**
     * GRAD for text over artwork. Light text over a bright backdrop loses its edges, so strokes
     * get heavier as the backdrop's relative luminance climbs from 0.25 to 0.75 (0 → [maxGrade]).
     * GRAD keeps advance widths, so the bump never reflows a line. Dark text, or a dark backdrop,
     * gets 0. Snapped to steps of 10 so a changing backdrop reuses a handful of typefaces.
     */
    fun gradeForBackdrop(backdropLuminance: Float, lightText: Boolean, maxGrade: Float = 60f): Float {
        if (!lightText || backdropLuminance.isNaN()) return 0f
        val t = ((backdropLuminance - 0.25f) / 0.5f).coerceIn(0f, 1f)
        return snap(t * maxGrade, 10f, FlexAxis.GRAD_MIN, maxGrade.coerceIn(FlexAxis.GRAD_MIN, FlexAxis.GRAD_MAX))
    }
}

/**
 * Named points in the design space, so screens share one vocabulary instead of ad-hoc numbers.
 * opsz is left at the default here; the Compose helpers tie it to the drawn size.
 */
object FlexPresets {
    /** Nav label at rest: medium, regular width. */
    val NavLabel = FlexSpec(weight = 500f, width = 100f)

    /** Nav label selected: heavier AND wider, so the selection reads as "stepping forward". */
    val NavLabelSelected = FlexSpec(weight = 720f, width = 112f)

    /** Hero display (artist / playlist / song title at full size): heavy, tight display cut. */
    val HeroDisplay = FlexSpec(weight = 760f, width = 100f)

    /** Hero collapsed into a top bar: lighter and condensed so long names still fit. */
    val HeroCollapsed = FlexSpec(weight = 620f, width = 84f)

    /** Playful chips and small labels (quality tier, filters): fully round, a touch wide. */
    val Chip = FlexSpec(weight = 620f, width = 108f, roundness = 100f)
}

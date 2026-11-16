package com.theveloper.pixelplay.presentation.components.hero

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pixel and geometry math shared by the progressively blurred heroes of the new UI (now playing,
 * artist, playlist): the picture fills the top of the screen edge to edge, sharp at the top, then a
 * pre-blurred copy fades in and a background-colour scrim melts it into the page. Pure Kotlin, no
 * Android: unit-tested in `HeroBlurMathTest`.
 */
internal object HeroBlurMath {
    /** Blur of the baked copy, in texels of the 96 px decode (≈ 5.7 % of the width on screen). */
    const val BLUR_SIGMA_TEXELS = 5.5f

    /** Saturation of the blurred copy (the mockups' `saturate(120%)`), baked into the pixels. */
    const val BLUR_SATURATION = 1.2f

    /**
     * Where a [srcW]×[srcH] picture lands when centre-cropped into a [dstW]×[dstH] box (what
     * `ContentScale.Crop` does): the scale and the offset of its top-left corner. Lets the blurred
     * copy line up texel for texel with the sharp picture drawn by Coil.
     */
    class Crop(val scale: Float, val dx: Float, val dy: Float)

    fun centerCrop(srcW: Float, srcH: Float, dstW: Float, dstH: Float): Crop {
        if (srcW <= 0f || srcH <= 0f || dstW <= 0f || dstH <= 0f) return Crop(1f, 0f, 0f)
        val scale = max(dstW / srcW, dstH / srcH)
        return Crop(scale, (dstW - srcW * scale) / 2f, (dstH - srcH * scale) / 2f)
    }

    /**
     * A full-bleed page hero (artist, playlist) as fractions of its own height, from the approved
     * mockups: the blurred copy fades in from [PAGE_RAMP_START] to [PAGE_RAMP_END]; the scrim (background
     * colour as an alpha mask) darkens the top for the header buttons, clears over the picture,
     * then climbs to solid at the bottom where the page continues.
     */
    const val PAGE_RAMP_START = 0.44f
    const val PAGE_RAMP_END = 0.66f
    val PAGE_SCRIM_STOPS = floatArrayOf(0f, 0.18f, 0.38f, 0.60f, 0.80f, 1f)
    val PAGE_SCRIM_ALPHAS = floatArrayOf(0.40f, 0f, 0.38f, 0.58f, 0.82f, 1f)

    /** Scrim alpha of the page hero at [fraction] of its height (linear between the stops). */
    fun pageScrimAt(fraction: Float): Float {
        val stops = PAGE_SCRIM_STOPS
        val alphas = PAGE_SCRIM_ALPHAS
        val f = fraction.coerceIn(0f, 1f)
        for (i in 1 until stops.size) {
            if (f <= stops[i]) {
                val span = stops[i] - stops[i - 1]
                val t = if (span <= 0f) 1f else (f - stops[i - 1]) / span
                return alphas[i - 1] + (alphas[i] - alphas[i - 1]) * t
            }
        }
        return alphas.last()
    }

    /**
     * Luminance under a title drawn at [fraction] of a page hero: the blurred picture with the
     * background laid over it at the scrim's alpha there.
     */
    fun pageBackdropLuma(coverLuma: Float, backgroundLuma: Float, fraction: Float): Float {
        val a = pageScrimAt(fraction)
        return coverLuma + (backgroundLuma - coverLuma) * a
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
     * of a [size]-wide ARGB image: what a title sits on, for its GRAD bump.
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
}

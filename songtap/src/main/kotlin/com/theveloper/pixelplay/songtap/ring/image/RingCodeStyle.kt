package com.theveloper.pixelplay.songtap.ring.image

import com.theveloper.pixelplay.songtap.ring.RingCodeLayout
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The ring code's colours, from the device colour, with the contrast rule. Pure, but floating point (it
 * lives in `ring/image`, which PurityTest lets use `kotlin.math`): the maths is CIE L*a*b* ("tone" is L*,
 * the same number Material's HCT calls tone) and WCAG relative luminance. The app passes the device colour
 * as an ARGB Int (the primary role of the dynamic scheme) and gets ARGB Ints back; nothing here knows
 * Android.
 *
 * Hoa's rules (2026-11-20): the dots are the device colour; the plate is a light tone of the same hue (tone
 * 95 in light and in dark mode, because a camera reads dark dots on a light plate best); the dots keep at
 * least 7:1 contrast against the plate, so a pale device colour is darkened (tone by tone, toward 30) until
 * it passes; the orange start dot is the one fixed colour.
 *
 * The Swift port reproduces the formulas (sRGB transfer function, D65 matrices, Lab) and is checked by
 * properties (contrast >= 7, plate tone 95 +- 1), not by golden vectors.
 */
object RingCodeStyle {
    const val PLATE_TONE = 95.0
    const val MIN_CONTRAST = 7.0
    const val DARKEST_TONE = 30.0
    /** The plate keeps a hint of the device colour: at most this much chroma (Lab C*). */
    const val PLATE_MAX_CHROMA = 8.0
    const val BIG_TO_SMALL_RADIUS = 2.0

    /** The colours of one code. [clamped] is true when the device colour had to be darkened. */
    class Palette(
        val dot: Int,
        val plate: Int,
        val marker: Int,
        val dotTone: Double,
        val plateTone: Double,
        val contrast: Double,
        val clamped: Boolean,
    )

    // ── colour maths ─────────────────────────────────────────────────────────────

    private fun srgbToLinear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSrgb(v: Double): Double =
        if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055

    /** WCAG relative luminance, 0..1. */
    fun relativeLuminance(argb: Int): Double {
        val r = srgbToLinear((argb shr 16) and 0xFF)
        val g = srgbToLinear((argb shr 8) and 0xFF)
        val b = srgbToLinear(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG contrast ratio of two opaque colours, 1..21. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private class Lab(val l: Double, val a: Double, val b: Double)

    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883

    private fun labF(t: Double): Double = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
    private fun labFInv(t: Double): Double {
        val t3 = t * t * t
        return if (t3 > 216.0 / 24389.0) t3 else (116.0 * t - 16.0) / (24389.0 / 27.0)
    }

    private fun toLab(argb: Int): Lab {
        val r = srgbToLinear((argb shr 16) and 0xFF)
        val g = srgbToLinear((argb shr 8) and 0xFF)
        val b = srgbToLinear(argb and 0xFF)
        val x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b
        val fx = labF(x / XN)
        val fy = labF(y / YN)
        val fz = labF(z / ZN)
        return Lab(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    /** CIE L* of a colour, 0..100: the tone. */
    fun toneOf(argb: Int): Double = toLab(argb).l

    /** The colour at [tone] with the given Lab chroma and hue (radians), chroma reduced until it fits sRGB. */
    private fun fromLch(tone: Double, chroma: Double, hue: Double): Int {
        var lo = 0.0
        var hi = chroma
        var best = lchToRgbOrNull(tone, 0.0, hue)!!
        if (chroma > 0.0) {
            val full = lchToRgbOrNull(tone, chroma, hue)
            if (full != null) return full
            repeat(24) {
                val mid = (lo + hi) / 2.0
                val c = lchToRgbOrNull(tone, mid, hue)
                if (c != null) { best = c; lo = mid } else hi = mid
            }
        }
        return best
    }

    private fun lchToRgbOrNull(tone: Double, chroma: Double, hue: Double): Int? {
        val a = chroma * kotlin.math.cos(hue)
        val b = chroma * kotlin.math.sin(hue)
        val fy = (tone + 16.0) / 116.0
        val fx = fy + a / 500.0
        val fz = fy - b / 200.0
        val x = XN * labFInv(fx)
        val y = YN * labFInv(fy)
        val z = ZN * labFInv(fz)
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        val eps = 1e-4
        if (r < -eps || r > 1.0 + eps || g < -eps || g > 1.0 + eps || bl < -eps || bl > 1.0 + eps) return null
        fun enc(v: Double): Int = (linearToSrgb(min(1.0, max(0.0, v))) * 255.0 + 0.5).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (enc(r) shl 16) or (enc(g) shl 8) or enc(bl)
    }

    /** The device colour's colour at another tone (hue kept, chroma kept as far as sRGB allows). */
    fun withTone(argb: Int, tone: Double, maxChroma: Double = Double.MAX_VALUE): Int {
        val lab = toLab(argb)
        val chroma = min(sqrt(lab.a * lab.a + lab.b * lab.b), maxChroma)
        val hue = kotlin.math.atan2(lab.b, lab.a)
        return fromLch(tone.coerceIn(0.0, 100.0), chroma, hue)
    }

    /**
     * The palette for a device colour. The plate is tone 95 of its hue with a hint of chroma. The dots are the
     * device colour itself when it is at least [MIN_CONTRAST]:1 against that plate, otherwise the same hue one
     * tone darker at a time (down to [DARKEST_TONE] at the most, which always passes) until it does.
     */
    fun palette(deviceArgb: Int): Palette {
        val opaque = deviceArgb or (0xFF shl 24)
        val plate = withTone(opaque, PLATE_TONE, PLATE_MAX_CHROMA)
        var dot = opaque
        var contrast = contrastRatio(dot, plate)
        var clamped = false
        if (contrast < MIN_CONTRAST) {
            clamped = true
            var tone = min(toneOf(opaque), 60.0)
            while (true) {
                dot = withTone(opaque, tone)
                contrast = contrastRatio(dot, plate)
                if (contrast >= MIN_CONTRAST || tone <= DARKEST_TONE) break
                tone -= 1.0
            }
            if (contrast < MIN_CONTRAST) { // cannot happen for tone 30 on tone 95, kept as a floor
                dot = withTone(opaque, 20.0)
                contrast = contrastRatio(dot, plate)
            }
        }
        return Palette(dot, plate, RingCodeLayout.MARKER_ARGB, toneOf(dot), toneOf(plate), contrast, clamped)
    }

    /** Dot radii in permille of the plate half-size: big and small (big = 2 x small). */
    fun bigRadius(): Int = RingCodeLayout.BIG_RADIUS
    fun smallRadius(): Int = RingCodeLayout.SMALL_RADIUS

    /** Luma (0..255, BT.601) of an ARGB colour: what a camera's Y plane would record, roughly. */
    fun lumaOf(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return ((299 * r + 587 * g + 114 * b + 500) / 1000)
    }

    /** True when two colours differ so little they could be the same: used by tests. */
    fun near(a: Int, b: Int, tolerance: Int = 2): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tolerance &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tolerance &&
            abs((a and 0xFF) - (b and 0xFF)) <= tolerance
}

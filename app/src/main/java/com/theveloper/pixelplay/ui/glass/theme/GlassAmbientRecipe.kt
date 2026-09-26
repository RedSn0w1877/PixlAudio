package com.theveloper.pixelplay.ui.glass.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.ceil
import kotlin.math.max

/**
 * What one ambient bake is made of. Two specs that are equal produce the same bitmap, so the spec is
 * also the bake's cache key.
 *
 * @param artUri the current song's artwork, or null for the no-art fallback.
 * @param blobs the 2–3 palette blobs (NexHome "nebula" composition in the album's colours), or null
 *   for NexHome's own NEBULA stops.
 */
@Immutable
data class GlassAmbientSpec(
    val artUri: String?,
    val isDark: Boolean,
    val blobs: List<Color>?,
    val width: Int,
    val height: Int,
)

/** One radial stop of the bake, in fractions of the canvas (radius in fractions of its height). */
@Immutable
data class AmbientStop(val color: Color, val center: Offset, val alpha: Float)

/**
 * The pure maths of the glass ambient layer (owner decision G1): NexHome's baked wallpaper recipe
 * repainted in the current song's colours. Base fill → the blurred art stretched to fill →
 * 2–3 radial palette blobs at NEBULA's positions → NexHome's readability scrim, raised once per bake
 * for very bright (dark mode) or very dark (light mode) art. Everything is baked at a quarter of the
 * window size and drawn scaled, so every glass layer samples one small texture.
 */
object GlassAmbientRecipe {

    /** Bake at 1/4 of the window on each side (a pre-blurred field upscales cleanly). */
    const val DOWNSCALE = 4

    /** NexHome `SpaceInk`, the dark base. */
    val DarkBase = Color(0xFF060A14)

    /** Light base (derived; NexHome has no light mode). */
    val LightBase = Color(0xFFF4F5F8)

    /** Alpha of the blurred artwork over the base. */
    const val ART_ALPHA = 0.9f

    /** Blur of the 96-texel artwork, in texels (≈ 8 % of its side: a soft field, no detail). */
    const val ART_BLUR_TEXELS = 7.5f

    /** NexHome NEBULA radius (1300 px on a 2400 px tall canvas), as a fraction of the height. */
    const val STOP_RADIUS_OF_HEIGHT = 1300f / 2400f

    /**
     * NexHome's NEBULA stop centres (absolute px tuned for a 1080×2400 phone), as fractions of the
     * canvas: top-left, right-upper, bottom-left, bottom-right.
     */
    val StopCenters = listOf(
        Offset(0.15f / 1080f, 0.05f / 2400f),
        Offset(1200f / 1080f, 300f / 2400f),
        Offset(200f / 1080f, 2200f / 2400f),
        Offset(1000f / 1080f, 2100f / 2400f),
    )

    /** NexHome NEBULA stops, the fallback when there is no artwork (dark). */
    val Nebula = listOf(
        AmbientStop(Color(0xFF241134), StopCenters[0], 1f),
        AmbientStop(Color(0xFF06232A), StopCenters[1], 0.9f),
        AmbientStop(Color(0xFF3A1030), StopCenters[2], 0.8f),
        AmbientStop(Color(0xFF2A1A05), StopCenters[3], 0.55f),
    )

    /** Alphas of the album blobs, NEBULA's range (0.55–0.9). */
    private val BlobAlphas = floatArrayOf(0.9f, 0.8f, 0.55f)

    /** Blob placement: top-left, right-upper, bottom-right (NEBULA positions 0, 1, 3). */
    private val BlobCenterIndex = intArrayOf(0, 1, 3)

    /** NexHome's photo scrim (Black@0.35) and its light-mode mirror (White@0.40, derived). */
    const val DARK_SCRIM = 0.35f
    const val LIGHT_SCRIM = 0.40f

    /** The most the adaptive scrim may add on top of the base scrim. */
    const val SCRIM_BOOST = 0.15f

    /** Bake size for a window of [width]×[height] px. */
    fun bakeWidth(width: Int): Int = max(1, ceil(width / DOWNSCALE.toFloat()).toInt())
    fun bakeHeight(height: Int): Int = max(1, ceil(height / DOWNSCALE.toFloat()).toInt())

    /** The radial stops to paint for [spec]: its album blobs, or NEBULA when there are none. */
    fun stops(spec: GlassAmbientSpec): List<AmbientStop> {
        val blobs = spec.blobs
        if (blobs.isNullOrEmpty()) return if (spec.isDark) Nebula else emptyList()
        return blobs.take(BlobAlphas.size).mapIndexed { i, color ->
            AmbientStop(color, StopCenters[BlobCenterIndex[i]], BlobAlphas[i])
        }
    }

    /** Whether the bake gets the readability scrim: over artwork only, like NexHome's photo mode. */
    fun hasScrim(spec: GlassAmbientSpec): Boolean = spec.artUri != null

    /**
     * Scrim alpha for a bake whose pre-scrim mean luma is [meanLuma] (0..1). Dark mode: Black@0.35,
     * rising toward 0.5 for bright art; light mode: White@0.40, rising toward 0.55 for dark art.
     * Computed once per bake, never per frame.
     */
    fun scrimAlpha(isDark: Boolean, meanLuma: Float): Float {
        val l = meanLuma.coerceIn(0f, 1f)
        return if (isDark) {
            DARK_SCRIM + SCRIM_BOOST * smoothstep(0.35f, 0.65f, l)
        } else {
            LIGHT_SCRIM + SCRIM_BOOST * (1f - smoothstep(0.30f, 0.60f, l))
        }
    }

    /** The scrim colour for [isDark] at [alpha]. */
    fun scrimColor(isDark: Boolean, alpha: Float): Color =
        if (isDark) Color.Black.copy(alpha = alpha) else Color.White.copy(alpha = alpha)

    /** Rec. 709 luma of an sRGB colour (0..1), as the lyrics background measures it. */
    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

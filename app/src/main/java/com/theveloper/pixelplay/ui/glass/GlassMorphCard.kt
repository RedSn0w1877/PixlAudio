package com.theveloper.pixelplay.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorFilter
import com.kyant.backdrop.effects.lens

/**
 * The player sheet's card in glass mode: ONE glass node that is the floating mini-player capsule
 * when collapsed, fading its glass out as the player expands.
 *
 * Put it on a SIBLING drawn behind the player content, never on the content's own chain: the
 * library's node renders everything after it into an offscreen layer of its own size. The caller
 * detaches it once [glassAmount] reaches 0 and draws the expanded card as a plain ambient copy
 * (`GlassPlayerCardBackground`); pass an already quantised [glassAmount] so the lens is rebuilt only
 * when a step changes.
 *
 * [glassAmount] (1 = collapsed, 0 = expanded; read only inside the node's effect/draw lambdas)
 * scales the NexHome light-panel recipe of the mini player — `vibrancy()` + light lens 12/24 +
 * the top-bar tint, no glint, no shadow — down to nothing. At 0 the node draws the ambient layer it
 * samples unfiltered, so the expanded player sits exactly on the baked ambient, and the glass
 * controls on it (which sample the same layer) refract the same picture.
 *
 * The vibrancy fades through 8 cached saturation steps (1.5 → 1.0) instead of snapping off, and
 * without building a colour matrix per frame. Frosted tier (API 31–32): the lens is replaced by
 * [FrostedBlur], scaled the same way.
 *
 * [coveredByBase] (read in draw) is true while the very same ambient is already showing behind
 * the card unobstructed (the page under a fully expanded player stops drawing): the node then
 * draws nothing, since the plain copy would be pixel-identical to what is behind it.
 *
 * [shape] must be lens-compatible (a `CornerBasedShape`, `RoundedRectangle` or `Capsule`).
 */
fun Modifier.glassMorphCard(
    backdrop: Backdrop,
    shape: () -> Shape,
    capability: GlassCapability,
    tint: Color,
    glassAmount: () -> Float,
    coveredByBase: () -> Boolean = { false },
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = shape,
    effects = {
        val k = glassAmount().fastCoerceIn(0f, 1f)
        if (k > 0.001f) {
            GlassSaturationSteps.forAmount(k)?.let { colorFilter(it) }
            if (capability.hasLens) {
                lens(12.dp.toPx() * k, 24.dp.toPx() * k)
            } else {
                val radius = FrostedBlur.toPx() * k
                if (radius >= 0.5f) blur(radius)
            }
        }
    },
    highlight = null,
    shadow = null,
    onDrawBackdrop = { drawBackdrop -> if (!coveredByBase()) drawBackdrop() },
    onDrawSurface = {
        val k = glassAmount().fastCoerceIn(0f, 1f)
        if (k > 0.001f) drawRect(tint, alpha = k)
    },
)

/**
 * `vibrancy()` (saturation 1.5, the library's value) faded toward identity in [STEPS] cached steps,
 * so an animated glass amount never allocates a colour matrix per frame.
 */
internal object GlassSaturationSteps {
    private const val STEPS = 8
    private val filters: Array<ColorFilter?> = arrayOfNulls(STEPS + 1)

    /** The filter for [amount] (0..1 of full vibrancy), or null at identity. */
    fun forAmount(amount: Float): ColorFilter? {
        val step = (amount.fastCoerceIn(0f, 1f) * STEPS).fastRoundToInt()
        if (step == 0) return null
        return filters[step] ?: saturationFilter(1f + 0.5f * step / STEPS).also { filters[step] = it }
    }

    // Same matrix as the library's colorControls(saturation = s) (Rec. 709 luma weights).
    private fun saturationFilter(s: Float): ColorFilter {
        val inv = 1f - s
        val r = 0.213f * inv
        val g = 0.715f * inv
        val b = 0.072f * inv
        return ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    r + s, g, b, 0f, 0f,
                    r, g + s, b, 0f, 0f,
                    r, g, b + s, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
}

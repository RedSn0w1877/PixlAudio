package com.theveloper.pixelplay.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Glass mode's press feedback for flat rows and tiles (orchestrator decision G3): instead of a
 * Material ripple, the pressed content swells to [RowPressScale] with [LiquidMotion.PressSpring]
 * and settles back with the bouncy [LiquidMotion.ReleaseSpring] (a swell, never a shrink), under
 * NexHome's dim press glow — the flat 0.10 flood plus a 0.22 spot where the finger landed,
 * additive and tinted by [glowColor] ("glow, not blind").
 *
 * An [IndicationNodeFactory], so glass screens and glass containers provide it as
 * `LocalIndication` and every `clickable` / `combinedClickable` that uses the default indication
 * picks it up with no call-site change. Material components that pass their own `ripple()`
 * (buttons, `Surface(onClick)`) keep it.
 *
 * Performance: one node per clickable (the same as a ripple). The swell is applied to the content
 * in the draw phase, so a press never recomposes or re-lays out the row; both animations are
 * snapshot state read in draw only, and the spot uses one unit radial brush per tint, positioned by
 * a transform (no allocation per frame). Surfaces larger than [MaxSwellSize] on either axis (a whole
 * page, a scrim, a hero) only glow: a swelling page would read as a zoom, not a press.
 */
@Stable
class GlassPressIndication(
    private val glowColor: Color,
    /** The pressed content scale; 1 = glow only (for surfaces whose container swells itself). */
    private val swellScale: Float = RowPressScale,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        GlassPressIndicationNode(interactionSource, glowColor, swellScale)

    override fun equals(other: Any?): Boolean =
        other is GlassPressIndication && other.glowColor == glowColor && other.swellScale == swellScale

    override fun hashCode(): Int = 31 * glowColor.hashCode() + swellScale.hashCode()

    companion object {
        /** Largest size (either axis) that still swells; bigger surfaces only glow. */
        val MaxSwellSize = 300.dp
    }
}

private const val PressFlatGlow = 0.10f
private const val PressSpotGlow = 0.22f

private class GlassPressIndicationNode(
    private val interactionSource: InteractionSource,
    private val glowColor: Color,
    private val swellScale: Float,
) : Modifier.Node(), DrawModifierNode {

    private val swell = Animatable(0f, 0.001f)
    private val glow = Animatable(0f, 0.001f)
    private var pressPosition = Offset.Unspecified
    private var spotBrush: Brush? = null
    private var collectJob: Job? = null

    override fun onAttach() {
        collectJob = coroutineScope.launch {
            var presses = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        presses++
                        pressPosition = interaction.pressPosition
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel ->
                        presses = (presses - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                val pressed = presses > 0
                // One launch per press / release (never per pointer move); a new target retargets
                // the running spring from its current value and velocity.
                launch {
                    swell.animateTo(
                        if (pressed) 1f else 0f,
                        if (pressed) LiquidMotion.PressSpring else LiquidMotion.ReleaseSpring,
                    )
                }
                launch { glow.animateTo(if (pressed) 1f else 0f, LiquidMotion.GlowSpring) }
            }
        }
    }

    override fun onDetach() {
        collectJob = null
    }

    override fun ContentDrawScope.draw() {
        val s = swell.value
        val maxSwell = GlassPressIndication.MaxSwellSize.toPx()
        if (s != 0f && swellScale != 1f && size.width <= maxSwell * 2.5f && size.height <= maxSwell) {
            val k = 1f + (swellScale - 1f) * s
            scale(k, k) { this@draw.drawContent() }
        } else {
            drawContent()
        }
        val g = glow.value.fastCoerceIn(0f, 1f)
        if (g <= 0.001f || size.width <= 0f || size.height <= 0f) return
        drawRect(glowColor.copy(alpha = PressFlatGlow * g), blendMode = BlendMode.Plus)
        val p = pressPosition
        val px = if (p.isSpecified) p.x.fastCoerceIn(0f, size.width) else size.width / 2f
        val py = if (p.isSpecified) p.y.fastCoerceIn(0f, size.height) else size.height / 2f
        val radius = size.minDimension * 1.5f
        if (radius <= 0f) return
        val brush = spotBrush ?: Brush.radialGradient(
            colors = listOf(glowColor.copy(alpha = PressSpotGlow), Color.Transparent),
            center = Offset.Zero,
            radius = 1f,
        ).also { spotBrush = it }
        // The unit gradient scaled onto the finger: the same pixels as a radial gradient of
        // `radius` centred there, clipped to this rect.
        withTransform({
            translate(px, py)
            scale(radius, radius, Offset.Zero)
        }) {
            drawRect(
                brush = brush,
                topLeft = Offset(-px / radius, -py / radius),
                size = Size(size.width / radius, size.height / radius),
                alpha = g,
                blendMode = BlendMode.Plus,
            )
        }
    }
}

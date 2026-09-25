package com.theveloper.pixelplay.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch

/**
 * Click handling for glass components.
 *
 * The press feedback is clipped to the component's own [shape] rather than left to spread over the
 * panel bounds: on a transparent surface anything unclipped visibly bleeds past the refracted edge,
 * which breaks the illusion that the glass is a solid object.
 *
 * In Material 3 mode the feedback is the normal ripple. In glass mode it is [GlassPressGlow], a
 * soft additive light — glass lights up under the finger rather than rippling. Components that run
 * their own [InteractiveHighlight] pass `indication = null` so the two don't stack.
 */
@Composable
fun Modifier.glassClickable(
    onClick: () -> Unit,
    enabled: Boolean,
    shape: Shape,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = if (isGlassEnabled) GlassPressGlow else ripple()
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return this
        .clip(shape)
        .clickable(
            interactionSource = source,
            indication = indication,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * Press feedback for glass: a white wash added with [BlendMode.Plus] that fades in on press and out
 * on release, drawn *under* the content so icons and labels stay crisp. The finger-tracking,
 * shader-lit version is [InteractiveHighlight]; this is the cheap one for everything else.
 */
object GlassPressGlow : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        GlassPressGlowNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()
}

private class GlassPressGlowNode(
    private val interactionSource: InteractionSource
) : Modifier.Node(), DrawModifierNode {

    private val progress = Animatable(0f, 0.001f)
    private val spec = spring(0.5f, 300f, 0.001f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressed = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel ->
                        pressed = (pressed - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                val target = if (pressed > 0) 1f else 0f
                launch { progress.animateTo(target, spec) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = progress.value
        if (p > 0f) {
            drawRect(Color.White.copy(alpha = 0.14f * p), blendMode = BlendMode.Plus)
        }
        drawContent()
    }
}

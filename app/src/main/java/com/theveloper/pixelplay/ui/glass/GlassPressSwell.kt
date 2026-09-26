package com.theveloper.pixelplay.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * NexHome's press feedback for flat (non-glass) rows and tiles: the content swells to
 * [pressedScale] with [LiquidMotion.PressSpring] while pressed and settles back with the bouncy
 * [LiquidMotion.ReleaseSpring] — it swells, it never shrinks on press.
 *
 * Glass mode keeps list rows flat (orchestrator decision G3: hundreds of rows can't each own a
 * lens), so this is how they answer a touch. A `Modifier.Node`: it observes [interactionSource]
 * (the row's own clickable source) and scales in the placement layer, so a press never recomposes
 * or re-lays out the row.
 */
fun Modifier.glassPressSwell(
    interactionSource: InteractionSource,
    pressedScale: Float = RowPressScale,
): Modifier = this then GlassPressSwellElement(interactionSource, pressedScale)

/** NexHome's row / wide-bar content swell. */
const val RowPressScale = 1.03f

private data class GlassPressSwellElement(
    val interactionSource: InteractionSource,
    val pressedScale: Float,
) : ModifierNodeElement<GlassPressSwellNode>() {
    override fun create() = GlassPressSwellNode(interactionSource, pressedScale)

    override fun update(node: GlassPressSwellNode) {
        node.pressedScale = pressedScale
        if (node.interactionSource != interactionSource) {
            node.interactionSource = interactionSource
            node.restart()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassPressSwell"
        properties["pressedScale"] = pressedScale
    }
}

private class GlassPressSwellNode(
    var interactionSource: InteractionSource,
    var pressedScale: Float,
) : Modifier.Node(), LayoutModifierNode {

    private val swell = Animatable(0f, 0.001f)
    private var collectJob: Job? = null

    // Allocated once; reads the animated value in the layer, never in composition or layout.
    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        val s = 1f + (pressedScale - 1f) * swell.value
        scaleX = s
        scaleY = s
    }

    override fun onAttach() {
        restart()
    }

    override fun onDetach() {
        collectJob = null
    }

    fun restart() {
        if (!isAttached) return
        collectJob?.cancel()
        collectJob = coroutineScope.launch {
            var presses = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel ->
                        presses = (presses - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                val pressed = presses > 0
                // One launch per press/release (never per pointer move); a new target cancels the
                // running spring and retargets from the current value and velocity.
                launch {
                    swell.animateTo(
                        if (pressed) 1f else 0f,
                        if (pressed) LiquidMotion.PressSpring else LiquidMotion.ReleaseSpring,
                    )
                }
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0, layerBlock = layerBlock)
        }
    }
}

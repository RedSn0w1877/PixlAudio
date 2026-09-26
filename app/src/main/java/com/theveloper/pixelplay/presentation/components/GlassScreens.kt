package com.theveloper.pixelplay.presentation.components

import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.controls.LocalLensBloom
import com.theveloper.pixelplay.ui.glass.components.GlassPanel
import androidx.compose.material3.CardElevation
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import com.theveloper.pixelplay.ui.glass.GlassPressIndication
import com.theveloper.pixelplay.ui.glass.RowPressScale
import com.theveloper.pixelplay.ui.glass.glassPressSwell
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.kyant.shapes.RoundedRectangle
import com.theveloper.pixelplay.ui.glass.LocalGlassModeEnabled
import com.theveloper.pixelplay.ui.glass.controls.LiquidChip
import com.theveloper.pixelplay.ui.glass.glassLightSurface
import com.theveloper.pixelplay.ui.glass.theme.GlassAmbientState
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassAmbient

// ------------------------------------------------------------------------------------------------
// Liquid Glass mode for screens (orchestrator decisions G1 / G3). Every helper is a pass-through in
// Material 3 mode.
// ------------------------------------------------------------------------------------------------

/**
 * A screen page's background. Material 3 mode: `background(color)`, exactly as before. Glass mode:
 * a plain copy of the baked ambient layer, drawn at the page's own origin, so at rest it is
 * pixel-identical to the ambient behind the whole app (the page reads as transparent over it),
 * while a page sliding in or out during navigation still covers the page beneath instead of the two
 * overlapping. One scaled bitmap draw; it redraws only during an ambient crossfade.
 */
@Composable
fun Modifier.glassScreenBackground(color: Color): Modifier {
    val ambient = LocalGlassAmbient.current
    return if (LocalGlassModeEnabled.current && ambient != null) {
        this.drawBehind {
            val rootSize = ambient.rootSize
            if (rootSize.width > 0 && rootSize.height > 0) {
                ambient.draw(this, rootSize.toSize())
            } else {
                ambient.draw(this)
            }
        }
    } else {
        this.background(color)
    }
}

/**
 * Fills the element with the baked ambient, lined up with the ambient layer behind the app, so it
 * masks scrolled content under a header (a collapsed title bar) while reading as the clear glass
 * page. Material 3 mode: nothing. Use it only on elements that do not move while the ambient shows
 * (headers pinned to the top of a page); the alignment is refreshed on every layout.
 */
@Composable
fun Modifier.glassAmbientFill(alpha: Float = 1f): Modifier {
    val ambient = LocalGlassAmbient.current
    return if (LocalGlassModeEnabled.current && ambient != null) {
        this then GlassAmbientFillElement(ambient, alpha, null)
    } else {
        this
    }
}

/**
 * [glassAmbientFill] with its alpha read in draw from [alpha] (remember the lambda): an animated
 * fill never touches composition or the modifier chain, and an alpha of 0 draws nothing. Unlike
 * the Float form, it needs no glass-mode check at the call site's composition: it draws only when
 * an ambient state exists.
 */
@Composable
fun Modifier.glassAmbientFill(alpha: () -> Float): Modifier {
    val ambient = LocalGlassAmbient.current
    return if (LocalGlassModeEnabled.current && ambient != null) {
        this then GlassAmbientFillElement(ambient, 1f, alpha)
    } else {
        this
    }
}

/**
 * A collapsing header's solid fill. Material 3 mode: `background(color.copy(alpha = alpha))`, as
 * before. Glass mode: the aligned ambient ([glassAmbientFill]) at [alpha], so the collapsed header
 * masks the list scrolled under it with the same clear-glass page the rest of the screen shows.
 */
@Composable
fun Modifier.glassAwareHeaderFill(color: Color, alpha: Float): Modifier =
    if (LocalGlassModeEnabled.current && LocalGlassAmbient.current != null) {
        this.glassAmbientFill(alpha)
    } else {
        this.background(color.copy(alpha = alpha))
    }

private data class GlassAmbientFillElement(
    val state: GlassAmbientState,
    val alpha: Float,
    val alphaProvider: (() -> Float)?,
) : ModifierNodeElement<GlassAmbientFillNode>() {
    override fun create() = GlassAmbientFillNode(state, alpha, alphaProvider)
    override fun update(node: GlassAmbientFillNode) {
        if (node.state !== state || node.alpha != alpha || node.alphaProvider !== alphaProvider) {
            node.state = state
            node.alpha = alpha
            node.alphaProvider = alphaProvider
            node.invalidateDraw()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassAmbientFill"
    }
}

private class GlassAmbientFillNode(
    var state: GlassAmbientState,
    var alpha: Float,
    var alphaProvider: (() -> Float)?,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var offsetInRoot = Offset.Zero

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val offset = coordinates.positionInRoot()
        if (offset != offsetInRoot) {
            offsetInRoot = offset
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val rootSize = state.rootSize
        val a = alphaProvider?.invoke() ?: alpha
        if (a > 0f && rootSize.width > 0 && rootSize.height > 0) {
            translate(-offsetInRoot.x, -offsetInRoot.y) {
                state.draw(this, rootSize.toSize(), a)
            }
        }
        drawContent()
    }
}

/** A container colour that turns clear in glass mode (the ambient or the glass behind shows). */
@Composable
fun glassClear(color: Color): Color = if (LocalGlassModeEnabled.current) Color.Transparent else color

/** The now-playing row's shape in glass mode (NexHome `GlassSettingRow`, radius 20). */
val GlassNowPlayingRowShape = RoundedRectangle(20.dp)

/**
 * The now-playing row of a glass list: one light glass panel (the `GlassSettingRow` look, radius
 * 20) under the row. Every other row stays flat. Material 3 mode, or [isCurrent] false: nothing.
 */
@Composable
fun Modifier.glassNowPlayingRow(isCurrent: Boolean): Modifier =
    if (isCurrent && LocalGlassModeEnabled.current) this.glassLightSurface(GlassNowPlayingRowShape) else this

/**
 * A filter chip that becomes a NexHome [LiquidChip] in glass mode (label + optional icon, accent
 * flood when selected). [material] is the chip's existing Material 3 code, composed unchanged
 * outside glass mode.
 */
@Composable
fun AdaptiveChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    material: @Composable () -> Unit,
) {
    if (LocalGlassModeEnabled.current) {
        LiquidChip(label = label, selected = selected, onClick = onClick, modifier = modifier, icon = icon)
    } else {
        material()
    }
}

/**
 * A clickable row / tile container. Material 3 mode is exactly `Surface(onClick, modifier, shape,
 * color)` with its ripple. Glass mode is a flat, clear container clipped to [shape]: the press
 * swells and glows through the glass press indication instead of a ripple (NexHome flat rows,
 * decision G3), and there is no tile fill of its own.
 */
@Composable
fun AdaptiveClickableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    if (LocalGlassModeEnabled.current) {
        Box(
            modifier = modifier
                .clip(shape)
                .clickable(onClick = onClick),
            propagateMinConstraints = true,
        ) {
            content()
        }
    } else {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, content = content)
    }
}

/**
 * A clickable list card (a folder, artist or result row). Material 3 mode is exactly
 * `Card(onClick, modifier, shape, colors)`. Glass mode is a flat row clipped to [shape] with no
 * fill; the press swells and glows through the glass press indication (G3).
 */
@Composable
fun AdaptiveClickableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalGlassModeEnabled.current) {
        Column(
            modifier = modifier
                .clip(shape)
                .clickable(enabled = enabled, onClick = onClick),
            content = content,
        )
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = colors,
            elevation = elevation,
            content = content,
        )
    }
}

/**
 * Glass mode's press feedback for a Material tile that keeps its own fill (a `Surface(onClick)` or
 * `Card(onClick)` whose Material ripple is switched off): the whole tile swells to
 * [RowPressScale] with the press spring and settles back with the bouncy release spring (placement
 * layer, never a shrink, never a relayout), under NexHome's dim press glow clipped to [shape]
 * (0.10 flood + 0.22 spot at the finger, white). Observes [interactionSource] only; the tile's own
 * click handling is untouched.
 */
@Composable
fun Modifier.glassTilePress(interactionSource: MutableInteractionSource, shape: Shape): Modifier {
    val glow = remember { GlassPressIndication(Color.White, swellScale = 1f) }
    return this
        .glassPressSwell(interactionSource, RowPressScale)
        .clip(shape)
        .indication(interactionSource, glow)
}

/**
 * `Surface(onClick)` (or `Surface(selected, onClick)` when [selected] is given) whose press follows
 * the theme mode. Material 3 mode is exactly that Material call, ripple included. Liquid Glass mode
 * keeps the tile — its fill (translucent over glass), shape, border and elevation — but replaces the
 * Material ripple with the glass press ([glassTilePress]: swell with overshoot plus dim glow).
 * Ripples inside the content are unaffected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptivePressSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val glass = LocalGlassModeEnabled.current
    val source = interactionSource ?: if (glass) remember { MutableInteractionSource() } else null
    val surfaceModifier = if (glass && source != null) modifier.glassTilePress(source, shape) else modifier
    val innerRipple = LocalRippleConfiguration.current
    val body: @Composable () -> Unit = if (glass) {
        { CompositionLocalProvider(LocalRippleConfiguration provides innerRipple, content = content) }
    } else {
        content
    }
    val surface: @Composable () -> Unit = {
        if (selected != null) {
            Surface(
                selected = selected,
                onClick = onClick,
                modifier = surfaceModifier,
                enabled = enabled,
                shape = shape,
                color = color,
                contentColor = contentColor,
                tonalElevation = tonalElevation,
                shadowElevation = shadowElevation,
                border = border,
                interactionSource = source,
                content = body,
            )
        } else {
            Surface(
                onClick = onClick,
                modifier = surfaceModifier,
                enabled = enabled,
                shape = shape,
                color = color,
                contentColor = contentColor,
                tonalElevation = tonalElevation,
                shadowElevation = shadowElevation,
                border = border,
                interactionSource = source,
                content = body,
            )
        }
    }
    if (glass) {
        CompositionLocalProvider(LocalRippleConfiguration provides null, content = surface)
    } else {
        surface()
    }
}

/**
 * `Card(onClick)` whose press follows the theme mode. Material 3 mode is exactly that Material
 * call. Liquid Glass mode keeps the card's fill, shape, border and elevation and replaces the
 * Material ripple with the glass press ([glassTilePress]). Ripples inside the content are unaffected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptivePressCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalGlassModeEnabled.current) {
        Card(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            interactionSource = interactionSource,
            content = content,
        )
        return
    }
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val innerRipple = LocalRippleConfiguration.current
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Card(
            onClick = onClick,
            modifier = modifier.glassTilePress(source, shape),
            enabled = enabled,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            interactionSource = source,
        ) {
            CompositionLocalProvider(LocalRippleConfiguration provides innerRipple) {
                content()
            }
        }
    }
}

/**
 * A Home card that becomes one of the page's few glass cards in glass mode (NexHome `DeviceCard`
 * geometry: a light glass panel, radius 26, lens 11/22, the default glass tint, the accent as its
 * light emitter, press swell 1.07 with the jelly and glow, lens bloom). Material 3 mode is exactly
 * `Card(onClick, modifier, shape, colors, elevation)`. Use it sparingly (layer budget, G3).
 */
@Composable
fun AdaptiveGlassCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalGlassModeEnabled.current) {
        val palette = LocalGlassPalette.current
        GlassPanel(
            modifier = modifier,
            shape = RoundedRectangle(26.dp),
            tint = palette.tint,
            accent = palette.accent,
            onClick = onClick,
            refractionHeight = 22.dp,
            refractionAmount = 44.dp,
            enterProgress = LocalLensBloom.current,
        ) {
            Column(content = content)
        }
    } else {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, elevation = elevation, content = content)
    }
}

/**
 * A header's darkening overlay. Material 3 mode: `background(brush)`, as before. Glass mode draws
 * nothing: the header art fades out with [glassArtFade] instead, straight into the ambient (the
 * overlay's surface colour is clear over glass, so it would leave a hard band at the bottom).
 */
@Composable
fun Modifier.glassAwareOverlay(brush: Brush): Modifier =
    if (LocalGlassModeEnabled.current) this else this.background(brush)

/**
 * Glass mode: fades a header image out towards its bottom edge (opaque → 78 % → 18 % → clear, the
 * inverse of the Material overlay stops) so it melts into the ambient layer. One offscreen layer
 * with a cached mask brush. Material 3 mode: nothing.
 */
@Composable
fun Modifier.glassArtFade(): Modifier =
    if (LocalGlassModeEnabled.current) {
        this
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val mask = Brush.verticalGradient(
                    0f to Color.Black,
                    0.45f to Color.Black.copy(alpha = 0.78f),
                    0.8f to Color.Black.copy(alpha = 0.18f),
                    1f to Color.Transparent,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
            }
    } else {
        this
    }

/**
 * A press scale that follows the theme mode. Material 3 mode: exactly the caller's existing
 * animation ([materialPressedScale] with [materialSpec]). Liquid Glass mode (NexHome's rule: a press
 * swells, never shrinks): [glassPressedScale] with the press spring, settling back with the bouncy
 * release spring. Read the returned state in a layer block.
 */
@Composable
fun animatePressScaleAsState(
    pressed: Boolean,
    materialPressedScale: Float,
    glassPressedScale: Float,
    materialSpec: androidx.compose.animation.core.AnimationSpec<Float>,
    label: String,
): androidx.compose.runtime.State<Float> {
    val glass = LocalGlassModeEnabled.current
    return androidx.compose.animation.core.animateFloatAsState(
        targetValue = when {
            !pressed -> 1f
            glass -> glassPressedScale
            else -> materialPressedScale
        },
        animationSpec = when {
            !glass -> materialSpec
            pressed -> com.theveloper.pixelplay.ui.glass.motion.LiquidMotion.PressSpring
            else -> com.theveloper.pixelplay.ui.glass.motion.LiquidMotion.ReleaseSpring
        },
        label = label,
    )
}

package com.theveloper.pixelplay.ui.glass.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.RoundedRectangle
import com.theveloper.pixelplay.ui.glass.FrostedBlur
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.LocalGlassCapability
import com.theveloper.pixelplay.ui.glass.drawBackdrop
import com.theveloper.pixelplay.ui.glass.light.glassLightEmitter
import com.theveloper.pixelplay.ui.glass.light.rememberGlassLightReceiver
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.utils.InteractiveHighlight
import com.theveloper.pixelplay.ui.glass.utils.LocalUISensor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The kit's base glass surface (ported from NexHome). Always refracts (`vibrancy()` + `lens()`);
 * [heavy] hero surfaces add `depthEffect` and a gravity-reactive highlight, light surfaces use a lens
 * at half the given refraction values and a plain rim. Interactive panels ([onClick] or
 * [interactive]) get the gliding InteractiveHighlight glow, LiquidButton-style jelly (tanh follow +
 * anisotropic stretch), a water swell on press with a bouncy release (never a shrink), and a lens
 * that thickens with press progress. Every panel RECEIVES light spill; a panel with an [accent] also
 * EMITS it while touched.
 *
 * [shape] must be lens-compatible: Capsule, RoundedRectangle, or a CornerBasedShape.
 * [enterProgress] (0..1) scales refraction for a "lens bloom" entrance.
 * [onDrawSurface] / [onDrawFront] paint in their own child layers (under / over [content]), never in
 * the glass node, so anything animated there re-records only that layer, not the lens.
 *
 * PixlAudio changes: colours come from [LocalGlassPalette]; on the frosted tier (API 31–32, where
 * `lens()` is a no-op) the panel adds [FrostedBlur] so it reads as frosted glass instead of a flat
 * copy of the backdrop; content defaults to the palette's primary colour ([LocalContentColor]); the
 * glass-on-glass export is gone (FlattenNestedGlass made it dead in NexHome).
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    shape: Shape = RoundedRectangle(24.dp),
    tint: Color = LocalGlassPalette.current.tint,
    accent: Color = Color.Unspecified,
    heavy: Boolean = false,
    onClick: (() -> Unit)? = null,
    interactive: Boolean = onClick != null,
    showHighlight: Boolean = true,
    refractionHeight: Dp = 24.dp,
    refractionAmount: Dp = 48.dp,
    pressScale: Float = LiquidMotion.TilePressScale,
    enterProgress: () -> Float = { 1f },
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val sensor = LocalUISensor.current
    val capability = LocalGlassCapability.current
    val contentColor = LocalGlassPalette.current.primary
    val animationScope = rememberCoroutineScope()
    val currentAccent by rememberUpdatedState(accent)
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope,
            color = {
                val a = currentAccent
                if (a.isSpecified) androidx.compose.ui.graphics.lerp(Color.White, a, 0.45f) else Color.White
            }
        )
    }
    val receiver = rememberGlassLightReceiver()

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    val press = if (interactive) interactiveHighlight.pressProgress.fastCoerceIn(0f, 1f) else 0f
                    val k = lerp(1f, LiquidMotion.LensThicken, press) * enterProgress().fastCoerceIn(0f, 1f)
                    // Gate: skip the shader passes when the lens bloom hasn't started yet (a
                    // sheet/panel still at enterProgress() == 0) instead of paying for a no-op lens.
                    if (k > 0.001f) {
                        vibrancy()
                        if (!capability.hasLens) {
                            blur(FrostedBlur.toPx() * k.fastCoerceAtMost(1f))
                        } else if (heavy) {
                            lens(refractionHeight.toPx() * k, refractionAmount.toPx() * k, depthEffect = true)
                        } else {
                            lens(refractionHeight.toPx() * 0.5f * k, refractionAmount.toPx() * 0.5f * k)
                        }
                    }
                },
                highlight = {
                    val press = if (interactive) interactiveHighlight.pressProgress.fastCoerceIn(0f, 1f) else 0f
                    val base = when {
                        !showHighlight -> null
                        heavy -> Highlight(
                            width = androidx.compose.ui.unit.lerp(0.5.dp, 1.25.dp, press),
                            style = HighlightStyle.Default(angle = sensor.gravityAngle, falloff = 2f)
                        )
                        press > 0f -> Highlight.Plain.copy(width = androidx.compose.ui.unit.lerp(0.5.dp, 1.dp, press))
                        else -> Highlight.Plain
                    }
                    receiver.highlight(base)
                },
                shadow = null,
                layerBlock = if (interactive) {
                    {
                        val width = size.width
                        val height = size.height
                        if (width > 0f && height > 0f) {
                            val scale = lerp(1f, pressScale, interactiveHighlight.swell)
                            val maxOffset = size.minDimension
                            val offset = interactiveHighlight.offset
                            translationX = maxOffset * tanh(LiquidMotion.JellyFollow * offset.x / maxOffset)
                            translationY = maxOffset * tanh(LiquidMotion.JellyFollow * offset.y / maxOffset)
                            val angle = atan2(offset.y, offset.x)
                            scaleX = scale + LiquidMotion.JellyStretch *
                                abs(cos(angle) * offset.x / size.maxDimension).fastCoerceAtMost(1f) *
                                (width / height).fastCoerceAtMost(1f)
                            scaleY = scale + LiquidMotion.JellyStretch *
                                abs(sin(angle) * offset.y / size.maxDimension).fastCoerceAtMost(1f) *
                                (height / width).fastCoerceAtMost(1f)
                        }
                    }
                } else {
                    null
                },
                // Only static paint stays in the glass node. Anything drawn here is part of the node
                // that re-records the refraction layer, so an animated aura / ring / fill in here
                // would re-render the lens every frame. Those are drawn by child layers below.
                onDrawSurface = {
                    if (tint.isSpecified) drawRect(tint)
                },
                onDrawFront = null,
            )
            .then(receiver.modifier)
            .then(
                if (interactive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .then(if (accent.isSpecified) Modifier.glassLightEmitter({ currentAccent }) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        // Child layers inside the panel (still clipped to its shape): redrawing these never
        // touches the refraction layer. Surface paint sits under the content, front paint over it.
        // Each in its OWN graphicsLayer: an animating aura/ring then re-records only its layer, not
        // the panel's content layer.
        if (onDrawSurface != null) Box(Modifier.matchParentSize().graphicsLayer().drawBehind(onDrawSurface))
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
        if (onDrawFront != null) Box(Modifier.matchParentSize().graphicsLayer().drawBehind(onDrawFront))
    }
}

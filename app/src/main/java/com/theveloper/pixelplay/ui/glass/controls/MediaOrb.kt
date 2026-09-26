package com.theveloper.pixelplay.ui.glass.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.theveloper.pixelplay.ui.glass.FrostedBlur
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.LocalGlassCapability
import com.theveloper.pixelplay.ui.glass.drawBackdrop
import com.theveloper.pixelplay.ui.glass.light.glassLightEmitter
import com.theveloper.pixelplay.ui.glass.light.rememberGlassLightReceiver
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

/**
 * Round liquid glass button (NexHome `MediaOrb`, the reference for the player's transport: play 80,
 * prev / next 54, small actions 42–44). Swells + jellies (1.12) with a gliding highlight, emits
 * [accent], receives light, `TextHandleMove` haptic. [lit] (0..1) floods it with the accent
 * (Hue 0.9 + 0.38), tints the rim and adds the accent glow shadow — a static lit state.
 *
 * PixlAudio changes: no beat pulse (NexHome drove it from a fake 100 bpm clock, a per-frame redraw
 * for the whole listening session; owner decision G3); palette colours; frosted-tier blur.
 */
@Composable
fun MediaOrb(
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    accent: Color = LocalGlassPalette.current.accent,
    lit: Float = 0f,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val highlight = rememberKitHighlight(accent)
    val receiver = rememberGlassLightReceiver()
    val haptic = LocalHapticFeedback.current
    val capability = LocalGlassCapability.current
    val contentColor = LocalGlassPalette.current.primary
    val currentAccent by rememberUpdatedState(accent)
    val currentOnClick by rememberUpdatedState(onClick)
    val glow = remember { Animatable(lit, 0.001f) }
    LaunchedEffect(lit) { glow.animateTo(lit, LiquidMotion.GlowSpring) }
    val orbSize = size

    Box(
        modifier
            .size(orbSize)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    val d = this.size.minDimension
                    val k = kitLensFactor(highlight)
                    vibrancy()
                    if (capability.hasLens) lens(d * 0.16f * k, d * 0.32f * k) else blur(FrostedBlur.toPx())
                },
                highlight = {
                    val g = glow.value.fastCoerceIn(0f, 1f)
                    receiver.highlight(
                        if (g > 0.01f) {
                            Highlight(
                                width = lerp(0.75.dp, 1.5.dp, g),
                                style = HighlightStyle.Default(
                                    color = androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.5f), currentAccent, g),
                                    falloff = 1.4f,
                                ),
                            )
                        } else {
                            Highlight.Plain
                        }
                    )
                },
                shadow = {
                    val g = glow.value.fastCoerceIn(0f, 1f)
                    if (g <= 0.01f) null else Shadow(radius = orbSize * 0.4f, color = currentAccent.copy(alpha = 0.5f), alpha = g)
                },
                layerBlock = { kitJelly(highlight, LiquidMotion.OrbPressScale) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.06f))
                    val g = glow.value.fastCoerceIn(0f, 1f)
                    if (g > 0.001f) {
                        drawRect(currentAccent.copy(alpha = 0.9f * g), blendMode = BlendMode.Hue)
                        drawRect(currentAccent.copy(alpha = 0.38f * g))
                    }
                },
            )
            .then(receiver.modifier)
            .then(highlight.modifier)
            .then(highlight.gestureModifier)
            .glassLightEmitter({ currentAccent })
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentOnClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

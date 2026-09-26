package com.theveloper.pixelplay.ui.glass.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import com.theveloper.pixelplay.ui.glass.FrostedBlur
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.LocalGlassCapability
import com.theveloper.pixelplay.ui.glass.drawBackdrop
import com.theveloper.pixelplay.ui.glass.light.glassLightEmitter
import com.theveloper.pixelplay.ui.glass.light.rememberGlassLightReceiver
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.utils.InteractiveHighlight
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Ported from the Backdrop library's own catalog app (LiquidButton.kt, Apache-2.0) via NexHome: the
 * capsule swells to [LiquidMotion.ButtonPressScale] and bounces back, the lens thickens under the
 * finger, the glow glides after it, and the button receives light spill. A [tint]ed button also
 * emits its tint as light while held.
 *
 * PixlAudio changes: [backdrop] defaults to [LocalGlassBackdrop]; the frosted tier (API 31–32)
 * blurs by [FrostedBlur] instead of 2 dp because it has no lens; content defaults to the palette's
 * primary colour.
 */
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    isInteractive: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val currentTint by rememberUpdatedState(tint)
    val capability = LocalGlassCapability.current
    val contentColor = LocalGlassPalette.current.primary

    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope,
            color = {
                val t = currentTint
                if (t.isSpecified) androidx.compose.ui.graphics.lerp(Color.White, t, 0.4f) else Color.White
            }
        )
    }
    val receiver = rememberGlassLightReceiver()

    Row(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    val press = if (isInteractive) interactiveHighlight.pressProgress.fastCoerceIn(0f, 1f) else 0f
                    val k = lerp(1f, LiquidMotion.LensThicken, press)
                    vibrancy()
                    if (capability.hasLens) {
                        blur(2f.dp.toPx())
                        lens(12f.dp.toPx() * k, 24f.dp.toPx() * k)
                    } else {
                        blur(FrostedBlur.toPx())
                    }
                },
                highlight = { receiver.highlight(Highlight.Default) },
                layerBlock = if (isInteractive) {
                    {
                        val width = size.width
                        val height = size.height
                        if (width > 0f && height > 0f) {
                            val scale = lerp(1f, LiquidMotion.ButtonPressScale, interactiveHighlight.swell)

                            val maxOffset = size.minDimension
                            val offset = interactiveHighlight.offset
                            translationX = maxOffset * tanh(LiquidMotion.JellyFollow * offset.x / maxOffset)
                            translationY = maxOffset * tanh(LiquidMotion.JellyFollow * offset.y / maxOffset)

                            val maxDragScale = LiquidMotion.JellyStretch
                            val offsetAngle = atan2(offset.y, offset.x)
                            scaleX = scale +
                                maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension).fastCoerceAtMost(1f) *
                                (width / height).fastCoerceAtMost(1f)
                            scaleY = scale +
                                maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension).fastCoerceAtMost(1f) *
                                (height / width).fastCoerceAtMost(1f)
                        }
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    }
                    if (surfaceColor.isSpecified) {
                        drawRect(surfaceColor)
                    }
                }
            )
            .then(receiver.modifier)
            .clickable(
                interactionSource = null,
                indication = if (isInteractive) null else LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (isInteractive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .then(if (tint.isSpecified) Modifier.glassLightEmitter({ currentTint }) else Modifier)
            .height(48f.dp)
            .padding(horizontal = 16f.dp),
        horizontalArrangement = Arrangement.spacedBy(8f.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

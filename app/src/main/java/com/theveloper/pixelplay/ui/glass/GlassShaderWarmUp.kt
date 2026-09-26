package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.theveloper.pixelplay.ui.glass.utils.GlowShaderString
import kotlinx.coroutines.delay

/** How long the warm-up instances stay composed once the first frame is out. */
private const val WarmUpMillis = 2500L

/**
 * Draws one tiny, near-transparent instance of every distinct glass shader / effect-chain combination
 * the kit uses for a short while after the first frame, so the GPU driver compiles them here instead
 * of on the first real screen, sheet or press (ported from NexHome's `ShaderWarmUp`). Alpha stays
 * above zero: HWUI skips fully transparent render nodes.
 *
 * PixlAudio changes: runs only in glass mode and only AFTER the first frame (NexHome ran it at app
 * start); skipped under battery saver ([enabled], owner decision G5); the AGSL entries (lens, rims,
 * glow) are skipped below the Full tier where the library can't use them; the glow uses the very
 * source string [GlowShaderString] the InteractiveHighlight compiles (NexHome's differed, so the real
 * glow still compiled on the first press); NexHome's security colour-matrix entry is dropped and the
 * sheet scrim entry matches the scaffold's constant, blur-free scrim.
 *
 * Covered (Full): heavy panel (vibrancy + depth lens + Default rim), heavy + inner shadow + coloured
 * shadow (lit orbs), light panel (vibrancy + lens + Plain), the lit light-spill rim, LiquidButton
 * (vibrancy + blur 2 + lens), tab bar (vibrancy + blur 8 + lens), thumbs (blur + dispersion lens +
 * Ambient + inner shadow) with and without blur, blobs (dispersion lens + Default + inner shadow),
 * the sheet scrim and the glow. Frosted: vibrancy + frosted blur, and the scrim.
 */
@Composable
fun GlassShaderWarmUp(
    backdrop: Backdrop,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    var started by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        started = true
        delay(WarmUpMillis)
        done = true
    }
    if (!started || done) return

    val capability = LocalGlassCapability.current
    val glowBrush = remember(capability) {
        if (capability.hasLens) {
            val shader = RuntimeShader(GlowShaderString)
            ShaderBrush(shader.asComposeShader()) to shader
        } else {
            null
        }
    }

    Row(
        modifier
            .clearAndSetSemantics {}
            .graphicsLayer { alpha = 0.01f },
    ) {
        if (capability.hasLens) {
            // A heavy: sections, headers, hero panels, icon orbs.
            WarmUpBox {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(3.dp) },
                    effects = {
                        vibrancy()
                        lens(1.dp.toPx(), 2.dp.toPx(), depthEffect = true)
                    },
                    highlight = { Highlight(style = HighlightStyle.Default(angle = 45f, falloff = 2f)) },
                    shadow = null,
                )
            }
            // A heavy + InnerShadow + coloured Shadow: lit icon orbs, lit media orbs.
            WarmUpBox {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        lens(1.dp.toPx(), 2.dp.toPx(), depthEffect = true)
                    },
                    highlight = { Highlight(width = 1.dp, style = HighlightStyle.Default(falloff = 1.5f)) },
                    shadow = { Shadow(radius = 4.dp, color = Color(0xFF6FE3FF).copy(alpha = 0.5f)) },
                    innerShadow = { InnerShadow(radius = 2.dp, alpha = 0.8f) },
                )
            }
            // A light: tiles, chips, rows, segmented bar, orbs at rest.
            WarmUpBox {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        lens(1.dp.toPx(), 2.dp.toPx())
                    },
                    highlight = { Highlight.Plain },
                    shadow = null,
                )
            }
            // Light spill: a receiver rim lit in the light colour (Default, low falloff).
            WarmUpBox {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(3.dp) },
                    effects = {
                        vibrancy()
                        lens(1.dp.toPx(), 2.dp.toPx())
                    },
                    highlight = {
                        Highlight(
                            width = 1.25.dp,
                            style = HighlightStyle.Default(color = Color(0xFFFFC46F), angle = 120f, falloff = 0.7f),
                        )
                    },
                    shadow = null,
                )
            }
            // B: LiquidButton.
            WarmUpBox {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(2.dp.toPx())
                        lens(1.dp.toPx(), 2.dp.toPx())
                    },
                    highlight = { Highlight.Default },
                )
            }
            // C: LiquidBottomTabs bar.
            WarmUpBox {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(1.dp.toPx(), 2.dp.toPx())
                    },
                    highlight = { Highlight.Default },
                    shadow = null,
                )
            }
            // D: toggle / slider / scrubber thumbs at rest and while pressing.
            WarmUpBox {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        blur(2.dp.toPx())
                        lens(1.dp.toPx(), 2.dp.toPx(), chromaticAberration = true)
                    },
                    highlight = { Highlight.Ambient },
                    shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)) },
                    innerShadow = { InnerShadow(radius = 2.dp, alpha = 1f) },
                )
            }
            // D at full press (blur dropped out).
            WarmUpBox {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = { lens(1.dp.toPx(), 2.dp.toPx(), chromaticAberration = true) },
                    highlight = { Highlight.Ambient },
                    shadow = null,
                )
            }
            // E: tab blob, segmented blob.
            WarmUpBox {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = { lens(1.dp.toPx(), 2.dp.toPx(), chromaticAberration = true) },
                    highlight = { Highlight.Default },
                    innerShadow = { InnerShadow(radius = 2.dp, alpha = 1f) },
                )
            }
        } else {
            // Frosted tier: panels, chips, orbs and buttons blur instead of refracting.
            WarmUpBox {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(FrostedBlur.toPx())
                    },
                    highlight = { Highlight.Plain },
                    shadow = null,
                )
            }
            WarmUpBox {
                drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                    },
                    highlight = { Highlight.Default },
                    shadow = null,
                )
            }
        }
        // Sheet scrim (constant colour controls, no blur).
        WarmUpBox {
            drawPlainBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    vibrancy()
                    colorControls(brightness = -0.1f, saturation = 1.25f)
                },
                onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.32f)) },
            )
        }
        // InteractiveHighlight glow: the same program the highlight compiles.
        if (glowBrush != null) {
            val (brush, shader) = glowBrush
            Box(
                Modifier
                    .size(6.dp)
                    .drawBehind {
                        shader.setFloatUniform("size", size.width, size.height)
                        shader.setColorUniform("color", Color.White.copy(alpha = 0.22f))
                        shader.setFloatUniform("radius", size.minDimension * 1.5f)
                        shader.setFloatUniform("position", size.width / 2f, size.height / 2f)
                        drawRect(brush, blendMode = BlendMode.Plus)
                    }
            )
        }
    }
}

@Composable
private fun WarmUpBox(glass: Modifier.() -> Modifier) {
    Box(Modifier.size(6.dp).glass())
}

package com.theveloper.pixelplay.ui.glass.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Ported from the Backdrop library's own catalog app (utils/InteractiveHighlight.kt, Apache-2.0) via
 * NexHome: a finger-tracking glow whose position GLIDES after the finger ([LiquidMotion.GlideSpring]),
 * tinted by [color] (white by default). Kept dim on purpose — the owner wanted it to "glow, not blind":
 * [FlatGlow] / [SpotGlow] are NexHome's approved levels.
 *
 * Also drives the kit's water "swell": [swell] rises with [LiquidMotion.PressSpring] on touch
 * and falls back with the bouncy [LiquidMotion.ReleaseSpring] (overshoot included).
 * [gestureModifier] only INSPECTS pointers — it never consumes, so scrolling and clicks still work.
 *
 * PixlAudio changes: the glow brushes are built once (the AGSL brush once per instance, the API
 * 31–32 radial fallback once per tint, positioned by a transform) instead of on every frame; finger
 * moves are coalesced to one glide launch per frame; the shader source is shared with the warm-up
 * ([GlowShaderString]) so the warm-up compiles the very program used here.
 */
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
    val color: () -> Color = { Color.White },
) {

    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val swellAnimation =
        Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero

    /** 0..1 glow progress (non-bouncy). */
    val pressProgress: Float get() = pressProgressAnimation.value

    /** Press swell progress: 0 at rest, 1 fully swollen; briefly overshoots on release. */
    val swell: Float get() = swellAnimation.value

    /** Glided finger offset from the touch-down point (drives the jelly translation). */
    val offset: Offset get() = positionAnimation.value - startPosition

    /** Glided finger position in local coordinates. */
    val fingerPosition: Offset get() = positionAnimation.value

    // Created lazily on the first press so idle list tiles never compile AGSL.
    private var shader: RuntimeShader? = null
    private var shaderBrush: ShaderBrush? = null

    // API 31–32 fallback: a unit radial gradient per tint, scaled/translated into place per frame.
    private var fallbackTint: Color = Color.Unspecified
    private var fallbackBrush: Brush? = null

    private var glideTarget = Offset.Zero
    private var glideLaunchPending = false

    private fun obtainShader(): RuntimeShader =
        shader ?: RuntimeShader(GlowShaderString).also { shader = it }

    private fun obtainShaderBrush(glow: RuntimeShader): ShaderBrush =
        shaderBrush ?: ShaderBrush(glow.asComposeShader()).also { shaderBrush = it }

    private fun obtainFallbackBrush(tint: Color): Brush {
        val cached = fallbackBrush
        if (cached != null && tint == fallbackTint) return cached
        return Brush.radialGradient(
            colors = listOf(tint.copy(alpha = SpotGlow), Color.Transparent),
            center = Offset.Zero,
            radius = 1f
        ).also {
            fallbackBrush = it
            fallbackTint = tint
        }
    }

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                val tint = color()
                drawRect(
                    tint.copy(alpha = FlatGlow * progress),
                    blendMode = BlendMode.Plus
                )
                val position = position(size, positionAnimation.value)
                val px = position.x.fastCoerceIn(0f, size.width)
                val py = position.y.fastCoerceIn(0f, size.height)
                val radius = size.minDimension * 1.5f
                if (isRuntimeShaderSupported()) {
                    val glow = obtainShader()
                    glow.apply {
                        setFloatUniform("size", size.width, size.height)
                        setColorUniform("color", tint.copy(alpha = SpotGlow * progress))
                        setFloatUniform("radius", radius)
                        setFloatUniform("position", px, py)
                    }
                    drawRect(
                        obtainShaderBrush(glow),
                        blendMode = BlendMode.Plus
                    )
                } else if (radius > 0f) {
                    // Same pixels as a radial gradient of radius `radius` centred on the finger:
                    // the unit gradient is transparent past 1, so drawing it over the scaled
                    // bounds of this rect covers exactly the old full-rect draw.
                    val brush = obtainFallbackBrush(tint)
                    withTransform({
                        translate(px, py)
                        scale(radius, radius, Offset.Zero)
                    }) {
                        drawRect(
                            brush = brush,
                            topLeft = Offset(-px / radius, -py / radius),
                            size = Size(size.width / radius, size.height / radius),
                            alpha = progress,
                            blendMode = BlendMode.Plus
                        )
                    }
                }
            }

            drawContent()
        }

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    glideTarget = down.position
                    animationScope.launch {
                        launch { positionAnimation.snapTo(startPosition) }
                        launch { pressProgressAnimation.animateTo(1f, LiquidMotion.GlowSpring) }
                        launch { swellAnimation.animateTo(1f, LiquidMotion.PressSpring) }
                    }
                },
                onDragEnd = { releaseAnimations() },
                onDragCancel = { releaseAnimations() }
            ) { change, _ ->
                glideTo(change.position)
            }
        }

    /** Retargets the glide; several moves inside one frame share a single launch. */
    private fun glideTo(target: Offset) {
        glideTarget = target
        if (glideLaunchPending) return
        glideLaunchPending = true
        animationScope.launch {
            glideLaunchPending = false
            positionAnimation.animateTo(glideTarget, LiquidMotion.GlideSpring)
        }
    }

    private fun releaseAnimations() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, LiquidMotion.GlowSpring) }
            launch { swellAnimation.animateTo(0f, LiquidMotion.ReleaseSpring) }
            launch { positionAnimation.animateTo(startPosition, LiquidMotion.GlideSpring) }
        }
    }
}

private const val FlatGlow = 0.10f
private const val SpotGlow = 0.22f

/**
 * The glow program. Shared with the shader warm-up so both compile the exact same source (NexHome's
 * warm-up used a different smoothstep edge, so the real glow still compiled on the first press).
 */
internal const val GlowShaderString = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""

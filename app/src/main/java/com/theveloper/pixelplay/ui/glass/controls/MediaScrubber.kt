package com.theveloper.pixelplay.ui.glass.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.theveloper.pixelplay.ui.glass.FrostedBlur
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.LocalGlassCapability
import com.theveloper.pixelplay.ui.glass.drawBackdrop
import com.theveloper.pixelplay.ui.glass.light.glassLightEmitter
import com.theveloper.pixelplay.ui.glass.light.rememberGlassLightReceiver
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import kotlin.math.roundToInt

/**
 * The glass seek bar, NexHome's `MediaScrubber` geometry: a 38 dp touch box holding a 30 dp glass
 * capsule track (lens 8/16, the palette's well tint) and a 22×36 chromatic thumb that is White@0.92
 * at rest and turns into a clear lens (2+8p / 4+12p) that swells 1.35 × 1.22 while held, emitting
 * [accent]. Horizontal drag scrubs and commits on release with a haptic every 5 %; tap seeks.
 *
 * PixlAudio changes (owner decision G3 and the NexHome defects): no fake waveform and no beat — the
 * played part is a plain accent fill; that fill is drawn in its own child layer, never in the glass
 * node, and it is also what the thumb refracts (NexHome refracted the whole exported track). The
 * position is read only in draw/layer lambdas, through a derived whole-pixel value, so playback
 * invalidates the bar about once per pixel of travel instead of every frame. NexHome's seek glide
 * smoothed a coarse simulated clock and is not ported: the player's position is frame-accurate.
 *
 * @param progress playback position 0..1; read in draw only. Back it with snapshot state.
 * @param onSeek called with the committed fraction (drag release or tap).
 * @param onScrubChange the fraction under the finger while scrubbing, then null when the drag ends
 *   (for readouts such as time labels; PixlAudio addition).
 */
@Composable
fun MediaScrubber(
    progress: () -> Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = LocalGlassPalette.current.accent,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    onScrubChange: (Float?) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val palette = LocalGlassPalette.current
    val capability = LocalGlassCapability.current
    val highlight = rememberKitHighlight(accent)
    val receiver = rememberGlassLightReceiver()
    val thumbReceiver = rememberGlassLightReceiver()
    val fillBackdrop = rememberLayerBackdrop()
    val thumbBackdrop = rememberCombinedBackdrop(backdrop, fillBackdrop)
    val currentAccent by rememberUpdatedState(accent)
    val currentProgress by rememberUpdatedState(progress)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrubChange by rememberUpdatedState(onScrubChange)

    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    val press = remember { Animatable(0f, 0.001f) }
    LaunchedEffect(scrubbing) {
        press.animateTo(
            if (scrubbing) 1f else 0f,
            if (scrubbing) LiquidMotion.PressSpring else LiquidMotion.ReleaseSpring,
        )
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .pointerInput(Unit) {
                var lastTick = -1
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        scrubValue = (offset.x / size.width.coerceAtLeast(1)).fastCoerceIn(0f, 1f)
                        lastTick = (scrubValue * 20f).toInt()
                        scrubbing = true
                        currentOnScrubChange(scrubValue)
                    },
                    onDragEnd = {
                        currentOnSeek(scrubValue)
                        scrubbing = false
                        currentOnScrubChange(null)
                    },
                    onDragCancel = {
                        scrubbing = false
                        currentOnScrubChange(null)
                    },
                ) { change, dragAmount ->
                    change.consume()
                    scrubValue = (scrubValue + dragAmount / size.width.coerceAtLeast(1)).fastCoerceIn(0f, 1f)
                    currentOnScrubChange(scrubValue)
                    val tick = (scrubValue * 20f).toInt()
                    if (tick != lastTick) {
                        lastTick = tick
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    currentOnSeek((position.x / size.width.coerceAtLeast(1)).fastCoerceIn(0f, 1f))
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        // Whole-pixel position: derived, so readers are only invalidated when it crosses a pixel.
        val shownPx by remember(widthPx) {
            derivedStateOf {
                val fraction = if (scrubbing) scrubValue else currentProgress()
                (fraction.fastCoerceIn(0f, 1f) * widthPx).roundToInt()
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        val k = kitLensFactor(highlight)
                        vibrancy()
                        if (capability.hasLens) lens(8.dp.toPx() * k, 16.dp.toPx() * k) else blur(FrostedBlur.toPx())
                    },
                    highlight = { receiver.highlight(Highlight.Plain) },
                    shadow = null,
                    onDrawSurface = { drawRect(palette.well) },
                )
                .then(receiver.modifier)
                .then(highlight.modifier)
                .then(highlight.gestureModifier),
        ) {
            // Played part: its own layer (captured for the thumb), clipped to the capsule.
            Box(
                Modifier
                    .matchParentSize()
                    .layerBackdrop(fillBackdrop)
                    .clip(Capsule())
                    .drawBehind {
                        val fill = shownPx.toFloat().coerceAtMost(size.width)
                        if (fill > 0f) {
                            drawRoundRect(
                                color = currentAccent.copy(alpha = 0.9f),
                                size = Size(fill, size.height),
                                cornerRadius = CornerRadius(size.height / 2f),
                            )
                        }
                    }
            )
        }
        Box(
            Modifier
                .graphicsLayer {
                    translationX = shownPx - size.width / 2f
                }
                .size(22.dp, 36.dp)
                .drawBackdrop(
                    backdrop = thumbBackdrop,
                    shape = { Capsule() },
                    effects = {
                        val pr = press.value.fastCoerceIn(0f, 1f)
                        blur(4.dp.toPx() * (1f - pr))
                        lens(2.dp.toPx() + 8.dp.toPx() * pr, 4.dp.toPx() + 12.dp.toPx() * pr, chromaticAberration = true)
                    },
                    highlight = {
                        val pr = press.value.fastCoerceIn(0f, 1f)
                        thumbReceiver.highlight(Highlight.Ambient.copy(alpha = 0.6f + 0.4f * pr))
                    },
                    shadow = { Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.14f)) },
                    innerShadow = {
                        val pr = press.value.fastCoerceIn(0f, 1f)
                        if (pr <= 0.01f) null else InnerShadow(radius = 6.dp * pr, alpha = pr)
                    },
                    layerBlock = {
                        val pr = press.value
                        scaleX = 1f + 0.35f * pr
                        scaleY = 1f + 0.22f * pr
                    },
                    onDrawSurface = {
                        val pr = press.value.fastCoerceIn(0f, 1f)
                        drawRect(Color.White.copy(alpha = 0.92f * (1f - pr)))
                        drawRect(currentAccent.copy(alpha = 0.3f * pr), blendMode = BlendMode.Plus)
                    },
                )
                .then(thumbReceiver.modifier)
                .glassLightEmitter({ currentAccent }),
        )
    }
}

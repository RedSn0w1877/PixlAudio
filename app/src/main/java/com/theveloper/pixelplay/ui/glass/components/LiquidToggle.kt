package com.theveloper.pixelplay.ui.glass.components

import androidx.compose.ui.platform.LocalViewConfiguration
import com.kyant.backdrop.backdrops.emptyBackdrop
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.drawBackdrop
import com.theveloper.pixelplay.ui.glass.light.glassLightEmitter
import com.theveloper.pixelplay.ui.glass.light.rememberGlassLightReceiver
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.utils.DampedDragAnimation
import com.theveloper.pixelplay.ui.glass.utils.applyBlobTransform
import kotlinx.coroutines.flow.collectLatest

/**
 * Ported from the Backdrop library's own catalog app (LiquidToggle.kt, Apache-2.0) via NexHome — a
 * real draggable glass blob: flick it, drag it partway and let go, it springs (with overshoot) to
 * whichever side it's closer to. The thumb receives light spill and emits [accentColor] while held.
 * At rest the thumb is opaque white and runs no blur, highlight or inner shadow.
 *
 * PixlAudio changes: colours default to the glass palette (accent = the album colour, track per
 * light/dark); [backdrop] defaults to [LocalGlassBackdrop]; [selected] / [onSelect] are read through
 * `rememberUpdatedState`, so an un-remembered lambda no longer restarts the sync effect; a disabled
 * toggle ([enabled] false, Material `Switch` parity) ignores touches and draws at 38 % alpha.
 *
 * At rest the thumb is opaque white, so it samples an empty backdrop then (identical pixels, no
 * offscreen backdrop draw); the real backdrop is swapped in while the thumb is held. A settings page
 * full of toggles therefore costs no glass work until one is touched.
 */
@Composable
fun LiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    accentColor: Color = LocalGlassPalette.current.accent,
    trackColor: Color = LocalGlassPalette.current.track,
    thumbColor: Color = LocalGlassPalette.current.thumb,
    enabled: Boolean = true,
) {
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    val currentAccent by rememberUpdatedState(accentColor)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var didDrag by remember { mutableStateOf(false) }
    val slop = remember { floatArrayOf(0f) }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var fraction by remember { mutableFloatStateOf(if (selected()) 1f else 0f) }
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.8f,
            onDragStarted = { slop[0] = 0f },
            onDragStopped = {
                if (wasCancelled) {
                    // A scrolling page took the gesture: neither a tap nor a drag, keep the value.
                    fraction = if (currentSelected()) 1f else 0f
                    didDrag = false
                } else if (didDrag) {
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    currentOnSelect(fraction == 1f)
                    didDrag = false
                } else {
                    fraction = if (currentSelected()) 0f else 1f
                    currentOnSelect(fraction == 1f)
                }
            },
            onDrag = drag@{ _, dragAmount ->
                if (!didDrag) {
                    // Horizontal travel past the touch slop makes it a drag; finger jitter while
                    // a list scrolls does not move the thumb.
                    slop[0] += dragAmount.x
                    if (kotlin.math.abs(slop[0]) <= touchSlop) return@drag
                    didDrag = true
                }
                val delta = dragAmount.x / dragWidth
                fraction =
                    if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                    else (fraction - delta).fastCoerceIn(0f, 1f)
            }
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { fraction ->
                dampedDragAnimation.updateValue(fraction)
            }
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentSelected() }
            .collectLatest { isSelected ->
                val target = if (isSelected) 1f else 0f
                if (target != fraction) {
                    fraction = target
                    dampedDragAnimation.animateToValue(target)
                }
            }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val receiver = rememberGlassLightReceiver()

    val heldBackdrop = rememberCombinedBackdrop(
        backdrop,
        rememberBackdrop(trackBackdrop) { drawBackdrop ->
            val progress = dampedDragAnimation.pressProgress
            val scaleX = lerp(2f / 3f, 0.75f, progress)
            val scaleY = lerp(0f, 0.75f, progress)
            scale(scaleX, scaleY) {
                drawBackdrop()
            }
        }
    )
    val restBackdrop = remember { emptyBackdrop() }
    // Recomposes only when the thumb starts or stops being held, never per frame.
    val thumbHeld by remember(dampedDragAnimation) {
        derivedStateOf { dampedDragAnimation.pressProgress > 0f }
    }

    Box(
        modifier.then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    val fraction = dampedDragAnimation.value.fastCoerceIn(0f, 1f)
                    drawRect(lerp(trackColor, accentColor, fraction))
                }
                .size(64f.dp, 28f.dp)
        )

        Box(
            Modifier
                .graphicsLayer {
                    val fraction = dampedDragAnimation.value
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) lerp(padding, padding + dragWidth, fraction)
                        else lerp(-padding, -(padding + dragWidth), fraction)
                }
                .semantics {
                    role = Role.Switch
                }
                .then(if (enabled) dampedDragAnimation.modifier else Modifier)
                .glassLightEmitter({ currentAccent })
                .drawBackdrop(
                    backdrop = if (thumbHeld) heldBackdrop else restBackdrop,
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        // At rest the thumb is opaque white (see onDrawSurface), so a blur under
                        // it is invisible GPU work repeated for every toggle on screen.
                        if (progress > 0f) blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            5f.dp.toPx() * progress,
                            10f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        receiver.highlight(
                            if (progress > 0f) {
                                Highlight.Ambient.copy(
                                    width = Highlight.Ambient.width / 1.5f,
                                    blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                    alpha = progress
                                )
                            } else {
                                null
                            }
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        if (progress > 0f) InnerShadow(radius = 4f.dp * progress, alpha = progress) else null
                    },
                    layerBlock = {
                        applyBlobTransform(dampedDragAnimation, velocityDivisor = 50f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(thumbColor.copy(alpha = (1f - progress).fastCoerceIn(0f, 1f)))
                    }
                )
                .then(receiver.modifier)
                .size(40f.dp, 24f.dp)
        )
    }
}

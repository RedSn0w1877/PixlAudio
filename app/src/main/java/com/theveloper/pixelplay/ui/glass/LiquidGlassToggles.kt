package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import kotlinx.coroutines.launch

/**
 * Universal Switch: a Liquid Glass toggle when [isGlassEnabled], a standard Material 3 [Switch]
 * otherwise.
 *
 * @param accentColor the "on" track colour in glass mode. [Color.Unspecified] (the default) uses
 *   the scheme's primary, so the toggle follows the album-art theme like everything else.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color = Color.Unspecified
) {
    if (!isGlassEnabled) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled
        )
        return
    }

    LiquidToggle(
        selected = { checked },
        onSelect = onCheckedChange,
        backdrop = LocalAppBackdrop.current,
        modifier = modifier,
        accentColor = accentColor,
        enabled = enabled
    )
}

/**
 * Port of the Backdrop catalog's `LiquidToggle` (Kyant0/AndroidLiquidGlass, Apache-2.0), made
 * fully **controlled**: it never flips itself. A tap or a drag release only calls [onSelect] with
 * the wanted state; the thumb then animates to whatever [selected] says. If the parent refuses the
 * change, the thumb simply springs back. Drag progress is the only local state.
 *
 * Tap anywhere on the toggle to flip it, or drag the thumb across. Disabled: 38% alpha and no
 * gestures. The thumb refracts only the track beneath it, never the page.
 */
@Composable
fun LiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    @Suppress("UNUSED_PARAMETER") backdrop: Backdrop,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.Unspecified,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val isDark = glassIsDark()
    val accent = if (accentColor.isSpecified) accentColor else scheme.primary
    val trackColor = scheme.onSurface.copy(alpha = if (isDark) 0.20f else 0.12f)
    val thumbRecipe = resolveRecipe(GlassRole.Thumb)
    val reduceMotion = LocalGlassReduceMotion.current

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20.dp.toPx() }
    val animationScope = rememberCoroutineScope()

    val isSelected = selected()
    val currentSelected by rememberUpdatedState(isSelected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    val dampedDragAnimation = remember(animationScope, dragWidth, isLtr) {
        // Local drag state: where the finger has pushed the thumb, and whether it moved at all.
        var fraction = if (isSelected) 1f else 0f
        var didDrag = false
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {
                fraction = if (currentSelected) 1f else 0f
                didDrag = false
            },
            onDragStopped = {
                val wanted = if (didDrag) fraction >= 0.5f else !currentSelected
                didDrag = false
                currentOnSelect(wanted)
                // Settle on whatever the parent decided — give it a frame to recompose first.
                animationScope.launch {
                    androidx.compose.runtime.withFrameNanos { }
                    animateToValue(if (currentSelected) 1f else 0f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) didDrag = dragAmount.x != 0f
                val delta = dragAmount.x / dragWidth
                fraction = (if (isLtr) fraction + delta else fraction - delta).fastCoerceIn(0f, 1f)
                updateValue(fraction)
            }
        )
    }

    LaunchedEffect(dampedDragAnimation, isSelected) {
        val target = if (isSelected) 1f else 0f
        if (dampedDragAnimation.targetValue != target) {
            dampedDragAnimation.animateToValue(target)
        }
    }

    val trackBackdrop = rememberPageBackdrop()

    Box(
        modifier
            .alpha(if (enabled) 1f else 0.38f)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = ToggleableState(isSelected)
                if (!enabled) {
                    disabled()
                } else {
                    onClick {
                        currentOnSelect(!currentSelected)
                        true
                    }
                }
            }
            .then(if (enabled) dampedDragAnimation.modifier else Modifier),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .pageBackdrop(trackBackdrop)
                .clip(GlassShapes.Capsule)
                .drawBehind {
                    drawRect(lerp(trackColor, accent, dampedDragAnimation.value.fastCoerceIn(0f, 1f)))
                }
                .size(64.dp, 28.dp)
        )

        val thumbBackdrop = rememberBackdrop(trackBackdrop) { drawBackdrop ->
            val p = dampedDragAnimation.pressProgress
            scale(lerp(2f / 3f, 0.75f, p), lerp(0f, 0.75f, p)) { drawBackdrop() }
        }
        val thumbLayer: GraphicsLayerScope.() -> Unit = remember(dampedDragAnimation, reduceMotion) {
            { applyGlassSquash(dampedDragAnimation, velocityDivisor = 50f, reduceMotion = reduceMotion) }
        }
        Box(
            Modifier
                .graphicsLayer {
                    val f = dampedDragAnimation.value
                    val padding = 2.dp.toPx()
                    translationX = if (isLtr) lerp(padding, padding + dragWidth, f)
                    else lerp(-padding, -(padding + dragWidth), f)
                }
                .liquidGlass(
                    recipe = thumbRecipe,
                    shape = GlassShapes.Capsule,
                    materialize = { dampedDragAnimation.pressProgress },
                    backdrop = thumbBackdrop,
                    layerBlock = thumbLayer
                )
                .size(40.dp, 24.dp)
        )
    }
}

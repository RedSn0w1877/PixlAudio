package com.theveloper.pixelplay.ui.glass.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.Capsule
import com.theveloper.pixelplay.ui.glass.FrostedBlur
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.LocalGlassCapability
import com.theveloper.pixelplay.ui.glass.components.GlassIcon
import com.theveloper.pixelplay.ui.glass.components.GlassText
import com.theveloper.pixelplay.ui.glass.drawBackdrop
import com.theveloper.pixelplay.ui.glass.light.glassLightEmitter
import com.theveloper.pixelplay.ui.glass.light.rememberGlassLightReceiver
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

/**
 * A selectable glass capsule (ported from NexHome): 40 dp, lens 6/12, White@0.06 base. Selecting
 * floods it with [accent] (Hue 0.9 + 0.42) and makes it bounce 4 % bigger; pressing swells + jellies
 * it (1.16) with a gliding highlight, and it emits [accent] as light while held. A `TextHandleMove`
 * haptic fires on click.
 *
 * PixlAudio changes: palette colours (accent = album colour; the label uses the palette's content
 * colours so it stays readable in light mode); frosted-tier blur on API 31–32.
 */
@Composable
fun LiquidChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = LocalGlassPalette.current.accent,
    backdrop: Backdrop = LocalGlassBackdrop.current,
) {
    val highlight = rememberKitHighlight(accent)
    val receiver = rememberGlassLightReceiver()
    val haptic = LocalHapticFeedback.current
    val capability = LocalGlassCapability.current
    val palette = LocalGlassPalette.current
    val currentAccent by rememberUpdatedState(accent)
    val currentOnClick by rememberUpdatedState(onClick)
    val selection = remember { Animatable(if (selected) 1f else 0f, 0.001f) }
    LaunchedEffect(selected) {
        selection.animateTo(if (selected) 1f else 0f, LiquidMotion.ReleaseSpring)
    }

    Row(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    val k = kitLensFactor(highlight)
                    // Small capsule: a shallow lens already reads as glass.
                    vibrancy()
                    if (capability.hasLens) {
                        lens(6.dp.toPx() * k, 12.dp.toPx() * k)
                    } else {
                        blur(FrostedBlur.toPx())
                    }
                },
                highlight = {
                    val s = selection.value.fastCoerceIn(0f, 1f)
                    receiver.highlight(
                        if (s > 0.01f) {
                            Highlight(
                                width = 1.dp,
                                alpha = s,
                                style = HighlightStyle.Default(color = currentAccent.copy(alpha = 0.8f), falloff = 1.2f),
                            )
                        } else {
                            Highlight.Plain
                        }
                    )
                },
                shadow = null,
                layerBlock = { kitJelly(highlight, LiquidMotion.ButtonPressScale, 1f + 0.04f * selection.value) },
                onDrawSurface = {
                    val s = selection.value.fastCoerceIn(0f, 1f)
                    drawRect(Color.White.copy(alpha = 0.06f))
                    if (s > 0.001f) {
                        drawRect(currentAccent.copy(alpha = 0.9f * s), blendMode = BlendMode.Hue)
                        drawRect(currentAccent.copy(alpha = 0.42f * s))
                    }
                },
            )
            .then(receiver.modifier)
            .then(highlight.modifier)
            .then(highlight.gestureModifier)
            .glassLightEmitter({ currentAccent })
            .clickable(interactionSource = null, indication = null, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentOnClick()
            }
            .height(40.dp)
            .padding(horizontal = if (icon != null) 14.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) GlassIcon(icon, tint = if (selected) palette.primary else currentAccent, size = 18.dp)
        GlassText(label, style = GlassType.Label, color = if (selected) palette.primary else palette.secondary, maxLines = 1)
    }
}

/**
 * Horizontally scrolling row of [LiquidChip]s. Vertical padding leaves room for the chips' swell
 * (the scroll container clips), and [contentPadding] lets chips glide to the section edges.
 */
@Composable
fun LiquidChipRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

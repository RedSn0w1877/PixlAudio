package com.theveloper.pixelplay.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.theveloper.pixelplay.ui.glass.components.GlassPanel
import com.theveloper.pixelplay.ui.glass.components.GlassText
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Glass mode's tab strip for tab sets that do not fit a [com.theveloper.pixelplay.ui.glass.controls.LiquidSegmented]
 * (more than five tabs, or labels too long for equal widths): the top bar's floating capsule (a
 * light [GlassPanel], top-bar tint, no glint) holding a row of text tabs that scrolls when it
 * overflows. The selected tab sits on NexHome's resting blob — the `LiquidBottomTabs` /
 * `LiquidSegmented` blob at rest (White 14 % dark / the light blob fill, plus the accent at 14 %) —
 * which glides to a new tab with the kit's blob spring (overshoot kept), and its label takes the
 * accent, as the blob's refracted accent copy does. [trailing] (an edit button) sits at the end,
 * outside the scrolling row.
 *
 * Performance: ONE `drawBackdrop` (the capsule), whatever the tab count. The blob is a plain
 * rounded rect drawn behind the tabs, its position animated and read only in draw; tab bounds are
 * recorded at placement and never force a recomposition of the tabs.
 */
@Composable
fun GlassTabStrip(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val palette = LocalGlassPalette.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()
    val count = labels.size
    // [left, width] per tab in the scrolling row's content coordinates, written at placement.
    val bounds = remember(count) { mutableStateListOf<Float>().apply { repeat(count * 2) { add(0f) } } }
    val blobLeft = remember { Animatable(0f) }
    val blobWidth = remember { Animatable(0f) }
    val currentSelected by rememberUpdatedState(selectedIndex)
    val blobSpring = remember {
        spring(LiquidMotion.BlobValueDamping, LiquidMotion.BlobValueStiffness, visibilityThreshold = 0.5f)
    }

    LaunchedEffect(bounds, scrollState) {
        snapshotFlow {
            val i = currentSelected
            if (i in 0 until count) Offset(bounds[i * 2], bounds[i * 2 + 1]) else Offset.Unspecified
        }.collectLatest { target ->
            if (!target.isSpecified || target.y <= 0f) return@collectLatest
            val (x, w) = target
            if (blobWidth.value <= 0f) {
                blobLeft.snapTo(x)
                blobWidth.snapTo(w)
            } else {
                launch { blobLeft.animateTo(x, blobSpring) }
                launch { blobWidth.animateTo(w, blobSpring) }
            }
            // Keep the selected tab in view, centred when the row overflows.
            val viewport = scrollState.viewportSize
            if (viewport > 0 && scrollState.maxValue > 0) {
                val centred = (x + w / 2f - viewport / 2f).toInt().coerceIn(0, scrollState.maxValue)
                if (centred != scrollState.value) scrollState.animateScrollTo(centred)
            }
        }
    }

    val blobRest = if (palette.isDark) Color.White.copy(alpha = 0.14f) else palette.blobRest
    val blobAccent = palette.accent.copy(alpha = 0.14f)

    GlassPanel(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = Capsule(),
        tint = palette.topBar,
        showHighlight = false,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(Capsule())
                    .horizontalScroll(scrollState)
                    .drawBehind {
                        val w = blobWidth.value
                        if (w > 0f) {
                            val radius = CornerRadius(size.height / 2f)
                            val topLeft = Offset(blobLeft.value, 0f)
                            val blobSize = Size(w, size.height)
                            drawRoundRect(blobRest, topLeft, blobSize, radius)
                            drawRoundRect(blobAccent, topLeft, blobSize, radius)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                labels.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .onPlaced { coordinates ->
                                val x = coordinates.positionInParent().x
                                val w = coordinates.size.width.toFloat()
                                if (index * 2 + 1 < bounds.size) {
                                    if (bounds[index * 2] != x) bounds[index * 2] = x
                                    if (bounds[index * 2 + 1] != w) bounds[index * 2 + 1] = w
                                }
                            }
                            .clip(Capsule())
                            .clickable(role = Role.Tab) {
                                if (index != currentSelected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                onSelect(index)
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlassText(
                            text = label,
                            style = GlassType.Label,
                            color = if (selected) palette.accent else palette.secondary,
                            maxLines = 1,
                        )
                    }
                }
            }
            trailing?.invoke(this)
        }
    }
}

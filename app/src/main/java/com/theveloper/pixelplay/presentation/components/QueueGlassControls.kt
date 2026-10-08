package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.ui.glass.GlassCircleAction
import com.theveloper.pixelplay.ui.glass.GlassLitAlpha
import com.theveloper.pixelplay.ui.glass.GlassPillButton
import com.theveloper.pixelplay.ui.glass.GlassPillShape
import com.theveloper.pixelplay.ui.glass.components.GlassPanel
import com.theveloper.pixelplay.ui.glass.controls.LocalLensBloom
import com.theveloper.pixelplay.ui.glass.glassMorphBounds
import com.theveloper.pixelplay.ui.glass.morphFadeIn
import com.theveloper.pixelplay.ui.glass.morphFadeOut
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import kotlin.math.roundToInt

// Glass mode's queue toolbar geometry. The Row keeps Material's 70 dp height and bottom offset, so
// the list's bottom padding and the undo bar's position are the same in both modes.
internal val QueueToolbarRowHeight = 70.dp
private val QueueToolbarCircleSize = 56.dp
private val QueueToolbarGap = 12.dp
private val QueueMoreOrbSize = 64.dp
/** Three circles, three gaps and the ⋯ slot. */
private val QueueToolbarRowWidth = QueueToolbarCircleSize * 3 + QueueToolbarGap * 3 + QueueMoreOrbSize
internal val QueueMenuPillHeight = 56.dp
private val QueueMenuPillGap = 10.dp
private val QueueMenuPillMinWidth = 184.dp
private val QueueMenuPillMaxWidth = 260.dp

/**
 * The morphing ⋯ panel refracts at the pill's lens (24 / 48, halved for a light panel); at rest it
 * scales that to 2/3 so the circle matches the kit's other circles (16 / 32 halved).
 */
private const val MoreOrbLensScale = 16f / 24f

/** Where the menu's upper pills start materialising: Clear (next to Save) first, then Locate. */
private const val ClearPillStart = 0.35f
private const val LocatePillStart = 0.5f

/**
 * Liquid Glass mode's queue toolbar and ⋯ menu (owner decisions 2026-10-07, options A + B).
 *
 * - Shuffle, repeat and the sleep timer are each their own glass circle (56 dp, subtle tint), lit
 *   with the accent when on. There is no backing capsule: a glass button on a glass bar is
 *   glass-on-glass, which the kit flattens anyway, so the capsule only cost a lens.
 * - The ⋯ circle IS the menu's "Save as playlist" pill: one capsule GlassPanel flows from the ⋯
 *   slot to the pill ([glassMorphBounds], [LiquidMotion.MorphOpenSpring] / MorphCloseSpring), its
 *   lens deepening from the circle's to the pill's and the accent flooding in. The toolbar fades
 *   out under it, and Locate / Clear materialise above it (alpha, a 16 dp rise and a staggered
 *   lens bloom), so nothing glass overlaps while it moves.
 * - Under the menu: the palette's dim (not Material's 0.55 scrim and opaque gradient), tap to close.
 *   System back closes the menu before the queue (QueueBottomSheet's BackHandler).
 *
 * Everything animated is read in layout or draw lambdas; the only compositions are the two
 * crossings of [expanded] and the menu's presence. Material 3 mode keeps its own toolbar and menu.
 *
 * @param bottomPadding the toolbar row's distance from the sheet's bottom edge.
 * @param dragModifier the sheet's direct drag, so the toolbar row still drags the queue.
 */
@Composable
internal fun GlassQueueControls(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    bottomPadding: Dp,
    dragModifier: Modifier,
    isShuffleOn: Boolean,
    repeatMode: Int,
    isTimerActive: State<Boolean>,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onTimerClick: () -> Unit,
    showLocate: Boolean,
    onLocate: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val bloom = LocalLensBloom.current
    val haptic = LocalHapticFeedback.current
    val morph = remember { Animatable(0f, visibilityThreshold = 0.001f) }
    LaunchedEffect(expanded) {
        morph.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = if (expanded) LiquidMotion.MorphOpenSpring else LiquidMotion.MorphCloseSpring,
        )
    }
    val menuPresent by remember { derivedStateOf { morph.value > 0.001f } }

    // The ⋯ slot in this container's coordinates. Written by the slot's placement (only when it
    // actually moves, e.g. rotation), read by the morph's layout: never a recomposition.
    var anchor by remember { mutableStateOf(Rect.Zero) }

    val latestExpanded = rememberUpdatedState(expanded)
    val latestOnExpandedChange = rememberUpdatedState(onExpandedChange)
    val latestOnSave = rememberUpdatedState(onSave)
    val onMorphClick = remember(haptic) {
        {
            if (latestExpanded.value) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                latestOnSave.value()
            } else {
                latestOnExpandedChange.value(true)
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPadding)
                .height(QueueToolbarRowHeight)
                .then(dragModifier)
                // Under the open menu the toolbar is gone: keep TalkBack off it too.
                .then(if (expanded) Modifier.clearAndSetSemantics { } else Modifier),
            horizontalArrangement = Arrangement.spacedBy(QueueToolbarGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassQueueToolbarCircle(
                onClick = onToggleShuffle,
                active = isShuffleOn,
                morph = morph,
                contentDescription = stringResource(R.string.queue_cd_toggle_shuffle_action),
            ) {
                Icon(imageVector = Icons.Rounded.Shuffle, contentDescription = null)
            }
            GlassQueueToolbarCircle(
                onClick = onToggleRepeat,
                active = repeatMode != Player.REPEAT_MODE_OFF,
                morph = morph,
                contentDescription = stringResource(R.string.queue_cd_toggle_repeat_action),
            ) {
                Icon(
                    imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = null,
                )
            }
            GlassQueueToolbarCircle(
                onClick = onTimerClick,
                active = isTimerActive.value,
                morph = morph,
                contentDescription = stringResource(R.string.queue_cd_sleep_timer_action),
            ) {
                Icon(imageVector = Icons.Rounded.Timer, contentDescription = null)
            }
            // The ⋯ slot: an empty spacer the morphing panel sits on at rest. Relative positions
            // (slot in Row, Row in this Box), so the anchor holds still while the sheet slides.
            Spacer(
                Modifier
                    .size(QueueMoreOrbSize)
                    .onPlaced { coordinates ->
                        val container = coordinates.parentLayoutCoordinates?.parentLayoutCoordinates
                            ?: return@onPlaced
                        if (!container.isAttached) return@onPlaced
                        val topLeft = container.localPositionOf(coordinates, Offset.Zero)
                        val rect = Rect(topLeft, coordinates.size.toSize())
                        if (rect != anchor) anchor = rect
                    }
            )
        }

        if (menuPresent) {
            // The menu's dim: NexHome's Dim fading with the morph, swallowing every touch.
            Box(
                Modifier
                    .matchParentSize()
                    .zIndex(20f)
                    .graphicsLayer { alpha = morph.value.coerceIn(0f, 1f) }
                    .background(palette.dim)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                latestOnExpandedChange.value(false)
                            }
                        }
                    }
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(40f)
                    .padding(
                        bottom = bottomPadding + (QueueToolbarRowHeight - QueueMenuPillHeight) / 2 +
                            QueueMenuPillHeight + QueueMenuPillGap
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(QueueMenuPillGap),
            ) {
                if (showLocate) {
                    GlassQueueMenuPill(
                        text = stringResource(R.string.queue_action_locate_current_song),
                        icon = Icons.Rounded.MyLocation,
                        morph = morph,
                        start = LocatePillStart,
                        // Only while the menu is open: closing, the pills fade out but stay laid
                        // out until the morph settles, and a stray second tap must not act.
                        onClick = {
                            if (latestExpanded.value) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onLocate()
                            }
                        },
                    )
                }
                GlassQueueMenuPill(
                    text = stringResource(R.string.queue_action_clear_queue),
                    icon = Icons.Filled.ClearAll,
                    morph = morph,
                    start = ClearPillStart,
                    // Destructive: the scheme's error colour as the glass tint, content stays primary.
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.32f),
                    onClick = {
                        if (latestExpanded.value) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onClear()
                        }
                    },
                )
            }
        }

        val saveLabel = stringResource(R.string.queue_action_save_as_playlist)
        val moreLabel = stringResource(R.string.queue_cd_more_action)
        val accent = palette.accent
        val morphLens = remember(bloom, morph) {
            { bloom() * lerp(MoreOrbLensScale, 1f, morph.value.coerceIn(0f, 1f)) }
        }
        val morphFill = remember(accent, morph) {
            val draw: DrawScope.() -> Unit = {
                val p = morph.value.coerceIn(0f, 1f)
                if (p > 0.001f) drawRect(accent.copy(alpha = GlassLitAlpha * p))
            }
            draw
        }
        GlassPanel(
            modifier = Modifier
                .zIndex(40f)
                .glassMorphBounds(
                    progress = { morph.value },
                    // Until the ⋯ slot has been placed once (the first frame of a freshly composed,
                    // still hidden queue), sit where the slot will be instead of at 0 × 0.
                    from = { container, _ ->
                        val placed = anchor
                        if (!placed.isEmpty) {
                            placed
                        } else {
                            queueMoreOrbFallbackRect(
                                containerWidth = container.width.toFloat(),
                                containerHeight = container.height.toFloat(),
                                toolbarBottomPx = bottomPadding.toPx(),
                                rowHeightPx = QueueToolbarRowHeight.toPx(),
                                rowWidthPx = QueueToolbarRowWidth.toPx(),
                                orbPx = QueueMoreOrbSize.toPx(),
                            )
                        }
                    },
                    to = { container, content ->
                        val heightPx = QueueMenuPillHeight.toPx()
                        val widthPx = content.maxIntrinsicWidth(heightPx.roundToInt()).toFloat()
                            .coerceIn(QueueMenuPillMinWidth.toPx(), QueueMenuPillMaxWidth.toPx())
                            .coerceAtMost(container.width.toFloat())
                        queueSavePillRect(
                            containerWidth = container.width.toFloat(),
                            containerHeight = container.height.toFloat(),
                            toolbarBottomPx = bottomPadding.toPx(),
                            rowHeightPx = QueueToolbarRowHeight.toPx(),
                            pillWidthPx = widthPx,
                            pillHeightPx = heightPx,
                        )
                    },
                )
                // At rest the ⋯ circle is part of the toolbar, which drags the sheet.
                .then(if (expanded) Modifier else dragModifier)
                .semantics { contentDescription = if (expanded) saveLabel else moreLabel },
            shape = GlassPillShape,
            tint = palette.tintSubtle,
            accent = accent,
            onClick = onMorphClick,
            showHighlight = false,
            refractionHeight = 24.dp,
            refractionAmount = 48.dp,
            pressScale = LiquidMotion.OrbPressScale,
            enterProgress = morphLens,
            onDrawSurface = morphFill,
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreHoriz,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = morphFadeOut(morph.value) },
            )
            // Measured at its natural width (never squeezed by the growing pill) and centred;
            // the capsule clips it while it fades in.
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .wrapContentWidth(unbounded = true)
                    .graphicsLayer { alpha = morphFadeIn(morph.value) }
                    .clearAndSetSemantics { }
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Filled.LibraryAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(text = saveLabel, style = GlassType.BodyStrong, maxLines = 1)
            }
        }
    }
}

/**
 * The menu's "Save as playlist" pill in the controls' coordinates: centred horizontally and
 * vertically on the toolbar row (whose bottom sits [toolbarBottomPx] above the container's bottom),
 * so the ⋯ circle stretches sideways into it instead of jumping.
 */
internal fun queueSavePillRect(
    containerWidth: Float,
    containerHeight: Float,
    toolbarBottomPx: Float,
    rowHeightPx: Float,
    pillWidthPx: Float,
    pillHeightPx: Float,
): Rect {
    val bottom = containerHeight - toolbarBottomPx - (rowHeightPx - pillHeightPx) / 2f
    val left = (containerWidth - pillWidthPx) / 2f
    return Rect(left = left, top = bottom - pillHeightPx, right = left + pillWidthPx, bottom = bottom)
}

/**
 * Where the ⋯ slot sits before it has been placed: the end of the centred toolbar row (left to
 * right; a right-to-left layout corrects it on the next frame, while the queue is still hidden).
 */
internal fun queueMoreOrbFallbackRect(
    containerWidth: Float,
    containerHeight: Float,
    toolbarBottomPx: Float,
    rowHeightPx: Float,
    rowWidthPx: Float,
    orbPx: Float,
): Rect {
    val left = (containerWidth - rowWidthPx) / 2f + rowWidthPx - orbPx
    val top = containerHeight - toolbarBottomPx - rowHeightPx + (rowHeightPx - orbPx) / 2f
    return Rect(left = left, top = top, right = left + orbPx, bottom = top + orbPx)
}

/**
 * One toolbar toggle: a 56 dp glass circle, lit with the accent while on (glow spring, drawn in the
 * circle's surface layer only), `selected` for TalkBack, fading and shrinking away while the menu
 * opens (layer reads; the lens itself is left alone, so the fade costs no lens renders).
 */
@Composable
private fun GlassQueueToolbarCircle(
    onClick: () -> Unit,
    active: Boolean,
    morph: Animatable<Float, AnimationVector1D>,
    contentDescription: String,
    content: @Composable BoxScope.() -> Unit,
) {
    val lit = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = LiquidMotion.GlowSpring,
        label = "queueToolbarLit",
    )
    val litLevel = remember(lit) { { lit.value } }
    GlassCircleAction(
        onClick = onClick,
        modifier = Modifier
            .graphicsLayer {
                val f = morphFadeOut(morph.value)
                alpha = f
                val s = lerp(0.86f, 1f, f)
                scaleX = s
                scaleY = s
            }
            .semantics { selected = active },
        contentDescription = contentDescription,
        size = QueueToolbarCircleSize,
        lit = litLevel,
        enterProgress = LocalLensBloom.current,
        content = content,
    )
}

/**
 * Locate / Clear in the open menu: secondary glass pills that materialise from [start] of the
 * morph (alpha, a 16 dp rise, a slight swell and their lens blooming in).
 */
@Composable
private fun GlassQueueMenuPill(
    text: String,
    icon: ImageVector,
    morph: Animatable<Float, AnimationVector1D>,
    start: Float,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    val bloom = LocalLensBloom.current
    val lens = remember(bloom, morph, start) { { bloom() * morphFadeIn(morph.value, start) } }
    GlassPillButton(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = QueueMenuPillMinWidth, max = QueueMenuPillMaxWidth)
            .height(QueueMenuPillHeight)
            .graphicsLayer {
                val a = morphFadeIn(morph.value, start)
                alpha = a
                translationY = (1f - a) * 16.dp.toPx()
                val s = lerp(0.9f, 1f, a)
                scaleX = s
                scaleY = s
            },
        prominent = false,
        tint = tint,
        enterProgress = lens,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(text = text, maxLines = 1)
    }
}

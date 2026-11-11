package com.theveloper.pixelplay.presentation.spatial

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.lerp

// The colours of the player's Spatial audio button and of the sheet's rows. Both come from a theme that can change on
// every frame: in the full player it is the album's palette, which fades over about 40 frames after every skip, and
// the sheet opened from the player wears it too. A colour of the theme is used as it comes, at once, the way Lyrics
// and Ask Taizo beside the button use theirs. Only the flip between the off look and the on look animates, as a
// fraction from 0 to 1 that the two current colours are mixed by when they are drawn. Animating the colours
// themselves would restart the animation on every frame of a fade and leave the button and the rows behind
// everything around them, and a theme change starts no animation at all this way.

/** The player button's fill ([container]) and its icon and label ([content]), read when they are drawn. */
@Stable
internal class SpatialButtonColors(val container: ColorProducer, val content: ColorProducer)

/**
 * The player button's colours: the row's chip colours, [containerColor] behind [contentColor], while it is off or
 * held back, and the two swapped while [emphasized] (Material's selected look, the content at full opacity). The swap
 * animates once [animate] is true and jumps before that; a new [containerColor] or [contentColor] applies at once.
 */
@Composable
internal fun rememberSpatialButtonColors(
    emphasized: Boolean,
    animate: Boolean,
    containerColor: Color,
    contentColor: Color,
): SpatialButtonColors {
    val spec: AnimationSpec<Float> = if (animate) tween(durationMillis = ButtonSwapMillis) else snap()
    val emphasis = animateFloatAsState(
        targetValue = if (emphasized) 1f else 0f,
        animationSpec = spec,
        label = "spatialButtonEmphasis",
    )
    val chip by rememberUpdatedState(containerColor)
    val onChip by rememberUpdatedState(contentColor)
    return remember(emphasis) {
        SpatialButtonColors(
            container = ColorProducer { mix(chip, onChip, emphasis.value) },
            content = ColorProducer { mix(onChip, chip.copy(alpha = 1f), emphasis.value) },
        )
    }
}

/**
 * The fill of one of the sheet's rows: [unselectedColor], or [selectedColor] while [selected]. The selection
 * animates; a new colour from the theme applies at once.
 */
@Composable
internal fun rememberSpatialChoiceFill(
    selected: Boolean,
    unselectedColor: Color,
    selectedColor: Color,
): ColorProducer {
    val selection = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = ChoiceSwapMillis),
        label = "spatialChoiceSelection",
    )
    val unselected by rememberUpdatedState(unselectedColor)
    val chosen by rememberUpdatedState(selectedColor)
    return remember(selection) { ColorProducer { mix(unselected, chosen, selection.value) } }
}

/**
 * [start] and [stop] mixed by [fraction] (in Oklab, as Compose animates colours), and exactly one of them at either
 * end, so a draw at rest, nearly every draw, does no colour maths.
 */
private fun mix(start: Color, stop: Color, fraction: Float): Color = when {
    fraction <= 0f -> start
    fraction >= 1f -> stop
    else -> lerp(start, stop, fraction)
}

private const val ButtonSwapMillis = 220
private const val ChoiceSwapMillis = 200

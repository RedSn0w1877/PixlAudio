package com.theveloper.pixelplay.presentation.screens.setup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * True when the system "Remove animations" setting is on. The setup flow then skips its springs,
 * drifting background and looping motion, and shows the final state straight away.
 */
internal val LocalSetupReduceMotion = compositionLocalOf { false }

/**
 * A 0..1 progress that springs to 1 the moment the page is composed. Pages are only composed while
 * they are (becoming) visible, so this replays every time a page slides in.
 *
 * The value is exposed as a [State] and must only be read inside draw / graphicsLayer lambdas, so the
 * animation never recomposes anything.
 */
@Composable
internal fun rememberPageEntrance(): State<Float> {
    val reduceMotion = LocalSetupReduceMotion.current
    val animatable = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            animatable.snapTo(1f)
        } else {
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 240f)
            )
        }
    }
    return animatable.asState()
}

/**
 * Staggered rise-and-fade for one element of a page. [index] shifts the start so elements arrive one
 * after another. Reads [progress] in the graphicsLayer block only (draw phase).
 */
internal fun Modifier.setupEntrance(
    progress: State<Float>,
    index: Int,
    distance: Dp = 36.dp
): Modifier = graphicsLayer {
    val delay = (index * 0.12f).coerceAtMost(0.6f)
    val local = ((progress.value - delay) / (1f - delay)).coerceAtLeast(0f)
    alpha = local.coerceIn(0f, 1f)
    translationY = (1f - local) * distance.toPx()
}

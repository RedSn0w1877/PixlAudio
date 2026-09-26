package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.presentation.viewmodel.PlayerSheetState
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

private object AlwaysOn : State<Boolean> {
    override val value: Boolean get() = true
}

/**
 * True while the expanded player does not cover the screen. Decorative loops on Home park while
 * it does, so the frame loop can idle behind the player (and behind the lyrics view) instead of
 * re-rendering, and re-blurring, a hidden page at 120 Hz. Read it only in effects or draw.
 */
@Composable
fun rememberNotCoveredByPlayer(playerViewModel: PlayerViewModel): State<Boolean> {
    val sheetState = playerViewModel.sheetState.collectAsStateWithLifecycle()
    return remember(sheetState) { derivedStateOf { sheetState.value != PlayerSheetState.EXPANDED } }
}

/**
 * A linear loop from [initialValue] to [targetValue] over [durationMillis], restarting at the
 * start: the motion of `infiniteRepeatable(tween(durationMillis, easing = LinearEasing))`, but it
 * parks while [enabled] is false (it is hidden then) and resumes where it stopped. Read the
 * returned state only in draw.
 */
@Composable
fun rememberAmbientLinearLoop(
    initialValue: Float,
    targetValue: Float,
    durationMillis: Int,
    enabled: State<Boolean>? = null,
): State<Float> {
    val gate = enabled ?: AlwaysOn
    val animatable = remember { Animatable(initialValue) }
    LaunchedEffect(animatable, gate, initialValue, targetValue, durationMillis) {
        snapshotFlow { gate.value }.collectLatest { running ->
            if (!running) return@collectLatest
            val span = targetValue - initialValue
            while (true) {
                val remaining = if (span == 0f) 1f else ((targetValue - animatable.value) / span).coerceIn(0f, 1f)
                if (remaining > 0f) {
                    animatable.animateTo(
                        targetValue = targetValue,
                        animationSpec = tween(
                            durationMillis = (durationMillis * remaining).roundToInt().coerceAtLeast(1),
                            easing = LinearEasing
                        )
                    )
                }
                animatable.snapTo(initialValue)
            }
        }
    }
    return animatable.asState()
}

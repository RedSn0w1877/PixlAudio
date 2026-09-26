package com.theveloper.pixelplay.presentation.components.scoped

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal data class CastSheetState(
    val showCastSheet: Boolean,
    /** Animates for 220 ms on open/close: read `.value` in layout/draw or a derivedStateOf only. */
    val castSheetOpenFractionState: State<Float>,
    val openCastSheet: () -> Unit,
    val dismissCastSheet: () -> Unit,
    val onCastExpansionChanged: (Float) -> Unit
)

@Composable
internal fun rememberCastSheetState(): CastSheetState {
    var showCastSheet by remember { mutableStateOf(false) }
    val castSheetOpenFractionState = animateFloatAsState(
        targetValue = if (showCastSheet) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "castSheetOpenFraction"
    )

    val openCastSheet = remember {
        { showCastSheet = true }
    }
    val dismissCastSheet = remember {
        {
            showCastSheet = false
        }
    }
    val onCastExpansionChanged = remember {
        { _: Float -> Unit }
    }

    return CastSheetState(
        showCastSheet = showCastSheet,
        castSheetOpenFractionState = castSheetOpenFractionState,
        openCastSheet = openCastSheet,
        dismissCastSheet = dismissCastSheet,
        onCastExpansionChanged = onCastExpansionChanged
    )
}

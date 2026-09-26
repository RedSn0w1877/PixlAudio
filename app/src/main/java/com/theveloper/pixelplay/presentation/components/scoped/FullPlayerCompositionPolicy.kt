package com.theveloper.pixelplay.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.theveloper.pixelplay.presentation.viewmodel.PlayerSheetState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal data class FullPlayerCompositionPolicy(
    val shouldRenderFullPlayer: Boolean
)

/**
 * Decides whether the full-player composable tree should be in composition.
 *
 * Accepts [Animatable] instead of a raw Float so that the expansion fraction is
 * read inside [derivedStateOf] / [snapshotFlow] — never as a `remember` key or
 * `LaunchedEffect` key. This prevents per-frame recomposition of the caller during
 * sheet drag gestures.
 *
 * Keyed on *having* a song, not on which song: once the hidden full player is warm it stays
 * composed across skips and only recomposes. (Keying on the song id tore the whole tree down on
 * every skip from the mini player and rebuilt it from scratch 650 ms later.)
 */
@Composable
internal fun rememberFullPlayerCompositionPolicy(
    hasCurrentSong: Boolean,
    currentSheetState: PlayerSheetState,
    expansionFraction: Animatable<Float, AnimationVector1D>,
    collapsedWarmDelayMs: Long = 650L
): FullPlayerCompositionPolicy {
    var keepFullPlayerComposed by remember(hasCurrentSong) { mutableStateOf(false) }

    LaunchedEffect(hasCurrentSong, currentSheetState) {
        if (!hasCurrentSong) {
            keepFullPlayerComposed = false
            return@LaunchedEffect
        }

        if (keepFullPlayerComposed) return@LaunchedEffect
        if (currentSheetState == PlayerSheetState.EXPANDED) {
            keepFullPlayerComposed = true
        } else {
            // Warm the hidden full-player tree after the collapsed state settles.
            // This moves the expensive first composition out of the expand animation.
            delay(collapsedWarmDelayMs)
            keepFullPlayerComposed = true
        }
    }

    // Monitor expansion fraction via snapshotFlow instead of using it as a
    // LaunchedEffect key. Once either condition is satisfied
    // (expansion crossed 0.12f, or the warm-delay coroutine flipped
    // keepFullPlayerComposed itself) we can exit. The previous `collect`
    // never terminated — it kept reading expansionFraction on every frame
    // for the rest of the song's lifetime, even though there was nothing
    // left to do once keepFullPlayerComposed was true.
    LaunchedEffect(hasCurrentSong) {
        if (!hasCurrentSong) return@LaunchedEffect
        snapshotFlow {
            keepFullPlayerComposed || expansionFraction.value > 0.12f
        }.first { it }
        if (!keepFullPlayerComposed) keepFullPlayerComposed = true
    }

    // Read expansion fraction inside derivedStateOf so that changes only trigger
    // recomposition of direct consumers when the Boolean result flips.
    val shouldRenderFullPlayer by remember(hasCurrentSong, currentSheetState) {
        derivedStateOf {
            hasCurrentSong && (
                currentSheetState == PlayerSheetState.EXPANDED ||
                    expansionFraction.value > 0.015f ||
                    keepFullPlayerComposed
                )
        }
    }

    return FullPlayerCompositionPolicy(
        shouldRenderFullPlayer = shouldRenderFullPlayer
    )
}

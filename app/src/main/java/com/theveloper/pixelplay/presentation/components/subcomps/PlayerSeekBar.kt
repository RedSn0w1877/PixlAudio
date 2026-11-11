package com.theveloper.pixelplay.presentation.components.subcomps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.presentation.components.WavySliderExpressive
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Longest the bar stays on a just-sought position while real playback has not caught up. */
private const val SEEK_HOLD_MAX_MS = 5_000L

/** Playback within this fraction of the sought target counts as "caught up" and releases the hold. */
private const val SEEK_CATCH_UP_FRACTION = 0.04f

@Composable
fun PlayerSeekBar(
    backgroundColor: Color,
    onBackgroundColor: Color,
    primaryColor: Color,
    /**
     * Read only inside a snapshotFlow / the draw phase, never in composition: the position ticks
     * several times a second and the bar must not recompose for it (only the slider's draw follows).
     */
    currentPosition: () -> Long,
    totalDuration: Long,
    onSeek: (Long) -> Unit,
    onSeekPreview: ((Long?) -> Unit)? = null,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 8.dp,
    inactiveTrackColor: Color = primaryColor.copy(alpha = 0.2f),
    /** True when [currentPosition] already moves every frame, so the slider draws it directly. */
    frameAccurate: Boolean = false
) {
    val hapticFeedback = LocalHapticFeedback.current
    val positionProvider by rememberUpdatedState(currentPosition)
    val durationState = rememberUpdatedState(totalDuration)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestOnSeekPreview by rememberUpdatedState(onSeekPreview)
    val progressFractionState = remember {
        derivedStateOf {
            val duration = durationState.value
            if (duration > 0) {
                (positionProvider().toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }

    // What the bar shows is decided in one place and read in draw only: the finger while
    // dragging, the just-sought target until playback catches up, otherwise the live position.
    // There is no "copy the live position into a second state" step that can get stuck: if the
    // drag state were ever left behind, the live position would still be one flag away.
    var isUserSeeking by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var heldSeekFraction by remember { mutableFloatStateOf(-1f) }
    var heldSeekToken by remember { mutableIntStateOf(0) }
    val shownFractionState = remember {
        derivedStateOf {
            when {
                isUserSeeking -> dragFraction
                heldSeekFraction >= 0f -> heldSeekFraction
                else -> progressFractionState.value
            }
        }
    }
    val lastHapticStep = remember { intArrayOf(-1) }

    // Releases the held target once playback is within a few percent of it, or after a hard cap
    // (a seek the player dropped must not pin the bar). The cap is a timeout, so it fires even if
    // the position never ticks again.
    LaunchedEffect(heldSeekToken) {
        if (heldSeekToken == 0) return@LaunchedEffect
        withTimeoutOrNull(SEEK_HOLD_MAX_MS) {
            snapshotFlow { progressFractionState.value }
                .first { abs(it - heldSeekFraction) < SEEK_CATCH_UP_FRACTION }
        }
        heldSeekFraction = -1f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = shadowElevation, // nivel de sombra
                shape = CircleShape,       // la misma forma de clip
                clip = false               // importante: NO recortar la sombra
            )
            .clip(CircleShape)
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WavySliderExpressive(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 0.dp),
            value = { shownFractionState.value },
            onValueChange = { newFraction ->
                isUserSeeking = true
                dragFraction = newFraction
                latestOnSeekPreview?.invoke((newFraction * durationState.value).roundToLong())
                val quantized = (newFraction.coerceIn(0f, 1f) * 20f).toInt()
                if (quantized != lastHapticStep[0]) {
                    lastHapticStep[0] = quantized
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
            onValueCommit = { finalFraction ->
                latestOnSeek((finalFraction * durationState.value).roundToLong())
                // Hold the target first, then let go of the finger: the bar never flashes the old
                // live position between the two. (The accessibility set-progress action commits
                // without a touch gesture, so it ends the drag here too.)
                heldSeekFraction = finalFraction
                heldSeekToken++
                isUserSeeking = false
            },
            // Runs after every gesture, including one that was cancelled before it could commit
            // (a parent took the touch, or the bar left composition mid-drag). Without it the
            // drag state and the lyrics' scrub preview stayed set for good, and the bar froze
            // where the finger had last been while the song played on.
            onInteractionEnd = {
                isUserSeeking = false
                latestOnSeekPreview?.invoke(null)
            },
            frameAccurateValue = frameAccurate,
            strokeWidth = 5.dp, // Was trackHeight
            thumbRadius = 8.dp,
            activeTrackColor = primaryColor,
            inactiveTrackColor = inactiveTrackColor,
            thumbColor = primaryColor,
            wavelength = 30.dp, // Was waveLength
            isPlaying = isPlaying,
            semanticsLabel = "Playback position"
        )
    }
}

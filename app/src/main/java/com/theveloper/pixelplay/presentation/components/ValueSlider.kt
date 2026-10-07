package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * A Material 3 [Slider] driven by a plain [value], with a custom [thumb] and/or [track].
 *
 * Material3 1.5.0-alpha29 removed the `Slider(value, onValueChange, …, thumb, track, valueRange)`
 * overload that these screens used; the only way left to customise the thumb or the track is the
 * [SliderState]-based `Slider(state, onValueChange, …)`. This puts the removed overload back on top
 * of that API, doing exactly what the library's own value-based `Slider` does in alpha29 (read from
 * the alpha29 bytecode, since no sources are published for it):
 *  - one [SliderState] per `steps`/`valueRange`, created with the current [value];
 *  - [value] pushed into that state on every composition, so the caller stays the single source
 *    of truth;
 *  - drags reported through [onValueChange]. With a non-null `onValueChange` the state never moves
 *    on its own, so nothing changes unless the caller feeds the new value back in, as before.
 *
 * Don't swap this for `rememberSliderState(...)` (what the alpha29 deprecation message suggests):
 * that one is `rememberSaveable` and never picks up a new [value] after the first composition, so
 * a slider whose value comes from a ViewModel would freeze.
 *
 * The [thumb] and [track] defaults are the library's own defaults for `Slider(state, …)`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValueSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    thumb: @Composable (SliderState) -> Unit = {
        SliderDefaults.Thumb(
            interactionSource = interactionSource,
            colors = colors,
            enabled = enabled
        )
    },
    track: @Composable (SliderState) -> Unit = { sliderState ->
        SliderDefaults.Track(
            sliderState = sliderState,
            colors = colors,
            enabled = enabled
        )
    },
) {
    val state = remember(steps, valueRange) { SliderState(value, steps, valueRange) }
    state.value = value
    Slider(
        state = state,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        thumb = thumb,
        track = track,
    )
}

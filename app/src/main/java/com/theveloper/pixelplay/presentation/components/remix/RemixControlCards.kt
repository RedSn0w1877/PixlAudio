package com.theveloper.pixelplay.presentation.components.remix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.pow
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@Composable
private fun RemixCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(22.dp, 60),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

/**
 * Tape speed. Pitch follows rate, exactly like a turntable — that is what "tape" means, and it is
 * why there is no separate pitch control: keeping pitch fixed while changing speed needs a phase
 * vocoder that costs more than the entire rest of the engine and smears drums while doing it.
 */
@Composable
fun TapeCard(rate: Float, onRateChanged: (Float) -> Unit, modifier: Modifier = Modifier) {
    RemixCard(
        title = "Tape",
        subtitle = "%.2f×  —  slower is deeper, faster is brighter".format(rate),
        modifier = modifier,
    ) {
        Slider(
            value = rate,
            onValueChange = onRateChanged,
            valueRange = 0.6f..1.4f,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0.75f to "Screwed", 1f to "Normal", 1.25f to "Nightcore").forEach { (value, label) ->
                FilterChip(
                    selected = kotlin.math.abs(rate - value) < 0.01f,
                    onClick = { onRateChanged(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
fun FilterCard(
    cutoffHz: Float,
    resonance: Float,
    mode: String,
    bits: Int,
    decim: Int,
    onFilterChanged: (cutoffHz: Float, resonance: Float, mode: String) -> Unit,
    onLoFiChanged: (bits: Int, decim: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    RemixCard(
        title = "Filter",
        subtitle = "${cutoffHz.toInt()} Hz  ·  resonance %.1f".format(resonance),
        modifier = modifier,
    ) {
        // Exponential mapping: a linear cutoff slider spends most of its travel in the top octave,
        // where nothing interesting happens, and crams the useful sweep into the last few pixels.
        Slider(
            value = cutoffToSlider(cutoffHz),
            onValueChange = { onFilterChanged(sliderToCutoff(it), resonance, mode) },
        )
        Slider(
            value = ((resonance - 0.3f) / 11.7f).coerceIn(0f, 1f),
            onValueChange = { onFilterChanged(cutoffHz, 0.3f + it * 11.7f, mode) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("lp" to "Low", "bp" to "Band", "hp" to "High").forEach { (value, label) ->
                FilterChip(
                    selected = mode == value,
                    onClick = { onFilterChanged(cutoffHz, resonance, value) },
                    label = { Text(label) },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = bits < 16,
                onClick = { onLoFiChanged(if (bits < 16) 16 else 8, decim) },
                label = { Text("Crush") },
            )
            FilterChip(
                selected = decim > 1,
                onClick = { onLoFiChanged(bits, if (decim > 1) 1 else 4) },
                label = { Text("Downsample") },
            )
        }
    }
}

@Composable
fun SpaceCard(
    reverbMix: Float,
    rt60: Float,
    decayMs: Int,
    onReverbChanged: (mix: Float, rt60: Float) -> Unit,
    onDecayChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    RemixCard(
        title = "Room",
        subtitle = "%.0f%% wet  ·  %.1f s tail".format(reverbMix * 100f, rt60),
        modifier = modifier,
    ) {
        Slider(value = reverbMix, onValueChange = { onReverbChanged(it, rt60) })
        Slider(
            value = ((rt60 - 0.3f) / 5.7f).coerceIn(0f, 1f),
            onValueChange = { onReverbChanged(reverbMix, 0.3f + it * 5.7f) },
        )
        Text(
            "Loop fade: ${decayMs} ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = decayMs / 300f,
            onValueChange = { onDecayChanged((it * 300f).toInt()) },
        )
    }
}

@Composable
fun ListeningCard(
    poseSourceId: String,
    headTrackerAvailable: Boolean,
    onPoseSourceChanged: (String) -> Unit,
    onRecentre: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RemixCard(
        title = "Looking around",
        subtitle = if (headTrackerAvailable) {
            "Your headphones report head movement."
        } else {
            "Turn the phone to look around the mix. Headphone head tracking isn't available to apps yet."
        },
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = poseSourceId == "device",
                onClick = { onPoseSourceChanged("device") },
                label = { Text("Phone") },
            )
            FilterChip(
                selected = poseSourceId == "manual",
                onClick = { onPoseSourceChanged("manual") },
                label = { Text("Fixed") },
            )
            if (headTrackerAvailable) {
                FilterChip(
                    selected = poseSourceId == "headset",
                    onClick = { onPoseSourceChanged("headset") },
                    label = { Text("Headphones") },
                )
            }
            FilterChip(selected = false, onClick = onRecentre, label = { Text("Re-centre") })
        }
    }
}

private const val MIN_CUTOFF = 200f
private const val MAX_CUTOFF = 18_000f

private fun cutoffToSlider(hz: Float): Float =
    (ln(hz.coerceIn(MIN_CUTOFF, MAX_CUTOFF) / MIN_CUTOFF) / ln(MAX_CUTOFF / MIN_CUTOFF)).coerceIn(0f, 1f)

private fun sliderToCutoff(value: Float): Float =
    MIN_CUTOFF * (MAX_CUTOFF / MIN_CUTOFF).pow(value.coerceIn(0f, 1f))

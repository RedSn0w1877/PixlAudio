package com.theveloper.pixelplay.presentation.components.remix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The loop region, drawn as peaks, with the playhead sweeping across it.
 *
 * It shows the **region**, not the whole song, because the region is what is resident in memory
 * and what the engine actually reads. Choosing where that window sits is the row of length chips
 * plus the nudge buttons — a full-song timeline with pinch-to-set-loop needs whole-file peak data
 * and belongs with the offline render work, not here.
 */
@Composable
fun RemixWaveformScrubber(
    peaks: List<Float>,
    playheadFraction: Float,
    loopStartMs: Int,
    loopLengthMs: Int,
    songDurationMs: Int,
    onLoopChanged: (startMs: Int, lengthMs: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(78.dp),
        ) {
            if (peaks.isEmpty()) return@Canvas
            val barWidth = size.width / peaks.size
            val loudest = max(peaks.max(), 0.0001f)
            peaks.forEachIndexed { index, peak ->
                val normalized = (peak / loudest).coerceIn(0f, 1f)
                val barHeight = (normalized * size.height * 0.92f).coerceAtLeast(2f)
                val x = index * barWidth
                val played = (index + 0.5f) / peaks.size <= playheadFraction
                drawRect(
                    color = if (played) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.45f),
                    topLeft = Offset(x, (size.height - barHeight) / 2f),
                    size = androidx.compose.ui.geometry.Size(barWidth * 0.72f, barHeight),
                )
            }
            val playheadX = (playheadFraction.coerceIn(0f, 1f) * size.width)
            drawLine(
                color = colors.primary,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, size.height),
                strokeWidth = 3f,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatSeconds(loopStartMs),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
            listOf(2_000, 4_000, 8_000, 16_000).forEach { length ->
                FilterChip(
                    selected = loopLengthMs == length,
                    onClick = { onLoopChanged(loopStartMs, length) },
                    label = { Text("${length / 1000}s") },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = false,
                onClick = {
                    onLoopChanged((loopStartMs - loopLengthMs).coerceAtLeast(0), loopLengthMs)
                },
                label = { Text("◀ back") },
            )
            FilterChip(
                selected = false,
                onClick = {
                    val maxStart = (songDurationMs - loopLengthMs).coerceAtLeast(0)
                    onLoopChanged((loopStartMs + loopLengthMs).coerceAtMost(maxStart), loopLengthMs)
                },
                label = { Text("forward ▶") },
            )
        }
    }
}

private fun formatSeconds(ms: Int): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

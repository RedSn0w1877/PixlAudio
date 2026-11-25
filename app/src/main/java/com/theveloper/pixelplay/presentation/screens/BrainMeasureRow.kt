package com.theveloper.pixelplay.presentation.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.brain.TrackDna
import com.theveloper.pixelplay.data.brain.AudioMeasurer
import com.theveloper.pixelplay.presentation.viewmodel.BrainSessionStateHolder
import java.util.Locale

/**
 * Developer mode › Maintenance: what stage 5 measured from the song that is playing (docs/brain-spec.md 3.3). One row; the subtitle is
 * the tempo with its confidence, the integrated loudness, whether the tempo is the one the flow ordering uses, and how long it took; or
 * why there is nothing yet. Recomposes only when the measurer's status changes (a few times per song).
 */
@Composable
internal fun BrainMeasureRow(brainSession: BrainSessionStateHolder) {
    val status by brainSession.audioMeasure.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val subtitle = describe(status, context::getString)
    SettingsItem(
        title = stringResource(R.string.brain_measure_title),
        subtitle = subtitle,
        leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.secondary) },
        onClick = {},
    )
}

/** The subtitle for a status; plain text so the row stays one `Text`. */
internal fun describe(status: AudioMeasurer.Status?, str: (Int) -> String): String {
    val s = status ?: return str(R.string.brain_measure_none)
    return when (s.state) {
        AudioMeasurer.STATE_WAITING -> str(R.string.brain_measure_waiting)
        AudioMeasurer.STATE_MEASURING -> str(if (s.fromFile) R.string.brain_measure_measuring_file else R.string.brain_measure_measuring)
        AudioMeasurer.STATE_WAITING_FOR_WIFI -> str(R.string.brain_measure_wifi)
        AudioMeasurer.STATE_OFF -> str(R.string.brain_measure_off)
        AudioMeasurer.STATE_UNKNOWN_TRACK -> str(R.string.brain_measure_unknown)
        AudioMeasurer.STATE_FAILED -> str(R.string.brain_measure_failed)
        AudioMeasurer.STATE_GAVE_UP -> str(R.string.brain_measure_gave_up)
        else -> {
            val r = s.result ?: return str(R.string.brain_measure_failed)
            val parts = ArrayList<String>(4)
            parts.add(
                if (r.bpmX10 > 0 && r.tempoConf > 0) {
                    String.format(str(R.string.brain_measure_tempo), String.format(Locale.US, "%.1f", r.bpmX10 / 10.0), r.tempoConf / 10)
                } else str(R.string.brain_measure_tempo_none)
            )
            parts.add(
                if (r.loudnessKnown) String.format(str(R.string.brain_measure_loudness), String.format(Locale.US, "%.1f", r.lufsX10 / 10.0))
                else str(R.string.brain_measure_loudness_none)
            )
            parts.add(str(if (s.keptTempoSource == TrackDna.TEMPO_ON_DEVICE) R.string.brain_measure_used else R.string.brain_measure_not_used))
            if (s.tookMs > 0) parts.add(String.format(str(R.string.brain_measure_took), String.format(Locale.US, "%.1f", s.tookMs / 1000.0)))
            parts.joinToString(" · ")
        }
    }
}

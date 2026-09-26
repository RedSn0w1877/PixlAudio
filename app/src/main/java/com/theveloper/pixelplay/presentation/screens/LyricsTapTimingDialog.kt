package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.presentation.viewmodel.SettingsViewModel
import com.theveloper.pixelplay.ui.glass.GlassAlertDialog
import com.theveloper.pixelplay.ui.glass.GlassSwitch

/** Settings -> Lyrics -> "Tap timing": the reaction time taken off every tap, plus haptics. */
@Composable
internal fun LyricsTapTimingDialog(settingsViewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val offsetMs by settingsViewModel.lyricsTapOffsetMs.collectAsStateWithLifecycle()
    val haptics by settingsViewModel.lyricsSyncHaptics.collectAsStateWithLifecycle()
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_lyrics_tap_timing)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.settings_lyrics_tap_timing_body))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalIconButton(
                        onClick = { settingsViewModel.adjustLyricsTapOffset(-STEP_MS) },
                        enabled = offsetMs > 0
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.lyrics_sync_decrease))
                    }
                    Text(
                        text = stringResource(R.string.settings_lyrics_tap_timing_value, offsetMs),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(
                        onClick = { settingsViewModel.adjustLyricsTapOffset(STEP_MS) },
                        enabled = offsetMs < UserPreferencesRepository.MAX_LYRICS_TAP_OFFSET_MS
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.lyrics_sync_increase))
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_lyrics_tap_haptics),
                        modifier = Modifier.weight(1f)
                    )
                    GlassSwitch(checked = haptics, onCheckedChange = settingsViewModel::setLyricsSyncHaptics)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        dismissButton = {
            TextButton(onClick = settingsViewModel::resetLyricsTapOffset) { Text(stringResource(R.string.common_reset)) }
        }
    )
}

private const val STEP_MS = 10

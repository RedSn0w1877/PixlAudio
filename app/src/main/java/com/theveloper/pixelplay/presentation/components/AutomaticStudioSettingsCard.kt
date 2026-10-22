package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.model.LocalDeveloperMode
import com.theveloper.pixelplay.presentation.viewmodel.AutomaticStudioSettingsViewModel

/**
 * Settings › Sing › "Ready when you play". Everyone sees the instrumentals switch; automatic
 * lyric sync, the live queue status and the queue tools are Developer mode only (hiding them
 * leaves their values and the background work as they are).
 */
@Composable
fun AutomaticStudioSettingsCard(viewModel: AutomaticStudioSettingsViewModel = hiltViewModel()) {
    val developerMode = LocalDeveloperMode.current
    val lyrics by viewModel.lyricsEnabled.collectAsStateWithLifecycle()
    val instrumentals by viewModel.instrumentalsEnabled.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    var showDetails by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ready when you play", style = MaterialTheme.typography.headlineSmall)
            Text("Prepare instrumentals quietly while PixlAudio is open.", style = MaterialTheme.typography.bodyMedium)
            AutomaticStudioToggle("Automatic instrumentals", "Makes instrumentals in the background while the app is open, one song at a time.", instrumentals, viewModel::setInstrumentals)
            Text("You can also make one yourself from a song's menu.", style = MaterialTheme.typography.bodySmall)
            if (developerMode) {
                Text(
                    text = stringResource(R.string.settings_section_advanced),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                AutomaticStudioToggle("Automatic lyric sync", "Looks for word timings first.", lyrics, viewModel::setLyrics)
                // El estado en vivo se queda siempre a la vista: esta función no emite notificaciones
                // a propósito, así que si además hay que desplegar un panel para saber si está
                // haciendo algo, no hay forma de saberlo en absoluto.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                FilledTonalButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (showDetails) "Hide queue details" else "Queue and diagnostics")
                }
                if (showDetails) {
                    Text("Runs quietly in the background when the phone is idle. It pauses during playback, low battery, heat, storage pressure, or manual processing; longer tracks remain available through the manual controls.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = viewModel::checkNow, enabled = lyrics || instrumentals, modifier = Modifier.fillMaxWidth()) {
                        Text("Check queue now")
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomaticStudioToggle(title: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChanged).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

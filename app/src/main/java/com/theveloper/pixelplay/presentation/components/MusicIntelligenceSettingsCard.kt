package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.viewmodel.MusicIntelligenceViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicIntelligenceSettingsCard(viewModel: MusicIntelligenceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }
    var exploration by remember(state.exploration) { mutableStateOf(state.exploration) }
    val clipboard = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.music_intelligence_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.music_intelligence_description), style = MaterialTheme.typography.bodyMedium)
            IntelligenceToggle(stringResource(R.string.music_intelligence_learning), state.learningEnabled, viewModel::setLearning)
            IntelligenceToggle(stringResource(R.string.music_intelligence_discovery), state.discoveryEnabled, viewModel::setDiscovery)
            Text(stringResource(R.string.music_intelligence_exploration, (exploration * 100).toInt()), style = MaterialTheme.typography.titleSmall)
            Slider(
                value = exploration, onValueChange = { exploration = it }, valueRange = 0f..0.6f,
                steps = 11, onValueChangeFinished = { viewModel.setExploration(exploration) }
            )
            Text(
                stringResource(R.string.music_intelligence_learning_count, state.learnedSongs, state.completions, state.skips),
                style = MaterialTheme.typography.bodySmall
            )
            FilledTonalButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (expanded) R.string.music_intelligence_hide_tools else R.string.music_intelligence_tools))
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.music_intelligence_tools_description), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = viewModel::preview, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        if (busy) LoadingIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.music_intelligence_preview))
                    }
                    OutlinedButton(onClick = viewModel::refreshDiscovery, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.music_intelligence_refresh))
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(state.lastReport, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(state.lastReport)) }) {
                        Text(stringResource(R.string.music_intelligence_copy_report))
                    }
                    Text(stringResource(R.string.music_intelligence_model_title), style = MaterialTheme.typography.titleMedium)
                    Text(modelStatus.backend, style = MaterialTheme.typography.titleSmall)
                    Text(modelStatus.detail, style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.music_intelligence_model_timing, modelStatus.lastInferenceMs, modelStatus.inferenceCount),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = viewModel::benchmarkInstrumentalModel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.music_intelligence_model_benchmark))
                    }
                    TextButton(onClick = { resetDialog = true }) { Text(stringResource(R.string.music_intelligence_reset)) }
                }
            }
        }
    }
    if (resetDialog) {
        AlertDialog(
            onDismissRequest = { resetDialog = false },
            title = { Text(stringResource(R.string.music_intelligence_reset)) },
            text = { Text(stringResource(R.string.music_intelligence_reset_description)) },
            confirmButton = { TextButton(onClick = { viewModel.resetLearning(); resetDialog = false }) { Text(stringResource(R.string.music_intelligence_reset_confirm)) } },
            dismissButton = { TextButton(onClick = { resetDialog = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

@Composable
private fun IntelligenceToggle(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked = checked, onCheckedChange = onChanged, modifier = Modifier.semantics { contentDescription = title })
    }
}

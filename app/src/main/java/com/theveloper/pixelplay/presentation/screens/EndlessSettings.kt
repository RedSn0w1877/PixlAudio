package com.theveloper.pixelplay.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.service.player.HostVoiceLog
import com.theveloper.pixelplay.data.service.player.HostVoiceLogEntry
import com.theveloper.pixelplay.presentation.components.AdaptiveAlertDialog
import com.theveloper.pixelplay.presentation.model.LocalDeveloperMode
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.HostVoiceStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * The "Endless Listening" settings screen: Host notes (on), Spoken host notes (off), Test the voice, How it works; and, in Developer mode only,
 * the host voice log (the last 20 decisions, for calibrating the host on a real phone).
 */
@Composable
internal fun EndlessSettingsSection(navController: NavController, playerViewModel: PlayerViewModel) {
    val endless = playerViewModel.brainSession.endless
    val voice = endless.voice
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val developerMode = LocalDeveloperMode.current
    val hostNotes by endless.hostNotesEnabled.collectAsStateWithLifecycle()
    val spoken by voice.spokenNotes.collectAsStateWithLifecycle()
    var testing by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    SettingsSubsection(title = stringResource(R.string.endless_settings_title), addBottomSpace = developerMode) {
        SwitchSettingItem(
            title = stringResource(R.string.endless_settings_host_notes),
            subtitle = stringResource(R.string.endless_settings_host_notes_summary),
            checked = hostNotes,
            onCheckedChange = endless::setHostNotes,
            leadingIcon = { Icon(Icons.Rounded.Notes, null, tint = MaterialTheme.colorScheme.secondary) }
        )
        SwitchSettingItem(
            title = stringResource(R.string.endless_settings_spoken),
            subtitle = stringResource(R.string.endless_settings_spoken_summary),
            checked = spoken,
            onCheckedChange = voice::setSpokenNotes,
            enabled = hostNotes,
            leadingIcon = { Icon(Icons.Rounded.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.secondary) }
        )
        SettingsItem(
            title = stringResource(R.string.endless_settings_voice_test),
            subtitle = stringResource(if (testing) R.string.endless_settings_voice_test_running else R.string.endless_settings_voice_test_summary),
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = MaterialTheme.colorScheme.secondary) },
            onClick = {
                if (!testing) {
                    testing = true
                    scope.launch {
                        val result = voice.testVoice()
                        testing = false
                        val message = when (result) {
                            HostVoiceStateHolder.TestResult.SPOKE -> null
                            HostVoiceStateHolder.TestResult.NO_VOICE -> R.string.endless_voice_test_none
                            HostVoiceStateHolder.TestResult.BUSY -> R.string.endless_voice_test_busy
                            HostVoiceStateHolder.TestResult.IN_CALL -> R.string.endless_voice_test_call
                            HostVoiceStateHolder.TestResult.ERROR -> R.string.endless_voice_test_error
                        }
                        if (message != null) Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        SettingsItem(
            title = stringResource(R.string.endless_settings_how),
            subtitle = stringResource(R.string.endless_settings_how_summary),
            leadingIcon = { Icon(Icons.Rounded.TouchApp, null, tint = MaterialTheme.colorScheme.secondary) },
            onClick = { navController.navigateSafely(Screen.EndlessIntro.route) }
        )
    }
    if (developerMode) {
        SettingsSubsection(title = stringResource(R.string.endless_settings_advanced)) {
            SettingsItem(
                title = stringResource(R.string.endless_settings_voice_log),
                subtitle = stringResource(R.string.endless_settings_voice_log_summary),
                leadingIcon = { Icon(Icons.Rounded.BugReport, null, tint = MaterialTheme.colorScheme.secondary) },
                onClick = { showLog = true }
            )
        }
    }
    if (showLog) HostVoiceLogDialog(voice = voice, onDismiss = { showLog = false })
}

/** The last 20 decisions of the spoken host, newest first: what was said or skipped, and why. Read when opened; nothing is kept on disk. */
@Composable
private fun HostVoiceLogDialog(voice: HostVoiceStateHolder, onDismiss: () -> Unit) {
    val log by voice.log.collectAsStateWithLifecycle()
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.endless_settings_voice_log)) },
        text = {
            if (log.isEmpty()) {
                Text(stringResource(R.string.endless_voice_log_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(log) { entry -> LogRow(entry, timeFormat) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.endless_voice_log_close)) } }
    )
}

@Composable
private fun LogRow(entry: HostVoiceLogEntry, timeFormat: DateFormat) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(timeFormat.format(Date(entry.atMs)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(HostVoiceLog.describe(entry), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

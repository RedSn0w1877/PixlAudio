package com.theveloper.pixelplay.presentation.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.history.HistoryImportState
import com.theveloper.pixelplay.data.history.ListeningHistoryStatus
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.ListeningHistoryViewModel
import java.text.NumberFormat

private const val SPOTIFY_PRIVACY_URL = "https://www.spotify.com/account/privacy/"
private const val TAKEOUT_YOUTUBE_URL = "https://takeout.google.com/settings/takeout/custom/youtube"

/** ZIPs and JSON; some file pickers label JSON as plain text or octet-stream. */
private val HISTORY_MIME_TYPES = arrayOf(
    "application/zip", "application/x-zip-compressed", "application/json", "application/octet-stream", "text/plain"
)

private fun formatCount(value: Int): String = NumberFormat.getIntegerInstance().format(value)

@Composable
private fun statusLine(status: ListeningHistoryStatus): String {
    val plays = formatCount(status.totalPlays)
    return when {
        status.syncing -> stringResource(R.string.listening_history_status_syncing, plays)
        status.totalPlays == 0 && status.lastSyncedMs == 0L -> stringResource(R.string.listening_history_status_empty)
        status.lastSyncedMs == 0L -> stringResource(R.string.listening_history_status_never, plays)
        else -> stringResource(
            R.string.listening_history_status_synced,
            DateUtils.getRelativeTimeSpanString(
                status.lastSyncedMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString(),
            plays
        )
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.listening_history_no_browser), Toast.LENGTH_SHORT).show()
    }
}

/**
 * Settings › Library & storage: the listening-history rows (status, the two automatic-sync switches, import,
 * clear). Its own small subsection so the 3000-line category screen only gains one item.
 */
@Composable
fun SettingsListeningHistorySection(
    navController: NavController,
    viewModel: ListeningHistoryViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tint = MaterialTheme.colorScheme.secondary
    var confirmClear by remember { mutableStateOf(false) }
    val clearedText = stringResource(R.string.listening_history_cleared)

    Column {
        SettingsSubsection(title = stringResource(R.string.listening_history_title)) {
            SettingsItem(
                title = stringResource(R.string.listening_history_title),
                subtitle = statusLine(status),
                leadingIcon = { Icon(Icons.Rounded.History, null, tint = tint) },
                trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                onClick = { navController.navigateSafely(Screen.ListeningHistory.route) }
            )
            SwitchSettingItem(
                title = stringResource(R.string.listening_history_spotify_title),
                subtitle = when {
                    !status.spotifySignedIn -> stringResource(R.string.listening_history_spotify_signed_out)
                    status.spotifyNeedsReconnect -> stringResource(R.string.listening_history_spotify_reconnect)
                    else -> stringResource(R.string.listening_history_spotify_subtitle)
                },
                checked = status.spotifyEnabled && status.spotifySignedIn,
                onCheckedChange = viewModel::setSpotifyEnabled,
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = tint) },
                enabled = status.spotifySignedIn
            )
            SwitchSettingItem(
                title = stringResource(R.string.listening_history_youtube_title),
                subtitle = if (status.youTubeSignedIn) stringResource(R.string.listening_history_youtube_subtitle)
                else stringResource(R.string.listening_history_youtube_signed_out),
                checked = status.youTubeEnabled && status.youTubeSignedIn,
                onCheckedChange = viewModel::setYouTubeEnabled,
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = tint) },
                enabled = status.youTubeSignedIn
            )
            SettingsItem(
                title = stringResource(R.string.listening_history_import_title),
                subtitle = stringResource(R.string.listening_history_import_subtitle),
                leadingIcon = { Icon(Icons.Rounded.UploadFile, null, tint = tint) },
                trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                onClick = { navController.navigateSafely(Screen.ListeningHistory.route) }
            )
            if (status.totalPlays > 0) {
                SettingsItem(
                    title = stringResource(R.string.listening_history_clear_title),
                    subtitle = stringResource(R.string.listening_history_clear_subtitle),
                    leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = tint) },
                    onClick = { confirmClear = true }
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.listening_history_clear_dialog_title)) },
            text = { Text(stringResource(R.string.listening_history_clear_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearImported { Toast.makeText(context, clearedText, Toast.LENGTH_SHORT).show() }
                }) { Text(stringResource(R.string.listening_history_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * Settings › Library & storage › Listening history: what is imported, and plain step-by-step instructions with
 * buttons for getting the Spotify and Google Takeout exports, then a file picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningHistoryScreen(
    navController: NavController,
    viewModel: ListeningHistoryViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importFiles(uris)
    }
    val importing = status.importProgress is HistoryImportState.Running

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.listening_history_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.listening_history_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item(key = "summary") {
                SummaryCard(status = status, onSyncNow = viewModel::syncNow)
            }

            if (status.spotifyNeedsReconnect) {
                item(key = "reconnect") {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.listening_history_reconnect_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                stringResource(R.string.listening_history_reconnect_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Button(onClick = viewModel::reconnectSpotify) {
                                Text(stringResource(R.string.listening_history_reconnect_action))
                            }
                        }
                    }
                }
            }

            item(key = "import_state") {
                ImportStateCard(state = status.importProgress, onDismiss = viewModel::clearImportResult)
            }

            item(key = "spotify_steps") {
                StepsCard(
                    title = stringResource(R.string.listening_history_spotify_section),
                    steps = listOf(
                        stringResource(R.string.listening_history_spotify_step1),
                        stringResource(R.string.listening_history_spotify_step2),
                        stringResource(R.string.listening_history_spotify_step3),
                        stringResource(R.string.listening_history_spotify_step4),
                    ),
                    actionLabel = stringResource(R.string.listening_history_spotify_open),
                    onAction = { openUrl(context, SPOTIFY_PRIVACY_URL) }
                )
            }

            item(key = "youtube_steps") {
                StepsCard(
                    title = stringResource(R.string.listening_history_youtube_section),
                    steps = listOf(
                        stringResource(R.string.listening_history_youtube_step1),
                        stringResource(R.string.listening_history_youtube_step2),
                        stringResource(R.string.listening_history_youtube_step3),
                        stringResource(R.string.listening_history_youtube_step4),
                    ),
                    actionLabel = stringResource(R.string.listening_history_youtube_open),
                    onAction = { openUrl(context, TAKEOUT_YOUTUBE_URL) }
                )
            }

            item(key = "choose") {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { picker.launch(HISTORY_MIME_TYPES) },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.listening_history_choose_files))
                    }
                    Text(
                        text = stringResource(R.string.listening_history_dedupe_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(status: ListeningHistoryStatus, onSyncNow: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.listening_history_summary_plays, formatCount(status.totalPlays)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (status.totalPlays > 0) {
                Text(
                    text = stringResource(
                        R.string.listening_history_summary_detail,
                        formatCount(status.spotifyPlays),
                        formatCount(status.youTubePlays),
                        formatCount(status.artists)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = statusLine(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val canSync = (status.spotifySignedIn && status.spotifyEnabled) || (status.youTubeSignedIn && status.youTubeEnabled)
            if (canSync) {
                FilledTonalButton(onClick = onSyncNow, enabled = !status.syncing) {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.listening_history_sync_now))
                }
            }
        }
    }
}

@Composable
private fun ImportStateCard(state: HistoryImportState, onDismiss: () -> Unit) {
    when (state) {
        HistoryImportState.Idle -> Unit
        is HistoryImportState.Running -> Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.listening_history_importing, formatCount(state.playsRead)),
                    style = MaterialTheme.typography.titleMedium
                )
                state.currentFile?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        stringResource(R.string.listening_history_importing_file, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is HistoryImportState.Done -> Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val title = if (state.added > 0) {
                    stringResource(R.string.listening_history_import_done, formatCount(state.added), formatCount(state.artists))
                } else {
                    stringResource(R.string.listening_history_import_nothing_new, formatCount(state.duplicates))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (state.added > 0 && state.duplicates > 0) {
                    Text(
                        stringResource(R.string.listening_history_import_done_duplicates, formatCount(state.duplicates)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
            }
        }
        is HistoryImportState.Failed -> Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
            }
        }
    }
}

@Composable
private fun StepsCard(title: String, steps: List<String>, actionLabel: String, onAction: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp)
                    )
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(onClick = onAction) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(actionLabel)
            }
        }
    }
}

/** The one-time Home card: "Make your mixes smarter: import your Spotify history". */
@Composable
fun HomeListeningHistoryCard(
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.listening_history_home_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                stringResource(R.string.listening_history_home_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.listening_history_home_card_dismiss)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onImport) { Text(stringResource(R.string.listening_history_home_card_action)) }
            }
        }
    }
}

package com.theveloper.pixelplay.presentation.screens

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.diagnostics.DiagnosticsLog
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > About > Diagnostics: build identity, last process exits, the tail of the redacted log,
 * and Share / Clear. Reads from [DiagnosticsLog] on the IO dispatcher; nothing here touches the
 * network or any account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    val snapshot by produceState<DiagnosticsLog.Snapshot?>(null, refresh) {
        value = withContext(Dispatchers.IO) { runCatching { DiagnosticsLog.snapshot(context) }.getOrNull() }
    }

    val shareChooserTitle = stringResource(R.string.diagnostics_share_chooser_title)
    val shareSubject = stringResource(R.string.diagnostics_share_subject)
    val shareFailed = stringResource(R.string.diagnostics_share_failed)
    val clearedText = stringResource(R.string.diagnostics_cleared)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    PaddingValues(
                        top = contentPadding.calculateTopPadding(),
                        start = 16.dp,
                        end = 16.dp,
                    )
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = snapshot
            if (current == null) {
                Text(
                    text = stringResource(R.string.diagnostics_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val info = current.info
                InfoCard(stringResource(R.string.diagnostics_section_build)) {
                    InfoRow(stringResource(R.string.diagnostics_row_version), "${info.versionName} (${info.versionCode}) ${info.buildType}")
                    InfoRow(stringResource(R.string.diagnostics_row_commit), info.commit)
                    InfoRow(stringResource(R.string.diagnostics_row_built), info.buildDate)
                    InfoRow(
                        stringResource(R.string.diagnostics_row_spotify),
                        stringResource(
                            if (info.spotifySignInConfigured) R.string.diagnostics_spotify_configured
                            else R.string.diagnostics_spotify_missing
                        ),
                    )
                }
                InfoCard(stringResource(R.string.diagnostics_section_device)) {
                    Text(info.device, style = MaterialTheme.typography.bodyMedium)
                    Text(info.android, style = MaterialTheme.typography.bodyMedium)
                    Text(info.abi, style = MaterialTheme.typography.bodyMedium)
                }
                InfoCard(stringResource(R.string.diagnostics_section_exits)) {
                    if (current.exitReasons.isEmpty()) {
                        Text(
                            stringResource(R.string.diagnostics_no_exits),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        current.exitReasons.take(5).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                InfoCard(stringResource(R.string.diagnostics_section_log)) {
                    SelectionContainer {
                        Text(
                            text = current.recentLog.ifBlank { stringResource(R.string.diagnostics_log_empty) },
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.diagnostics_log_size,
                            Formatter.formatShortFileSize(context, current.sizeBytes)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = stringResource(R.string.diagnostics_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { DiagnosticsLog.writeShareFile(context) }
                            if (file == null) {
                                status = shareFailed
                                return@launch
                            }
                            status = null
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                            val chooser = ShareCompat.IntentBuilder(context)
                                .setType("text/plain")
                                .setStream(uri)
                                .setSubject(shareSubject)
                                .setChooserTitle(shareChooserTitle)
                                .createChooserIntent()
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(chooser) }
                        }
                    },
                ) { Text(stringResource(R.string.diagnostics_action_share), fontWeight = FontWeight.SemiBold) }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { DiagnosticsLog.clear(context) }
                            status = clearedText
                            refresh++
                        }
                    },
                ) { Text(stringResource(R.string.diagnostics_action_clear)) }
            }

            Spacer(modifier = Modifier.height(MiniPlayerHeight + contentPadding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

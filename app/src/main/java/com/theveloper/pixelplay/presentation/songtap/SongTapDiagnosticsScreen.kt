package com.theveloper.pixelplay.presentation.songtap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.songtap.ble.DiagSnapshot
import com.theveloper.pixelplay.presentation.viewmodel.SongTapViewModel
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SongTapTuning
import kotlinx.coroutines.delay

/**
 * Developer mode > Song Tap diagnostics: the live numbers behind the tap detector, so the owner can calibrate it on
 * the real phones. The phones touch; this screen shows the signal rise above its baseline, the jerk on either phone,
 * which part of the detector fired and the last 20 events, and "Copy log" puts all of it (and the thresholds)
 * on the clipboard. It arms Song Tap the same way the main screen does, so it works as one of the two phones.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SongTapDiagnosticsScreen(
    navController: NavController,
    viewModel: SongTapViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snap by viewModel.diag.snapshot.collectAsStateWithLifecycle()
    val events by viewModel.diag.events.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(Role.SEND) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(viewModel) { viewModel.attach() }
    DisposableEffect(viewModel) {
        viewModel.diag.setEnabled(true)
        onDispose { viewModel.diag.setEnabled(false) }
    }
    LifecycleResumeEffect(ui.allowed, viewModel) {
        if (ui.allowed) viewModel.arm(mode)
        onPauseOrDispose { viewModel.disarm() }
    }
    LaunchedEffect(copied) {
        if (copied) { delay(1_500); copied = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.songtap_diag_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!ui.allowed) {
                Text(
                    stringResource(R.string.songtap_diag_need_on),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == Role.SEND,
                    onClick = { mode = Role.SEND; viewModel.switchRole(Role.SEND) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.songtap_mode_send)) }
                SegmentedButton(
                    selected = mode == Role.RECEIVE,
                    onClick = { mode = Role.RECEIVE; viewModel.switchRole(Role.RECEIVE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.songtap_mode_receive)) }
            }

            Block(stringResource(R.string.songtap_diag_signal)) {
                Line("status", snap.status.name.lowercase())
                Line("following", snap.focus.ifEmpty { "-" })
                Line("phones heard", snap.peersSeen.toString())
                Line("signal now", "${snap.rssi} dBm")
                Line("smoothed", "${snap.smoothed} dBm")
                Line("baseline", "${snap.baseline} dBm")
                Line("jump", "${snap.jump} dB   (fires at ${SongTapTuning.JUMP_DB})")
                Line("held for", if (snap.heldMs < 0) "-" else "${snap.heldMs} ms")
                Line("near", if (snap.near) "yes" else "no")
                Line("connection", snap.connection)
                Line("readings", snap.samples.toString())
            }
            Block(stringResource(R.string.songtap_diag_motion)) {
                Line("this phone's last jerk", ageText(snap.ownJerkAgeMs))
                Line("other phone's last jerk", ageText(snap.peerJerkAgeMs))
            }
            Block(stringResource(R.string.songtap_diag_detector)) {
                Line("state", snap.detector)
                Line("last tap", snap.lastFire)
                Line("session", ui.phase.code)
            }
            Block(stringResource(R.string.songtap_diag_tuning)) {
                Line("jump", "${SongTapTuning.JUMP_DB} dB, hold ${SongTapTuning.HOLD_MS} ms, dwell ${SongTapTuning.DWELL_MS} ms")
                Line("jerk window", "${SongTapTuning.IMPULSE_WINDOW_MS} ms, at least ${SongTapTuning.JERK_MILLI_G} milli g")
                Line("near", "from ${SongTapTuning.NEAR_ENTER_DBM} to ${SongTapTuning.NEAR_EXIT_DBM} dBm")
                Line("cooldown", "${SongTapTuning.COOLDOWN_MS} ms")
            }

            Text(stringResource(R.string.songtap_diag_marks), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.songtap_diag_marks_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (label in MARKS) AssistChip(onClick = { viewModel.mark(label) }, label = { Text(label) })
            }

            Text(stringResource(R.string.songtap_diag_events), style = MaterialTheme.typography.titleSmall)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (events.isEmpty()) {
                        Text("-", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    val newest = events.lastOrNull()?.tMs ?: 0L
                    for (e in events.asReversed()) {
                        Text(
                            "-${(newest - e.tMs) / 1000}.${((newest - e.tMs) % 1000).toString().padStart(3, '0')} s  ${e.text}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Song Tap diagnostics", viewModel.diag.copyLog().withSnapshot(snap)))
                    copied = true
                }) { Text(stringResource(if (copied) R.string.songtap_diag_copied else R.string.songtap_diag_copy)) }
                OutlinedButton(onClick = { viewModel.diag.clear() }) { Text(stringResource(R.string.songtap_diag_clear)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val MARKS = listOf("touching", "1 cm", "3 cm", "10 cm", "30 cm")

private fun ageText(ms: Long): String = if (ms < 0) "none lately" else "$ms ms ago"

/** The copied log leads with the numbers on screen at that moment. */
private fun String.withSnapshot(s: DiagSnapshot): String =
    "now: signal ${s.rssi} dBm, smoothed ${s.smoothed}, baseline ${s.baseline}, jump ${s.jump} dB, held ${s.heldMs} ms, near ${s.near}, " +
        "${s.connection}, detector ${s.detector}\n" + this

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Line(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

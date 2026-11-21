package com.theveloper.pixelplay.presentation.songtap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
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
import com.theveloper.pixelplay.data.songtap.ble.CalibrationState
import com.theveloper.pixelplay.data.songtap.ble.RssiCalibration
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SongTapSensitivity
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
    val profile = SongTapSensitivity.fromCode(snap.profile)
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

            Block(stringResource(R.string.songtap_sensitivity_title)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SongTapSensitivity.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = ui.sensitivity == option,
                            onClick = { viewModel.setSensitivity(option) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = SongTapSensitivity.entries.size),
                        ) { Text(stringResource(sensitivityLabel(option))) }
                    }
                }
            }
            Block(stringResource(R.string.songtap_diag_scale)) {
                Text(
                    stringResource(R.string.songtap_diag_scale_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Line(stringResource(R.string.songtap_diag_offset), "${ui.offsetDb} dB")
                CalibrateButton(
                    state = ui.calibration,
                    onDown = viewModel::startCalibration,
                    onUp = viewModel::endCalibration,
                )
                Text(
                    calibrationText(ui.calibration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = viewModel::resetOffset) { Text(stringResource(R.string.songtap_diag_offset_reset)) }
            }

            Block(stringResource(R.string.songtap_diag_signal)) {
                Line("status", snap.status.name.lowercase())
                Line("following", snap.focus.ifEmpty { "-" })
                Line("phones heard", snap.peersSeen.toString())
                Line(stringResource(R.string.songtap_diag_raw), "${snap.rssi} dBm")
                Line(stringResource(R.string.songtap_diag_offset), "${snap.offsetDb} dB")
                Line(stringResource(R.string.songtap_diag_normalised), "${snap.normalised} dBm")
                Line(stringResource(R.string.songtap_diag_smoothed), "${snap.smoothed} dBm")
                Line(stringResource(R.string.songtap_diag_baseline), "${snap.baseline} dBm")
                Line("jump", "${snap.jump} dB   (fires at ${profile.jumpDb})")
                Line("held for", if (snap.heldMs < 0) "-" else "${snap.heldMs} ms")
                Line("near", if (snap.near) "yes" else "no")
                Line(stringResource(R.string.songtap_diag_active_gate), snap.gate)
                Line("may connect and beam", if (snap.mayConnect) "yes" else "no")
                Line("shown in the picker", if (snap.listed) "yes" else "no")
                Line("connection", snap.connection)
                Line("offer in hand", if (snap.offerInHand) "yes" else "no")
                Line("name (advert / full)", snap.names.ifEmpty { "-" })
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
                Line(stringResource(R.string.songtap_diag_profile), stringResource(sensitivityLabel(profile)))
                Line("jump", "${profile.jumpDb} dB, hold ${profile.holdMs} ms, dwell ${profile.dwellMs} ms")
                Line("jerk (optional)", "${profile.impulseWindowMs} ms window, at least ${profile.jerkMilliG} milli g")
                Line("tap near gate (connect, beam)", "in ${SongTapTuning.NEAR_ENTER_DBM}, out ${SongTapTuning.NEAR_EXIT_DBM} dBm")
                Line("list (picker only)", "in ${SongTapTuning.LIST_ENTER_DBM}, out ${SongTapTuning.LIST_EXIT_DBM} dBm")
                Line(stringResource(R.string.songtap_diag_hold_level), "${SongTapTuning.CLOSE_HOLD_DBM} dBm for ${SongTapTuning.CLOSE_HOLD_MS} ms")
                Line("advert power", "level ${com.theveloper.pixelplay.data.songtap.ble.BleTuning.ADVERT_TX_POWER} (1 = low)")
                Line("cooldown", "${profile.cooldownMs} ms")
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
    "now: signal ${s.rssi} dBm raw, offset ${s.offsetDb} dB, normalised ${s.normalised}, smoothed ${s.smoothed}, baseline ${s.baseline}, jump ${s.jump} dB, held ${s.heldMs} ms, near ${s.near}, gate ${s.gate}, " +
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

@StringRes
private fun sensitivityLabel(option: SongTapSensitivity): Int =
    if (option == SongTapSensitivity.HIGH) R.string.songtap_sensitivity_high else R.string.songtap_sensitivity_normal

@Composable
private fun calibrationText(state: CalibrationState): String = when (state) {
    CalibrationState.Idle -> stringResource(R.string.songtap_diag_calibrate_hint)
    is CalibrationState.Running -> stringResource(R.string.songtap_diag_calibrate_running, state.heldMs, RssiCalibration.HOLD_MS)
    is CalibrationState.Done -> stringResource(R.string.songtap_diag_calibrate_done, state.p90Raw, state.offsetDb)
    is CalibrationState.Failed -> stringResource(
        when (state.reason) {
            "no_phone" -> R.string.songtap_diag_calibrate_no_phone
            "too_few" -> R.string.songtap_diag_calibrate_too_few
            else -> R.string.songtap_diag_calibrate_released
        },
    )
}

/**
 * Press and hold for [RssiCalibration.HOLD_MS] with the phones touching: down starts the measurement, up before it is done drops it.
 * A plain surface with its own pointer handling, because a Button would turn the press into a click.
 */
@Composable
private fun CalibrateButton(state: CalibrationState, onDown: () -> Unit, onUp: () -> Unit) {
    val running = state is CalibrationState.Running
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        contentColor = if (running) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onDown()
                    try {
                        tryAwaitRelease()
                    } finally {
                        onUp()
                    }
                })
            },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.songtap_diag_calibrate), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

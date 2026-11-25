package com.theveloper.pixelplay.presentation.songtap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhonelinkRing
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.songtap.ble.BleStatus
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.performAppHapticFeedback
import com.theveloper.pixelplay.presentation.viewmodel.SongTapCodeKind
import com.theveloper.pixelplay.presentation.viewmodel.SongTapNotice
import com.theveloper.pixelplay.presentation.viewmodel.SongTapRingNote
import com.theveloper.pixelplay.presentation.viewmodel.SongTapUiState
import com.theveloper.pixelplay.presentation.viewmodel.SongTapViewModel
import com.theveloper.pixelplay.songtap.HapticCue
import com.theveloper.pixelplay.songtap.NearbyEntry
import com.theveloper.pixelplay.songtap.Phase
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.ring.RingScanMessage
import com.theveloper.pixelplay.songtap.ring.RingScanStage
import com.theveloper.pixelplay.songtap.ring.RingSendRules
import kotlinx.coroutines.delay

/**
 * Song Tap's full screen. Armed only while it is on screen and in the foreground.
 *
 * Send: the song that is playing, large; touch the backs of the two phones together and it flies off the top of
 * this screen into the other phone. Receive: wait, and a card with the song enters from the top. A glow at the
 * top edge grows as the other phone comes close. A code (shown or scanned) does the same job without Bluetooth
 * and is one tap away on both sides.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongTapScreen(
    navController: NavController,
    viewModel: SongTapViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(Role.SEND) }
    var useCode by rememberSaveable { mutableStateOf(false) }
    // A phone the listener picked from the nearby list, waiting for its "Song drop to X?" answer.
    var picked by remember { mutableStateOf<NearbyEntry?>(null) }
    val view = LocalView.current
    val context = LocalContext.current
    val hapticsConfig = LocalAppHapticsConfig.current
    val motion = remember { MotionScheme.expressive() }
    val rootHeight = remember { mutableIntStateOf(0) }

    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.bluetoothChanged()
    }
    val requestBluetooth = { bluetoothLauncher.launch(bluetoothPermissions()) }

    LaunchedEffect(viewModel) { viewModel.attach() }
    val perform: (HapticCue) -> Unit = { cue -> view.performAppHapticFeedback(hapticsConfig, hapticConstant(cue)) }
    LaunchedEffect(viewModel, view, hapticsConfig) { viewModel.haptics.collect { perform(it) } }
    // Armed only while this screen is resumed: leaving the app or the screen stops everything, radio and sensor included.
    LifecycleResumeEffect(ui.allowed, viewModel) {
        if (ui.allowed) viewModel.arm(mode)
        onPauseOrDispose { viewModel.disarm() }
    }

    // Song Drop from a song's options opens this screen as a sender.
    LaunchedEffect(ui.dropMode) {
        if (ui.dropMode && mode != Role.SEND) { mode = Role.SEND; useCode = false; viewModel.switchRole(Role.SEND) }
    }
    val beamTime = rememberBeamTime(ui.beamRole, ui.beamStartMs, ui.beamReduced, perform)
    val approach = viewModel.glow.collectAsStateWithLifecycle()
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(ui.phase) {
        // The moment of contact: the glow flares once.
        if ((ui.phase == Phase.OFFERED || ui.phase == Phase.TRANSFERRING) && ui.session.peer != 0 && !ui.beamReduced) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, motion.slowEffectsSpec())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { rootHeight.intValue = it.height },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.songtap_title)) },
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
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!ui.allowed) {
                    TurnOnCard(onTurnOn = {
                        viewModel.allow(true)
                        // The one place Bluetooth is asked for: the moment Song Tap is turned on.
                        requestBluetooth()
                    })
                } else {
                    ModeSwitch(mode = mode, onChange = { mode = it; useCode = false; viewModel.switchRole(it) })
                    BluetoothCard(
                        status = ui.bluetooth,
                        onAllow = { requestBluetooth() },
                        onOpenSettings = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    )
                    ResultCard(
                        ui = ui,
                        onAdd = viewModel::accept,
                        onNotNow = viewModel::decline,
                        onUndo = viewModel::undo,
                        onOk = viewModel::dismiss,
                    )
                    if (mode == Role.SEND) {
                        SendContent(
                            ui = ui,
                            useCode = useCode,
                            time = beamTime,
                            rootHeight = rootHeight,
                            approach = approach,
                            onShowCode = { useCode = true; viewModel.showCode() },
                            onHideCode = { useCode = false; viewModel.hideCode() },
                            onUseQr = viewModel::showQrInstead,
                            onPick = { picked = it },
                            onCancelDrop = viewModel::cancelDrop,
                        )
                    } else {
                        ReceiveContent(ui = ui, viewModel = viewModel)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        SongTapGlow(approach = approach, time = beamTime, role = ui.beamRole, reduced = ui.beamReduced, pulse = pulse.asState())

        // "Song drop to X?": for a phone the listener picked from the list, or for one that came inside the strict near gate.
        SongDropAsk(
            ui = ui,
            enabled = ui.allowed && mode == Role.SEND && !useCode,
            picked = picked,
            viewModel = viewModel,
            onAnswered = { picked = null },
        )

        val card = ui.card
        AnimatedVisibility(
            visible = ui.beamRole == Role.RECEIVE && card != null,
            enter = fadeIn(motion.fastEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.Center),
        ) {
            if (card != null) {
                ReceiveBeamCard(
                    card = card,
                    phase = ui.phase,
                    alreadyHad = ui.session.alreadyHad,
                    from = ui.peerName,
                    time = beamTime,
                    reduced = ui.beamReduced,
                    rootHeight = rootHeight,
                    onUndo = viewModel::undo,
                )
            }
        }
    }
}

private fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hapticConstant(cue: HapticCue): Int = when (cue) {
    HapticCue.TICK -> HapticFeedbackConstants.CLOCK_TICK
    HapticCue.CONFIRM -> HapticFeedbackConstants.CONTEXT_CLICK
    HapticCue.SUCCESS -> HapticFeedbackConstants.CONFIRM
    HapticCue.WARN -> HapticFeedbackConstants.REJECT
}

@Composable
private fun TurnOnCard(onTurnOn: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.PhonelinkRing, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.songtap_off_headline), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.songtap_off_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onTurnOn, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.songtap_turn_on)) }
        }
    }
}

@Composable
private fun ModeSwitch(mode: Role, onChange: (Role) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = mode == Role.SEND,
            onClick = { onChange(Role.SEND) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text(stringResource(R.string.songtap_mode_send)) }
        SegmentedButton(
            selected = mode == Role.RECEIVE,
            onClick = { onChange(Role.RECEIVE) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text(stringResource(R.string.songtap_mode_receive)) }
    }
}

/** What is wrong with Bluetooth, and the one button that fixes it. Nothing when it works; the code route is always there. */
@Composable
private fun BluetoothCard(status: BleStatus, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    val text = when (status) {
        BleStatus.NO_PERMISSION -> R.string.songtap_bt_permission
        BleStatus.BLUETOOTH_OFF -> R.string.songtap_bt_off
        BleStatus.UNSUPPORTED -> R.string.songtap_bt_unsupported
        else -> return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            when (status) {
                BleStatus.NO_PERMISSION -> Button(onClick = onAllow) { Text(stringResource(R.string.songtap_bt_allow)) }
                BleStatus.BLUETOOTH_OFF -> Button(onClick = onOpenSettings) { Text(stringResource(R.string.songtap_bt_open_settings)) }
                else -> Unit
            }
        }
    }
}

@Composable
private fun statusLine(ui: SongTapUiState): String? {
    if (ui.bluetooth != BleStatus.ACTIVE) return null
    val id = when {
        ui.phase == Phase.SAVED && ui.role == Role.SEND -> R.string.songtap_status_sent
        ui.phase == Phase.ARMED -> R.string.songtap_status_looking
        ui.phase == Phase.FOUND -> R.string.songtap_status_found
        ui.phase == Phase.NEAR -> R.string.songtap_status_near
        else -> return null
    }
    return stringResource(id)
}

@Composable
private fun SendContent(
    ui: SongTapUiState,
    useCode: Boolean,
    time: androidx.compose.runtime.State<Float>,
    rootHeight: androidx.compose.runtime.IntState,
    approach: State<Int>,
    onShowCode: () -> Unit,
    onHideCode: () -> Unit,
    onUseQr: () -> Unit,
    onPick: (NearbyEntry) -> Unit,
    onCancelDrop: () -> Unit,
) {
    val playing = ui.nowPlaying
    if (playing == null) {
        Text(
            stringResource(R.string.songtap_send_nothing),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val ring = ui.ring
    val ringShown = useCode && ui.codeKind == SongTapCodeKind.RING && ring != null
    if (ringShown && ring != null) {
        // The Pixl ring code: the playing song's artwork in the middle, the dots in this phone's own colour around it.
        RingCodeView(
            slots = ring,
            palette = rememberRingPalette(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            approach = approach,
            beamStartMs = if (ui.beamRole == Role.SEND) ui.beamStartMs else 0L,
            rootHeight = rootHeight,
            pillText = stringResource(R.string.songtap_ring_sent),
        ) {
            if (playing.artUrl != null) {
                AsyncImage(model = playing.artUrl, contentDescription = stringResource(R.string.songtap_ring_description), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    } else {
        BeamArtwork(
            url = playing.artUrl,
            role = ui.beamRole,
            time = time,
            reduced = ui.beamReduced,
            rootHeight = rootHeight,
            modifier = Modifier.padding(top = 8.dp).size(248.dp),
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(playing.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(
            playing.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
    if (!playing.canSend) {
        Text(
            stringResource(R.string.songtap_send_cant),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val armed = ui.session.target != 0 && ui.phase == Phase.OFFERED
    if (!armed && !useCode) {
        val status = statusLine(ui)
        Text(
            status ?: stringResource(R.string.songtap_send_hint),
            style = MaterialTheme.typography.titleSmall,
            color = if (status != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.animateContentSize(),
        )
    }
    val sending = ui.role == Role.SEND && ui.phase == Phase.TRANSFERRING
    if (armed) {
        ArmedCard(name = ui.peerName, onCancel = onCancelDrop)
        return
    }
    if (sending && ui.beamRole == null && ui.session.beamDone) {
        Text(
            stringResource(R.string.songdrop_waiting, ui.peerName ?: stringResource(R.string.songdrop_other_phone)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    val code = ui.code
    val waitingPhase = ui.phase == Phase.ARMED || ui.phase == Phase.FOUND || ui.phase == Phase.NEAR
    if (!useCode && waitingPhase) {
        NearbyList(entries = ui.nearby, onPick = onPick)
    }
    if (ringShown) {
        Text(
            stringResource(R.string.songtap_ring_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // The QR is the last resort: it is offered once the code has been up for a few seconds without being taken, never beside the ring code.
        var qrOffered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(RingSendRules.QR_OFFER_AFTER_MS)
            qrOffered = true
        }
        if (qrOffered) TextButton(onClick = onUseQr) { Text(stringResource(R.string.songtap_use_qr)) }
        OutlinedButton(onClick = onHideCode, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.songtap_back_to_tap)) }
    } else if (useCode && code != null) {
        if (ui.ringNote == SongTapRingNote.NO_VIDEO) {
            Text(
                stringResource(R.string.songtap_ring_no_video),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        val image = rememberQrImage(code)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.songtap_code_description),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Text(
            stringResource(R.string.songtap_code_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onHideCode, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.songtap_back_to_tap)) }
    } else {
        TextButton(onClick = onShowCode) { Text(stringResource(R.string.songtap_use_code)) }
    }
}

@Composable
private fun ReceiveContent(ui: SongTapUiState, viewModel: SongTapViewModel) {
    var scanning by rememberSaveable { mutableStateOf(false) }
    // The plain QR code is the last resort: it only opens when the listener asks for it after the ring scan failed.
    var qrMode by rememberSaveable { mutableStateOf(false) }
    if (scanning) {
        if (qrMode) {
            Scanner(onScanned = viewModel::onScanned)
            OutlinedButton(onClick = { qrMode = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.songtap_back_to_ring)) }
        } else {
            RingScanPane(ui = ui, viewModel = viewModel, onUseQr = { qrMode = true })
        }
        OutlinedButton(onClick = { scanning = false; qrMode = false }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.songtap_back_to_tap))
        }
        return
    }
    Box(
        modifier = Modifier
            .padding(top = 24.dp)
            .size(184.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PhonelinkRing, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
    }
    val status = statusLine(ui)
    Text(
        status ?: stringResource(R.string.songtap_receive_hint),
        style = MaterialTheme.typography.titleSmall,
        color = if (status != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.animateContentSize(),
    )
    TextButton(onClick = { scanning = true }) { Text(stringResource(R.string.songtap_scan_code)) }
}

/**
 * The receiver's camera for the Pixl ring code. It runs only while this screen is resumed and waiting for a song (the session is not
 * in the middle of a beam), asks for the camera the first time, and shows "Use QR code instead" only when the scan has failed for about
 * six seconds or the camera cannot run the scanner.
 */
@Composable
private fun RingScanPane(ui: SongTapUiState, viewModel: SongTapViewModel, onUseQr: () -> Unit) {
    val context = LocalContext.current
    val cameraExists = remember { hasCamera(context) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val waiting = ui.phase == Phase.ARMED || ui.phase == Phase.FOUND || ui.phase == Phase.NEAR
    val scan = ui.ringScan

    if (cameraExists && granted) {
        // One scan session per time on screen while waiting: a result (a beam in progress) closes it, the end of the beam opens it again.
        LifecycleResumeEffect(waiting) {
            if (waiting) viewModel.ringScanStart(cameraAvailable = true)
            onPauseOrDispose { viewModel.ringScanStop() }
        }
        val live = waiting && (scan.stage == RingScanStage.SCANNING || scan.stage == RingScanStage.RESOLVING)
        if (live) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                RingCodeCamera(sink = viewModel.ringSink, onCameraFailed = viewModel::ringCameraFailed)
                // A guide for where to hold the code: the camera is not aimed at anything else.
                val guide = Color.White.copy(alpha = 0.55f)
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize(0.82f)) {
                    drawCircle(guide, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                }
            }
        }
    } else if (cameraExists) {
        Text(
            stringResource(R.string.songtap_camera_needed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.songtap_allow_camera))
        }
    } else {
        // No camera at all: the scan opens in its "cannot run" state, which offers the QR (whose own camera start will say the same).
        LaunchedEffect(Unit) { viewModel.ringScanStart(cameraAvailable = false) }
    }

    val line = when {
        scan.stage == RingScanStage.RESOLVING -> R.string.songtap_ring_looking_up
        scan.message == RingScanMessage.EXPIRED -> R.string.songtap_ring_expired
        scan.message == RingScanMessage.CLOCK -> R.string.songtap_ring_clock
        scan.message == RingScanMessage.LOOKUP_FAILED -> R.string.songtap_ring_lookup_failed
        scan.message == RingScanMessage.REPLAY -> R.string.songtap_ring_replay
        scan.stage == RingScanStage.UNAVAILABLE && !cameraExists -> R.string.songtap_ring_no_camera
        scan.stage == RingScanStage.UNAVAILABLE -> R.string.songtap_ring_camera_failed
        cameraExists && granted && waiting -> R.string.songtap_ring_scan_hint
        else -> null
    }
    if (line != null) {
        Text(
            stringResource(line),
            style = MaterialTheme.typography.bodyMedium,
            color = if (scan.message == RingScanMessage.NONE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
    if (scan.qrOffered) TextButton(onClick = onUseQr) { Text(stringResource(R.string.songtap_use_qr)) }
}

/** The camera, behind its permission: asked for the first time the listener chooses to scan a code, and nowhere else. */
@Composable
private fun Scanner(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            QrScanner(onText = onScanned)
        }
        Text(
            stringResource(R.string.songtap_scan_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            stringResource(R.string.songtap_camera_needed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.songtap_allow_camera))
        }
    }
}

@Composable
private fun SongRow(title: String, artist: String, artUrl: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (artUrl != null) {
                AsyncImage(model = artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What happened, in one plain card, when the beam is not showing it: the question the first time (Add / Not now),
 * a result that came without a beam (a code, a slow save), or why it did not work.
 */
@Composable
private fun ResultCard(ui: SongTapUiState, onAdd: () -> Unit, onNotNow: () -> Unit, onUndo: () -> Unit, onOk: () -> Unit) {
    val card = ui.card
    val phase = ui.phase
    val receiving = ui.role == Role.RECEIVE
    val beamShowsIt = ui.beamRole == Role.RECEIVE
    val showsSong = card != null && receiving && !beamShowsIt && (phase == Phase.OFFERED || phase == Phase.TRANSFERRING || phase == Phase.SAVED)
    val showsText = phase == Phase.FAILED || phase == Phase.DECLINED || phase == Phase.CANCELLED || (ui.notice == SongTapNotice.INVALID_CODE && phase == Phase.ARMED)
    if (!showsSong && !showsText) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showsSong && card != null) {
                if (ui.peerName != null) {
                    Text(
                        stringResource(R.string.songtap_from, ui.peerName),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                SongRow(title = card.title, artist = card.artist, artUrl = card.artUrl)
                when (phase) {
                    Phase.OFFERED -> {
                        Text(stringResource(R.string.songtap_card_ask), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onAdd, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.songtap_card_add)) }
                            OutlinedButton(onClick = onNotNow, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.songtap_card_not_now)) }
                        }
                    }
                    Phase.TRANSFERRING -> Text(stringResource(R.string.songtap_card_adding), style = MaterialTheme.typography.titleSmall)
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (ui.session.alreadyHad) R.string.songtap_card_already else R.string.songtap_card_saved),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (!ui.session.alreadyHad) TextButton(onClick = onUndo) { Text(stringResource(R.string.songtap_card_undo)) }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(noticeText(ui), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOk) { Text(stringResource(R.string.songtap_card_ok)) }
                }
            }
        }
    }
}

@Composable
private fun noticeText(ui: SongTapUiState): String {
    val id = when (ui.notice) {
        SongTapNotice.INVALID_CODE -> R.string.songtap_notice_invalid_code
        SongTapNotice.NOTHING_TO_SEND -> R.string.songtap_notice_nothing
        SongTapNotice.NOT_FOUND -> R.string.songtap_notice_not_found
        SongTapNotice.SAVE_FAILED -> R.string.songtap_notice_save_failed
        SongTapNotice.TIMEOUT -> R.string.songtap_notice_timeout
        SongTapNotice.TOO_SOON -> R.string.songtap_notice_too_soon
        SongTapNotice.LIMIT -> R.string.songtap_notice_limit
        SongTapNotice.DUPLICATE -> R.string.songtap_notice_duplicate
        SongTapNotice.MUTED -> R.string.songtap_notice_muted
        SongTapNotice.BOTH_SENDING -> R.string.songtap_notice_both_sending
        SongTapNotice.PEER_LOST -> R.string.songtap_notice_peer_lost
        SongTapNotice.INVALID_OFFER -> R.string.songtap_notice_invalid_offer
        null -> when (ui.phase) {
            Phase.DECLINED -> R.string.songtap_declined
            Phase.CANCELLED -> R.string.songtap_cancelled
            else -> R.string.songtap_notice_save_failed
        }
    }
    return stringResource(id)
}

/** The phones that advertise as receivers inside the list gate, strongest first. A row is "Close" inside the strict near gate, "Nearby" otherwise. */
@Composable
internal fun NearbyList(entries: List<NearbyEntry>, onPick: (NearbyEntry) -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.songdrop_nearby_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.songdrop_nearby_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (entry in entries) {
            Card(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { onPick(entry) },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(if (entry.near) R.string.songdrop_signal_close else R.string.songdrop_signal_nearby),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** After a yes: the sender waits for the touch, armed to that one phone. */
@Composable
internal fun ArmedCard(name: String?, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.PhonelinkRing, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
            Text(
                stringResource(R.string.songdrop_armed_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.songdrop_armed_to, name ?: stringResource(R.string.songdrop_other_phone)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.songdrop_armed_cancel)) }
        }
    }
}

/**
 * The "Song drop to X?" question, shared by the Song Tap screen and its diagnostics screen: for the phone picked from the list ([picked]) or the
 * one the transport asks about (`ui.prompt`), while a sender is waiting and the song can be sent. Yes arms the beam to that phone, No silences it.
 */
@Composable
internal fun SongDropAsk(ui: SongTapUiState, enabled: Boolean, picked: NearbyEntry?, viewModel: SongTapViewModel, onAnswered: () -> Unit) {
    val waiting = ui.phase == Phase.ARMED || ui.phase == Phase.FOUND || ui.phase == Phase.NEAR
    val canSend = ui.nowPlaying?.canSend == true
    val asking = picked ?: ui.prompt.takeIf { enabled && waiting && canSend }
    val song = ui.nowPlaying
    if (asking != null && waiting && canSend && song != null) {
        SongDropDialog(
            name = asking.name,
            songTitle = song.title,
            onYes = { viewModel.approve(asking.id, asking.name); onAnswered() },
            onNo = { viewModel.decideNo(asking.id); onAnswered() },
        )
    }
}

@Composable
private fun SongDropDialog(name: String, songTitle: String, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(stringResource(R.string.songdrop_confirm_title, name)) },
        text = { Text(stringResource(R.string.songdrop_confirm_body, songTitle)) },
        confirmButton = { Button(onClick = onYes) { Text(stringResource(R.string.songdrop_yes)) } },
        dismissButton = { TextButton(onClick = onNo) { Text(stringResource(R.string.songdrop_no)) } },
    )
}

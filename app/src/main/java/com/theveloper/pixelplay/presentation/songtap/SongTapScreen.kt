package com.theveloper.pixelplay.presentation.songtap

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhonelinkRing
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.performAppHapticFeedback
import com.theveloper.pixelplay.presentation.viewmodel.SongTapNotice
import com.theveloper.pixelplay.presentation.viewmodel.SongTapUiState
import com.theveloper.pixelplay.presentation.viewmodel.SongTapViewModel
import com.theveloper.pixelplay.songtap.HapticCue
import com.theveloper.pixelplay.songtap.Phase
import com.theveloper.pixelplay.songtap.Role

/**
 * Song Tap's full screen. This milestone is the working route, in plain Material 3: Send shows a code for the
 * song that is playing, Receive scans one, and a card says what happened (Add / Not now the first time,
 * then Undo). It is armed only while the screen is on screen and in the foreground.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongTapScreen(
    navController: NavController,
    viewModel: SongTapViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(Role.SEND) }
    val view = LocalView.current
    val hapticsConfig = LocalAppHapticsConfig.current

    LaunchedEffect(viewModel) { viewModel.attach() }
    LaunchedEffect(viewModel, view, hapticsConfig) {
        viewModel.haptics.collect { cue -> view.performAppHapticFeedback(hapticsConfig, hapticConstant(cue)) }
    }
    // Armed only while this screen is resumed: leaving the app or the screen stops everything.
    LifecycleResumeEffect(ui.allowed, viewModel) {
        if (ui.allowed) viewModel.arm(mode)
        onPauseOrDispose { viewModel.disarm() }
    }

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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!ui.allowed) {
                TurnOnCard(onTurnOn = { viewModel.allow(true) })
            } else {
                ModeSwitch(mode = mode, onChange = { mode = it; viewModel.switchRole(it) })
                ResultCard(
                    ui = ui,
                    onAdd = viewModel::accept,
                    onNotNow = viewModel::decline,
                    onUndo = viewModel::undo,
                    onOk = viewModel::dismiss,
                )
                if (mode == Role.SEND) {
                    SendContent(ui = ui, onShow = viewModel::showCode, onHide = viewModel::hideCode)
                } else {
                    ReceiveContent(onScanned = viewModel::onScanned)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
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

@Composable
private fun SendContent(ui: SongTapUiState, onShow: () -> Unit, onHide: () -> Unit) {
    val playing = ui.nowPlaying
    if (playing == null) {
        Text(
            stringResource(R.string.songtap_send_nothing),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(stringResource(R.string.songtap_send_now_playing), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    SongRow(title = playing.title, artist = playing.artist, artUrl = playing.artUrl)
    val code = ui.code
    if (code != null) {
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
        OutlinedButton(onClick = onHide, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.songtap_hide_code)) }
    } else if (!playing.canSend) {
        Text(
            stringResource(R.string.songtap_send_cant),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Button(onClick = onShow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.songtap_show_code)) }
    }
}

@Composable
private fun ReceiveContent(onScanned: (String) -> Unit) {
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
        // The camera is asked for here, the first time the listener chooses to scan, and nowhere else.
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
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** What happened, in one card: the question the first time, the song that was added, or why not. */
@Composable
private fun ResultCard(ui: SongTapUiState, onAdd: () -> Unit, onNotNow: () -> Unit, onUndo: () -> Unit, onOk: () -> Unit) {
    val card = ui.card
    val phase = ui.phase
    val receiving = ui.role == Role.RECEIVE
    val showsSong = card != null && receiving && (phase == Phase.OFFERED || phase == Phase.TRANSFERRING || phase == Phase.SAVED)
    val showsText = phase == Phase.FAILED || phase == Phase.DECLINED || phase == Phase.CANCELLED || (ui.notice == SongTapNotice.INVALID_CODE && phase == Phase.ARMED)
    if (!showsSong && !showsText) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showsSong && card != null) {
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

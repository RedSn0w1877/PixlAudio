package com.theveloper.pixelplay.presentation.screens.remix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.theveloper.pixelplay.presentation.components.remix.FilterCard
import com.theveloper.pixelplay.presentation.components.remix.ListeningCard
import com.theveloper.pixelplay.presentation.components.remix.RemixWaveformScrubber
import com.theveloper.pixelplay.presentation.components.remix.SpaceCard
import com.theveloper.pixelplay.presentation.components.remix.SpatialStage
import com.theveloper.pixelplay.presentation.components.remix.TapeCard
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.RemixStudioViewModel
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Remix Studio: drag the parts of a song around you, loop a bar, and mangle it.
 *
 * The studio takes over audio while it is open — it runs its own engine so the stems can be
 * mixed sample-accurately, which the shared player cannot do. Leaving the screen stops it and
 * hands playback back.
 */
@Composable
fun RemixStudioScreen(
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit,
    viewModel: RemixStudioViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val playerState by playerViewModel.stablePlayerState.collectAsState()
    val song = playerState.currentSong

    LaunchedEffect(song?.id) {
        song?.let { viewModel.load(it) }
    }

    // The engine is foreground-only by design: no notification, no lock screen. Stopping on
    // ON_STOP is what keeps it from quietly holding audio focus behind another app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stop()
        }
    }

    // Meters and playhead are polled on the frame clock rather than pushed from the audio thread.
    var playhead by remember { mutableFloatStateOf(0f) }
    var yaw by remember { mutableFloatStateOf(0f) }
    var peaks by remember { mutableStateOf(FloatArray(4)) }
    LaunchedEffect(uiState.playing) {
        while (uiState.playing) {
            withFrameNanos { }
            playhead = viewModel.playheadFraction
            yaw = viewModel.listenerYaw
            peaks = viewModel.stemPeaks.copyOf()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Remix Studio",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    uiState.songTitle.ifBlank { "Play something first" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.loading) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }

        uiState.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "stage") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpatialStage(
                        stems = uiState.stems,
                        peaks = peaks,
                        listenerYaw = yaw,
                        onStemMoved = viewModel::onStemMoved,
                        onStemTapped = viewModel::onStemMuteToggled,
                    )
                }
            }

            item(key = "stem_note") {
                Text(
                    text = if (uiState.separated) {
                        "Drag a part anywhere around you. Tap one to mute it."
                    } else {
                        "No separated stems yet, so this is the centre of the mix against its sides — " +
                            "vocals and bass tend to sit in the centre. Separate the track for four real parts."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item(key = "waveform") {
                RemixWaveformScrubber(
                    peaks = uiState.waveform,
                    playheadFraction = playhead,
                    loopStartMs = uiState.loopStartMs,
                    loopLengthMs = uiState.loopLengthMs,
                    songDurationMs = uiState.songDurationMs,
                    onLoopChanged = viewModel::setLoop,
                )
            }

            if (uiState.canSeparate || uiState.separating || uiState.separationDetail != null) {
                item(key = "separate") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = false,
                            enabled = !uiState.separating && uiState.canSeparate,
                            onClick = viewModel::separateIntoStems,
                            label = {
                                Text(
                                    if (uiState.separating) {
                                        "Separating… ${uiState.separationPercent}%"
                                    } else {
                                        "Separate into 4 parts"
                                    }
                                )
                            },
                        )
                        uiState.separationDetail?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item(key = "transport") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.playing,
                        onClick = { if (uiState.playing) viewModel.stop() else viewModel.play() },
                        label = { Text(if (uiState.playing) "Stop" else "Play") },
                    )
                }
            }

            item(key = "tape") {
                TapeCard(rate = uiState.rate, onRateChanged = viewModel::setRate)
            }

            item(key = "filter") {
                FilterCard(
                    cutoffHz = uiState.cutoffHz,
                    resonance = uiState.resonance,
                    mode = uiState.filterMode,
                    bits = uiState.bits,
                    decim = uiState.decim,
                    onFilterChanged = viewModel::setFilter,
                    onLoFiChanged = viewModel::setLoFi,
                )
            }

            item(key = "room") {
                SpaceCard(
                    reverbMix = uiState.reverbMix,
                    rt60 = uiState.rt60,
                    decayMs = uiState.decayMs,
                    onReverbChanged = viewModel::setReverb,
                    onDecayChanged = viewModel::setDecay,
                )
            }

            item(key = "listening") {
                ListeningCard(
                    poseSourceId = uiState.poseSourceId,
                    headTrackerAvailable = uiState.headTrackerAvailable,
                    onPoseSourceChanged = viewModel::setPoseSource,
                    onRecentre = viewModel::recentrePose,
                )
            }
            item(key = "server_card") {
                SeparationServerCard(
                    backendUrl = uiState.backendUrl,
                    tokenSet = uiState.backendTokenSet,
                    consent = uiState.uploadConsent,
                    onUrlChanged = viewModel::setBackendUrl,
                    onTokenChanged = viewModel::setBackendToken,
                    onConsentChanged = viewModel::setUploadConsent,
                )
            }
        }
    }
}

/**
 * Where the four-part separation happens. Kept in the studio rather than buried in settings,
 * because it only matters here and because the settings screen's state is assembled from a
 * positional combine that is easy to break by adding to.
 */
@Composable
private fun SeparationServerCard(
    backendUrl: String,
    tokenSet: Boolean,
    consent: Boolean,
    onUrlChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onConsentChanged: (Boolean) -> Unit,
) {
    var urlDraft by remember(backendUrl) { mutableStateOf(backendUrl) }
    var tokenDraft by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(22.dp, 60),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Separation server",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Splitting a song into four parts runs on a GPU server. Your track is uploaded to " +
                    "it, so this is off until you switch it on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text("Server address") },
                placeholder = { Text("https://…proxy.runpod.net") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tokenDraft,
                onValueChange = { tokenDraft = it },
                label = { Text(if (tokenSet) "Access token (saved)" else "Access token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = false,
                    onClick = {
                        onUrlChanged(urlDraft)
                        if (tokenDraft.isNotBlank()) {
                            onTokenChanged(tokenDraft)
                            tokenDraft = ""
                        }
                    },
                    label = { Text("Save") },
                )
                Text(
                    "Upload tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = consent, onCheckedChange = onConsentChanged)
            }
        }
    }
}

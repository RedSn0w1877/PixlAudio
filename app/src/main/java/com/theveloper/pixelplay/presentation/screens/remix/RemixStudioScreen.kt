package com.theveloper.pixelplay.presentation.screens.remix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.presentation.components.remix.LoopStrip
import com.theveloper.pixelplay.presentation.components.remix.PartChipRow
import com.theveloper.pixelplay.presentation.components.remix.RemixRoom
import com.theveloper.pixelplay.presentation.components.remix.RemixStageMotion
import com.theveloper.pixelplay.presentation.components.remix.RoomBackdrop
import com.theveloper.pixelplay.presentation.components.remix.RoomRail
import com.theveloper.pixelplay.presentation.components.remix.RoomSegments
import com.theveloper.pixelplay.presentation.components.remix.RoomToggle
import com.theveloper.pixelplay.presentation.components.remix.TonePad
import com.theveloper.pixelplay.presentation.components.remix.TransportBar
import com.theveloper.pixelplay.presentation.components.scoped.rememberSheetThemeState
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.RemixStudioViewModel
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import com.theveloper.pixelplay.ui.theme.ShapeCache
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Remix Studio: a room you stand in the middle of, with the pieces of the song placed around you.
 *
 * The screen is four children of one Box — backdrop, room, floating HUD, and a three-detent sheet
 * of controls. Deliberately **not** a LazyColumn with a diagram at the top: that structure was
 * what let the transport scroll off the bottom, and what disposed the stage (resetting every drag)
 * as soon as you scrolled past it.
 *
 * The studio takes over audio while it is open — it runs its own engine so the pieces can be mixed
 * sample-accurately, which the shared player cannot do. Leaving stops it and hands playback back.
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

    // Album colour has to be pulled in here: the screen is mounted plainly, so without this the
    // whole room renders in wallpaper colour while the rest of the app follows the artwork.
    val activePair by playerViewModel.activePlayerColorSchemePair.collectAsStateWithLifecycle()
    val themedArtUri by playerViewModel.currentThemedAlbumArtUri.collectAsStateWithLifecycle()
    val themePreference by playerViewModel.playerThemePreference.collectAsStateWithLifecycle()
    val isDark = LocalPixelPlayDarkTheme.current
    val roomScheme = rememberSheetThemeState(
        activePlayerSchemePair = activePair,
        isDarkTheme = isDark,
        playerThemePreference = themePreference,
        currentSong = song,
        themedAlbumArtUri = themedArtUri,
        preparingSongId = null,
        systemColorScheme = MaterialTheme.colorScheme,
    ).albumColorScheme

    LaunchedEffect(song?.id) { song?.let { viewModel.load(it) } }

    // Foreground-only by design: no notification, no lock screen. Stopping on ON_STOP is what
    // keeps the engine from quietly holding audio focus behind another app.
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

    val motion = remember { RemixStageMotion() }

    // Keep the motion object's puck targets in step with committed UI state. Positions during a
    // drag come from the gesture directly; this only catches loads, resets and nudges.
    LaunchedEffect(uiState.stems) {
        motion.stemCount = uiState.stems.size
        uiState.stems.forEach { stem ->
            if (motion.curX[stem.index] == 0f && motion.curY[stem.index] == 0f) {
                motion.snapStem(stem.index, stem.x, stem.y)
            } else {
                motion.targetStem(stem.index, stem.x, stem.y)
            }
        }
    }

    // One frame loop for the life of the screen — not gated on `playing`, so ambient motion and
    // puck settling keep working while stopped. It touches exactly one Compose state, once.
    LaunchedEffect(motion) {
        var last = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            if (last == 0L) last = now
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
            last = now
            val state = viewModel.uiState.value
            motion.timeSec += dt * state.rate
            motion.samplePeaks(viewModel.stemPeaks, dt, state.reverbMix)
            motion.playhead = viewModel.playheadFraction
            motion.yaw = viewModel.listenerYaw
            motion.advanceRings(dt, state.rate, state.bits < 16, state.decim > 1)
            motion.stepPucks(dt)
            motion.decayHead(dt, state.rt60)
            motion.frame.intValue++
        }
    }

    MaterialTheme(
        colorScheme = roomScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
    ) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val peekPx = with(density) { 150.dp.toPx() }
        var heightPx by remember { mutableStateOf(0f) }
        val sheetOffset = remember { Animatable(peekPx) }

        val ready = !uiState.loading && uiState.error == null && uiState.stems.isNotEmpty()
        val sheetColor = MaterialTheme.colorScheme.surfaceContainerLow

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            RoomBackdrop(motion)

            RemixRoom(
                stems = uiState.stems,
                motion = motion,
                manualPose = uiState.poseSourceId == "manual",
                onStemMoved = viewModel::onStemMoved,
                onStemDragEnd = viewModel::onStemDragEnd,
                onStemTapped = viewModel::onStemMuteToggled,
                onStemSolo = viewModel::onStemSoloToggled,
                onHeadTapped = {
                    val next = when (uiState.poseSourceId) {
                        "device" -> "manual"
                        else -> "device"
                    }
                    viewModel.setPoseSource(next)
                },
                onManualHeading = viewModel::setManualHeading,
                poseCaption = when (uiState.poseSourceId) {
                    "manual" -> "Drag your head to look around"
                    "headset" -> "Your earbuds are steering"
                    else -> "Turn your phone to look around"
                },
                bottomInsetPx = sheetOffset.value,
            )

            StageHud(
                title = uiState.songTitle,
                loading = uiState.loading,
                onBack = onNavigateBack,
                onFaceForward = viewModel::recentrePose,
            )

            if (song == null) {
                EmptyRoom(onPick = onNavigateBack)
            }

            uiState.error?.let { message ->
                ErrorBanner(
                    message = message,
                    onRetry = { song?.let { viewModel.load(it, resumePlaying = true) } },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 160.dp, start = 16.dp, end = 16.dp),
                )
            }

            // ── the sheet ────────────────────────────────────────────────
            val openPx = heightPx * 0.52f
            val fullPx = heightPx * 0.88f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { heightPx = it.height.toFloat() }
                    .offset {
                        IntOffset(0, (heightPx - sheetOffset.value).roundToInt().coerceAtLeast(0))
                    }
                    .clip(
                        AbsoluteSmoothCornerShape(
                            cornerRadiusTL = 32.dp, cornerRadiusTR = 32.dp,
                            cornerRadiusBL = 0.dp, cornerRadiusBR = 0.dp,
                            smoothnessAsPercentTL = 60, smoothnessAsPercentTR = 60,
                            smoothnessAsPercentBL = 60, smoothnessAsPercentBR = 60,
                        )
                    )
                    .drawWithCache {
                        // The top band is thinner so the nearest arc of the floor bleeds up under
                        // it — the sheet sits *on* the floor rather than slicing it off.
                        val brush = Brush.verticalGradient(
                            0f to sheetColor.copy(alpha = 0.82f),
                            0.12f to sheetColor.copy(alpha = 0.97f),
                            1f to sheetColor,
                        )
                        onDrawBehind { drawRect(brush) }
                    }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                sheetOffset.snapTo(
                                    (sheetOffset.value - delta).coerceIn(peekPx, fullPx.coerceAtLeast(peekPx))
                                )
                            }
                        },
                        onDragStopped = { velocity ->
                            val projected = sheetOffset.value + velocity * 0.18f
                            val target = listOf(peekPx, openPx, fullPx)
                                .filter { it >= peekPx }
                                .minByOrNull { abs(it - projected) } ?: peekPx
                            sheetOffset.animateTo(
                                target,
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                        },
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                TransportBar(
                    playing = uiState.playing,
                    enabled = ready,
                    onPlayPause = { if (uiState.playing) viewModel.stop() else viewModel.play() },
                    onJumpBack = {
                        viewModel.setLoop(
                            (uiState.loopStartMs - uiState.loopLengthMs).coerceAtLeast(0),
                            uiState.loopLengthMs,
                        )
                    },
                    onJumpForward = {
                        val maxStart = (uiState.songDurationMs - uiState.loopLengthMs).coerceAtLeast(0)
                        viewModel.setLoop(
                            (uiState.loopStartMs + uiState.loopLengthMs).coerceAtMost(maxStart),
                            uiState.loopLengthMs,
                        )
                    },
                )
                Text(
                    text = if (uiState.loading) {
                        if (song?.contentUriString?.startsWith("spotify://") == true) {
                            "Streaming this one — hang on…"
                        } else {
                            "Getting the audio ready…"
                        }
                    } else {
                        "Looping ${formatClock(uiState.loopStartMs)} – " +
                            "${formatClock(uiState.loopStartMs + uiState.loopLengthMs)} " +
                            "of ${formatClock(uiState.songDurationMs)}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Grabber(Modifier.align(Alignment.CenterHorizontally))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SheetSection("Which bit") {
                        LoopStrip(
                            peaks = uiState.waveform,
                            motion = motion,
                            loopStartMs = uiState.loopStartMs,
                            loopLengthMs = uiState.loopLengthMs,
                            songDurationMs = uiState.songDurationMs,
                            onLoopChanged = viewModel::setLoop,
                            enabled = ready,
                        )
                    }

                    SheetSection("The parts") {
                        PartChipRow(
                            stems = uiState.stems,
                            motion = motion,
                            onMute = viewModel::onStemMuteToggled,
                            onSolo = viewModel::onStemSoloToggled,
                            onNudge = viewModel::nudgeStem,
                        )
                        if (!uiState.separated && uiState.stems.size > 1) {
                            Text(
                                "Not split yet. \"Middle\" is mostly the singer and the bass; " +
                                    "\"Sides\" is everything spread out wide.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (uiState.stems.size == 1) {
                            Text(
                                "This song is mono, so there's only one piece to move. " +
                                    "Splitting still works.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SplitBlock(uiState = uiState, viewModel = viewModel)
                    }

                    SheetSection("Sound") {
                        RoomRail(
                            label = "Speed",
                            valueText = speedLabel(uiState.rate),
                            fraction = { (uiState.rate - 0.6f) / 0.8f },
                            onFractionChange = { viewModel.setRate(0.6f + it * 0.8f) },
                            detent = 0.5f,
                            enabled = ready,
                        )
                        RoomSegments(
                            options = listOf(
                                "lp" to "Cut the highs",
                                "bp" to "Keep the middle",
                                "hp" to "Cut the lows",
                            ),
                            selectedId = uiState.filterMode,
                            onSelected = { viewModel.setFilter(uiState.cutoffHz, uiState.resonance, it) },
                            enabled = ready,
                        )
                        TonePad(
                            cutoffHz = uiState.cutoffHz,
                            resonance = uiState.resonance,
                            mode = uiState.filterMode,
                            onChange = { hz, q -> viewModel.setFilter(hz, q, uiState.filterMode) },
                            enabled = ready,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RoomToggle(
                                label = "Fuzz",
                                caption = "Coarser steps. Like an old sampler.",
                                checked = uiState.bits < 16,
                                onCheckedChange = {
                                    viewModel.setLoFi(if (it) 8 else 16, uiState.decim)
                                },
                                enabled = ready,
                                modifier = Modifier.weight(1f),
                            )
                            RoomToggle(
                                label = "Stutter",
                                caption = "Skips samples. Like a scratched CD.",
                                checked = uiState.decim > 1,
                                onCheckedChange = {
                                    viewModel.setLoFi(uiState.bits, if (it) 4 else 1)
                                },
                                enabled = ready,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        RoomRail(
                            label = "Echo amount",
                            valueText = echoAmountLabel(uiState.reverbMix),
                            fraction = { uiState.reverbMix },
                            onFractionChange = { viewModel.setReverb(it, uiState.rt60) },
                            enabled = ready,
                        )
                        RoomRail(
                            label = "Echo length",
                            valueText = "%.1f s".format(uiState.rt60),
                            fraction = { ((uiState.rt60 - 0.3f) / 5.7f).coerceIn(0f, 1f) },
                            onFractionChange = { viewModel.setReverb(uiState.reverbMix, 0.3f + it * 5.7f) },
                            enabled = ready,
                        )
                    }

                    SheetSection("Listening") {
                        RoomSegments(
                            options = buildList {
                                add("device" to "Phone turns it")
                                add("manual" to "You turn it")
                                if (uiState.headTrackerAvailable) add("headset" to "Earbuds turn it")
                            },
                            selectedId = uiState.poseSourceId,
                            onSelected = viewModel::setPoseSource,
                            enabled = ready,
                        )
                        FilledTonalButton(
                            onClick = viewModel::recentrePose,
                            shape = ShapeCache.smooth20,
                        ) { Text("Face forward") }
                    }

                    SheetSection("More") {
                        RoomRail(
                            label = "Fade out each loop",
                            valueText = if (uiState.decayMs == 0) "Off" else "${uiState.decayMs} ms",
                            fraction = { uiState.decayMs / 300f },
                            onFractionChange = { viewModel.setDecay((it * 300f).toInt()) },
                            enabled = ready,
                        )
                        Text(
                            "Remix takes over the sound while you're here. " +
                                "Your music comes back when you leave.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────── pieces

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

/** A chevron, not a grey pill: it says which way it goes. */
@Composable
private fun Grabber(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .height(20.dp)
            .fillMaxWidth()
            .drawWithCache {
                val stroke = 5.dp.toPx()
                val half = 22.dp.toPx()
                val color = colors.onSurfaceVariant.copy(alpha = 0.45f)
                onDrawBehind {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    drawLine(color, Offset(cx - half, cy), Offset(cx, cy), stroke, StrokeCap.Round)
                    drawLine(color, Offset(cx, cy), Offset(cx + half, cy), stroke, StrokeCap.Round)
                }
            },
    )
}

@Composable
private fun StageHud(
    title: String,
    loading: Boolean,
    onBack: () -> Unit,
    onFaceForward: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Text(
                "REMIX STUDIO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                title.ifBlank { "Nothing playing" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        // A real button, in a fixed place, for the one action that used to be a chip buried
        // inside a row of radio options.
        IconButton(onClick = onFaceForward) {
            Icon(
                Icons.Rounded.Explore,
                contentDescription = "Face forward",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EmptyRoom(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Nothing playing",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Remix works on whatever you're listening to. Start a song and come back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPick, shape = ShapeCache.smooth28) { Text("Pick a song") }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShapeCache.smooth20)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Button(onClick = onRetry, shape = ShapeCache.smooth20) { Text("Try again") }
    }
}

/**
 * One block, four states, and the copy is generated from the same flags that decide the button —
 * so the screen can never again tell you to split a song while hiding the control that does it,
 * or offer a button that the worker will refuse a second later.
 */
@Composable
private fun SplitBlock(
    uiState: com.theveloper.pixelplay.presentation.viewmodel.RemixUiState,
    viewModel: RemixStudioViewModel,
) {
    val colors = MaterialTheme.colorScheme
    var setupOpen by remember { mutableStateOf(false) }
    var tokenDraft by remember { mutableStateOf("") }

    val configured = uiState.backendTokenSet && uiState.uploadConsent

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            uiState.separating -> {
                RoomRail(
                    label = "Splitting",
                    valueText = "${uiState.separationPercent}%",
                    fraction = { uiState.separationPercent / 100f },
                    onFractionChange = {},
                    enabled = false,
                )
                uiState.separationDetail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = viewModel::cancelSeparation) { Text("Cancel") }
            }

            !uiState.canSeparate -> {
                Button(onClick = {}, enabled = false, shape = ShapeCache.smooth20) {
                    Text("Split it")
                }
                Text(
                    "Download this song first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            !configured -> {
                Text(
                    "Split this song into 4 parts",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Vocals, drums, bass and everything else — as four pieces you can move around " +
                        "the room. Splitting is too heavy for a phone, so it runs on a rented " +
                        "computer. You pay for it with your own Runpod key, and only the seconds " +
                        "you're looping ever leave your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                if (!setupOpen) {
                    Button(onClick = { setupOpen = true }, shape = ShapeCache.smooth20) {
                        Text("Set it up")
                    }
                } else {
                    OutlinedTextField(
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        label = {
                            Text(if (uiState.backendTokenSet) "Runpod key (saved)" else "Runpod key")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Send audio to the splitting server", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Only the looped seconds, only when you tap Split.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = uiState.uploadConsent,
                            onCheckedChange = viewModel::setUploadConsent,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            if (tokenDraft.isNotBlank()) {
                                viewModel.setBackendToken(tokenDraft)
                                tokenDraft = ""
                            }
                        },
                        shape = ShapeCache.smooth20,
                    ) { Text("Save key") }
                }
            }

            else -> {
                Button(onClick = viewModel::separateIntoStems, shape = ShapeCache.smooth20) {
                    Text("Split it")
                }
                Text(
                    "Sends only the ${uiState.loopLengthMs / 1000} seconds you're looping. " +
                        "Takes about a minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                uiState.separationDetail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────── labels

private fun speedLabel(rate: Float): String = when {
    abs(rate - 1f) < 0.005f -> "Normal"
    rate < 1f -> "Slower · %.2f×".format(rate)
    else -> "Faster · %.2f×".format(rate)
}

private fun echoAmountLabel(mix: Float): String = when {
    mix < 0.03f -> "None"
    mix < 0.35f -> "A little · ${(mix * 100).toInt()}%"
    mix < 0.7f -> "Half · ${(mix * 100).toInt()}%"
    else -> "Drenched · ${(mix * 100).toInt()}%"
}

private fun formatClock(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}


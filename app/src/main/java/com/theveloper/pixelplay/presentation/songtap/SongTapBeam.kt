package com.theveloper.pixelplay.presentation.songtap

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.songtap.SongTapCard
import com.theveloper.pixelplay.songtap.BeamFrameBuffer
import com.theveloper.pixelplay.songtap.HapticCue
import com.theveloper.pixelplay.songtap.Phase
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SongTapHaptics
import com.theveloper.pixelplay.songtap.SongTapTimeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The beam: what Song Tap looks like when two phones touch.
 *
 * The motion is the pure timeline in the core (SongTapTimeline.frame, in permille): there is no animation state
 * here, only one clock. A single Animatable counts milliseconds from the beam's T0 and every layer reads it
 * inside its graphicsLayer or draw block, so a frame costs a few integer operations and no recomposition,
 * layout, allocation or blur. The glow is one radial Brush, built once per size, drawn with an alpha.
 */

/** The clock's resting value: no beam is playing. */
private const val NOT_PLAYING = -1f

/** Always runs at real speed: the reduced-motion path is a 150 ms cross-fade, which should not be switched off with the rest. */
private val RealSpeed = object : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/**
 * Milliseconds since the beam's T0 (the moment this phone committed, the first haptic: spec 5 has no `go` and no delay), or [NOT_PLAYING] (-1).
 * A beam whose T0 is still ahead waits; one that already started joins part-way. The beam's haptic
 * cues are fired from here, on the same clock, and skipped when they are already long past.
 */
@Composable
fun rememberBeamTime(role: Role?, startMs: Long, reduced: Boolean, onCue: (HapticCue) -> Unit): State<Float> {
    val time = remember { Animatable(NOT_PLAYING) }
    val cue by rememberUpdatedState(onCue)
    LaunchedEffect(role, startMs, reduced) {
        if (role == null) {
            time.snapTo(NOT_PLAYING)
            return@LaunchedEffect
        }
        val total = SongTapTimeline.totalMs(role, reduced)
        for (timed in SongTapHaptics.cues(role, reduced)) {
            launch {
                val wait = startMs + timed.tMs - System.currentTimeMillis()
                if (wait < -PAST_CUE_MS) return@launch
                if (wait > 0L) delay(wait)
                if (isActive) cue(timed.cue)
            }
        }
        val wait = startMs - System.currentTimeMillis()
        if (wait > 0L) delay(wait)
        val elapsed = (System.currentTimeMillis() - startMs).coerceIn(0L, total)
        time.snapTo(elapsed.toFloat())
        withContext(RealSpeed) {
            time.animateTo(total.toFloat(), tween((total - elapsed).toInt().coerceAtLeast(1), easing = LinearEasing))
        }
        time.snapTo(NOT_PLAYING)
    }
    return time.asState()
}

private const val PAST_CUE_MS = 120L

/**
 * The glow at the top edge of the screen. Its strength is the larger of the approach (from the smoothed signal of
 * the phone coming closer, [approach] 0..500) and the beam's own glow, plus a short pulse at the moment of contact.
 */
@Composable
fun SongTapGlow(
    approach: State<Int>,
    time: State<Float>,
    role: Role?,
    reduced: Boolean,
    pulse: State<Float>,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    val frame = remember { BeamFrameBuffer() }
    val smoothed = remember { Animatable(0f) }
    LaunchedEffect(approach) {
        snapshotFlow { approach.value }.collectLatest { smoothed.animateTo(it.toFloat(), tween(GLOW_EASE_MS, easing = LinearEasing)) }
    }
    val drawer = remember(color, role, reduced) {
        Modifier.drawWithCache {
            val brush = Brush.radialGradient(
                colors = listOf(color, color.copy(alpha = 0f)),
                center = Offset(size.width / 2f, 0f),
                radius = size.width * GLOW_RADIUS,
            )
            onDrawBehind {
                var strength = smoothed.value
                val t = time.value
                if (role != null && t >= 0f) {
                    SongTapTimeline.frameInto(role, t.toLong(), reduced, frame)
                    if (frame.glow > strength) strength = frame.glow.toFloat()
                }
                strength += pulse.value * PULSE_PERMILLE
                if (strength > 1f) drawRect(brush, alpha = (strength / 1000f).coerceAtMost(MAX_GLOW_ALPHA))
            }
        }
    }
    Box(modifier.fillMaxSize().then(drawer))
}

private const val GLOW_EASE_MS = 120
private const val GLOW_RADIUS = 0.95f
private const val PULSE_PERMILLE = 280f
private const val MAX_GLOW_ALPHA = 0.7f

/**
 * The sender's artwork: it lifts, shrinks to 40 percent and leaves through the top, then comes back (the first
 * 1.5 s of [SongTapTimeline]). At rest it is just the picture. [rootHeight] is the height of the screen it flies across.
 */
@Composable
fun BeamArtwork(
    url: String?,
    role: Role?,
    time: State<Float>,
    reduced: Boolean,
    rootHeight: IntState,
    modifier: Modifier = Modifier,
) {
    val frame = remember { BeamFrameBuffer() }
    val shape = remember { RoundedCornerShape(28.dp) }
    Box(
        modifier = modifier
            .graphicsLayer {
                val t = time.value
                if (role == Role.SEND && t >= 0f) {
                    SongTapTimeline.frameInto(Role.SEND, t.toLong(), reduced, frame)
                    val s = frame.scale / 1000f
                    scaleX = s
                    scaleY = s
                    translationY = frame.translateY / 1000f * rootHeight.intValue
                    alpha = frame.alpha / 1000f
                    shadowElevation = frame.shadow / 1000f * 24.dp.toPx()
                }
                this.shape = shape
                clip = true
            }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.songtap_beam_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
        }
    }
}

/**
 * The receiver's card: the artwork enters from the top at 40 percent and springs into a card, then the title,
 * the line "Added to your library" and the Undo chip fade in (the receiver half of [SongTapTimeline]). It rests
 * for six seconds with Undo, then slides away.
 */
@Composable
fun ReceiveBeamCard(
    card: SongTapCard,
    phase: Phase,
    alreadyHad: Boolean,
    time: State<Float>,
    reduced: Boolean,
    rootHeight: IntState,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    /** The sender's name ("From X"), when it is known. */
    from: String? = null,
) {
    val frame = remember { BeamFrameBuffer() }
    val shape = remember { RoundedCornerShape(32.dp) }
    val artShape = remember { RoundedCornerShape(22.dp) }
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val canUndo = phase == Phase.SAVED && !alreadyHad
    Box(
        modifier = modifier
            .widthIn(max = 320.dp)
            .graphicsLayer {
                val t = time.value
                SongTapTimeline.frameInto(Role.RECEIVE, if (t < 0f) 0L else t.toLong(), reduced, frame)
                val s = frame.scale / 1000f
                scaleX = s
                scaleY = s
                translationY = frame.translateY / 1000f * rootHeight.intValue
                alpha = frame.alpha / 1000f
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    SongTapTimeline.frameInto(Role.RECEIVE, time.value.coerceAtLeast(0f).toLong(), reduced, frame)
                    alpha = frame.cardAlpha / 1000f
                    this.shape = shape
                    clip = true
                    shadowElevation = 8.dp.toPx()
                }
                .background(container),
        )
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(132.dp)
                    .graphicsLayer { this.shape = artShape; clip = true }
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (card.artUrl != null) {
                    AsyncImage(model = card.artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                }
            }
            Column(
                Modifier.graphicsLayer {
                    SongTapTimeline.frameInto(Role.RECEIVE, time.value.coerceAtLeast(0f).toLong(), reduced, frame)
                    alpha = frame.textAlpha / 1000f
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (from != null) {
                    Text(stringResource(R.string.songtap_from, from), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    card.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        when {
                            phase == Phase.SAVED && alreadyHad -> R.string.songtap_card_already
                            phase == Phase.SAVED -> R.string.songtap_card_saved
                            else -> R.string.songtap_card_adding
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Box(
                Modifier.graphicsLayer {
                    SongTapTimeline.frameInto(Role.RECEIVE, time.value.coerceAtLeast(0f).toLong(), reduced, frame)
                    alpha = frame.undoAlpha / 1000f
                },
            ) {
                AssistChip(
                    onClick = { if (time.value >= SongTapTimeline.RECEIVE_UNDO_START_MS) onUndo() },
                    label = { Text(stringResource(R.string.songtap_card_undo)) },
                    enabled = canUndo,
                )
            }
        }
    }
}

// ── Previews: the beam at fixed moments ──────────────────────────────────────────────

private fun previewCard() = SongTapCard(videoId = "dQw4w9WgXcQ", songId = "id", title = "Never Gonna Give You Up", artist = "Rick Astley", artUrl = null)

@Preview(name = "Receiver card, resting", widthDp = 360, heightDp = 640)
@Composable
private fun ReceiveBeamCardResting() {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            ReceiveBeamCard(
                card = previewCard(), phase = Phase.SAVED, alreadyHad = false,
                time = remember { mutableFloatStateOf(2_000f) }, reduced = false,
                rootHeight = remember { mutableIntStateOf(640) }, onUndo = {},
            )
        }
    }
}

@Preview(name = "Receiver card, entering", widthDp = 360, heightDp = 640)
@Composable
private fun ReceiveBeamCardEntering() {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            ReceiveBeamCard(
                card = previewCard(), phase = Phase.TRANSFERRING, alreadyHad = false,
                time = remember { mutableFloatStateOf(300f) }, reduced = false,
                rootHeight = remember { mutableIntStateOf(640) }, onUndo = {},
            )
        }
    }
}

@Preview(name = "Sender artwork, lifted", widthDp = 360, heightDp = 640)
@Composable
private fun BeamArtworkLifted() {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            BeamArtwork(
                url = null, role = Role.SEND, time = remember { mutableFloatStateOf(180f) }, reduced = false,
                rootHeight = remember { mutableIntStateOf(640) }, modifier = Modifier.size(240.dp),
            )
        }
    }
}

@Preview(name = "Glow", widthDp = 360, heightDp = 640)
@Composable
private fun GlowPreview() {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            SongTapGlow(
                approach = remember { mutableIntStateOf(450) }, time = remember { mutableFloatStateOf(-1f) },
                role = null, reduced = false, pulse = remember { mutableFloatStateOf(0f) },
            )
        }
    }
}

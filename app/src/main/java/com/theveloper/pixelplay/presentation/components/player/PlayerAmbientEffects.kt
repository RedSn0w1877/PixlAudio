package com.theveloper.pixelplay.presentation.components.player

import android.media.audiofx.Visualizer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.data.preferences.PlayerAmbientStyle
import com.theveloper.pixelplay.presentation.components.SmartImage
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import timber.log.Timber

/**
 * The player's ambient background — exclusive styles, not layered: [style] picks exactly one
 * of blended/blurred album art, a slow flowing gradient, a low-poly moving mesh, or an
 * audio-reactive waveform. Every style here is a `drawBehind`/`Canvas`-only effect (no extra
 * composition, no double-draw risk), so switching styles is cheap either way.
 *
 * See `UserPreferencesRepository.playerAmbientStyleFlow` for the persisted choice.
 */
@Composable
fun PlayerAmbientBackground(
    style: PlayerAmbientStyle,
    albumArtUri: String?,
    colorScheme: ColorScheme,
    audioSessionIdProvider: () -> Int,
    isPlayingProvider: () -> Boolean,
    modifier: Modifier = Modifier
) {
    when (style) {
        PlayerAmbientStyle.OFF -> Unit
        PlayerAmbientStyle.BLENDED_COVER -> {
            SmartImage(
                model = albumArtUri,
                contentDescription = null,
                modifier = modifier
                    .fillMaxSize()
                    .blur(72.dp)
                    .graphicsLayer { alpha = 0.5f },
                targetSize = coil.size.Size(200, 200),
                allowHardware = false
            )
        }
        PlayerAmbientStyle.FLOWING_GRADIENT -> {
            PlayerFlowingGradient(colorScheme = colorScheme, modifier = modifier.fillMaxSize())
        }
        PlayerAmbientStyle.LOW_POLY_MESH -> {
            PlayerLowPolyMesh(colorScheme = colorScheme, modifier = modifier.fillMaxSize())
        }
        PlayerAmbientStyle.AUDIO_WAVEFORM -> {
            PlayerAudioWaveform(
                audioSessionIdProvider = audioSessionIdProvider,
                isPlayingProvider = isPlayingProvider,
                colorScheme = colorScheme,
                modifier = modifier.fillMaxSize()
            )
        }
    }
}

/**
 * A slow-moving multi-color gradient built from the current Material scheme's own roles — no
 * separate palette extraction needed, this just re-uses what [ColorSchemeProcessor] already
 * derived from the album art. Two radial blobs drift in a loose Lissajous path; period is
 * measured in tens of seconds on purpose so it reads as "ambient", not as an obvious loop.
 */
@Composable
private fun PlayerFlowingGradient(
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PlayerFlowingGradient")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 50_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    // Alpha roughly doubled and a mid-gradient stop added per blob (instead of a single
    // color->transparent fade) so each one reads as a solid, saturated blob with a soft edge,
    // rather than a faint halo — that softness was the "not visible/noticeable enough" gap.
    // Three colors instead of two for a fuller "flowing colors" look rather than two dots.
    val colorA = colorScheme.primary.copy(alpha = 0.55f)
    val colorB = colorScheme.tertiary.copy(alpha = 0.50f)
    val colorC = colorScheme.secondary.copy(alpha = 0.42f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val angleA = time * 2f * Math.PI.toFloat()
        val angleB = time * 2f * Math.PI.toFloat() * 0.63f + 1.7f
        val angleC = time * 2f * Math.PI.toFloat() * 0.47f + 3.4f

        val centerA = Offset(
            x = w * (0.5f + 0.32f * cos(angleA)),
            y = h * (0.32f + 0.22f * sin(angleA * 1.3f))
        )
        val centerB = Offset(
            x = w * (0.5f + 0.30f * cos(angleB + Math.PI.toFloat())),
            y = h * (0.68f + 0.20f * sin(angleB))
        )
        val centerC = Offset(
            x = w * (0.5f + 0.26f * cos(angleC + 2.1f)),
            y = h * (0.5f + 0.26f * sin(angleC))
        )
        // Blobs cover more of the frame — larger and more overlap is what turns three
        // separate glows into one continuous field of moving color.
        val radius = min(w, h) * 0.78f

        fun blob(color: Color, center: Offset) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to color,
                        0.55f to color.copy(alpha = color.alpha * 0.7f),
                        1f to Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }

        blob(colorC, centerC)
        blob(colorB, centerB)
        blob(colorA, centerA)
    }
}

private data class MeshPoint(val phaseX: Float, val phaseY: Float, val speed: Float, val baseX: Float, val baseY: Float)

/**
 * A sparse low-poly "constellation": a handful of points drifting slowly, connected by thin
 * lines when they're close enough. Point positions are computed straight from the animated
 * time value inside the draw call — no per-point state, so the whole thing is one
 * [Canvas]/`drawBehind` allocation.
 */
@Composable
private fun PlayerLowPolyMesh(
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    val points = remember {
        val random = kotlin.random.Random(1337)
        List(11) {
            MeshPoint(
                phaseX = random.nextFloat() * 6.28f,
                phaseY = random.nextFloat() * 6.28f,
                speed = 0.6f + random.nextFloat() * 0.8f,
                baseX = random.nextFloat(),
                baseY = random.nextFloat()
            )
        }
    }
    val infiniteTransition = rememberInfiniteTransition(label = "PlayerLowPolyMesh")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "meshTime"
    )
    val lineColor = colorScheme.tertiary.copy(alpha = 0.18f)
    val dotColor = colorScheme.tertiaryContainer.copy(alpha = 0.35f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val t = time * 2f * Math.PI.toFloat()
        val positions = points.map { p ->
            Offset(
                x = w * (p.baseX + 0.06f * cos(t * p.speed + p.phaseX)).coerceIn(0f, 1f),
                y = h * (p.baseY + 0.06f * sin(t * p.speed + p.phaseY)).coerceIn(0f, 1f)
            )
        }
        val connectDistance = min(w, h) * 0.28f
        for (i in positions.indices) {
            for (j in i + 1 until positions.size) {
                val a = positions[i]
                val b = positions[j]
                val dx = a.x - b.x
                val dy = a.y - b.y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist < connectDistance) {
                    drawLine(
                        color = lineColor,
                        start = a,
                        end = b,
                        strokeWidth = 1.dp.toPx(),
                        alpha = 1f - (dist / connectDistance)
                    )
                }
            }
        }
        positions.forEach { p ->
            drawCircle(color = dotColor, radius = 2.5.dp.toPx(), center = p)
        }
    }
}

/**
 * A gradient-colored waveform driven by real playback audio via [Visualizer], attached to the
 * currently active ExoPlayer's audio session ([DualPlayerEngine.getAudioSessionId]).
 *
 * No `RECORD_AUDIO` permission is needed: [Visualizer] only requires it when capturing session
 * 0 (the global output mix) — attaching to a session this app itself owns (its own player) is
 * always allowed. Falls back to a flat idle line if the session isn't ready yet or capture
 * fails, rather than crashing the player.
 */
@Composable
private fun PlayerAudioWaveform(
    audioSessionIdProvider: () -> Int,
    isPlayingProvider: () -> Boolean,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    var waveform by remember { mutableStateOf(FloatArray(48) { 0f }) }
    val sessionId = audioSessionIdProvider()

    DisposableEffect(sessionId) {
        if (sessionId == 0) {
            return@DisposableEffect onDispose { }
        }
        val visualizer = runCatching {
            Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(viz: Visualizer?, bytes: ByteArray?, samplingRate: Int) {
                            if (bytes == null || bytes.isEmpty()) return
                            val bucketCount = 48
                            val bucketSize = (bytes.size / bucketCount).coerceAtLeast(1)
                            val next = FloatArray(bucketCount)
                            for (i in 0 until bucketCount) {
                                val start = i * bucketSize
                                if (start >= bytes.size) break
                                // 8-bit unsigned PCM centered at 128; normalize to 0..1 amplitude.
                                val sample = bytes[start].toInt() and 0xFF
                                next[i] = kotlin.math.abs(sample - 128) / 128f
                            }
                            waveform = next
                        }

                        override fun onFftDataCapture(viz: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            // Unused — waveform (time-domain) is enough for a bar visualizer.
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,
                    false
                )
                enabled = true
            }
        }.onFailure { Timber.w(it, "PlayerAudioWaveform: couldn't attach Visualizer to session $sessionId") }
            .getOrNull()

        onDispose {
            runCatching {
                visualizer?.enabled = false
                visualizer?.release()
            }
        }
    }

    // Decays the last captured frame toward silence while paused, instead of freezing mid-bar.
    val isPlaying = isPlayingProvider()
    val displayedWaveform = if (isPlaying) waveform else FloatArray(waveform.size) { waveform[it] * 0.3f }

    val barColorTop = colorScheme.primary.copy(alpha = 0.35f)
    val barColorBottom = colorScheme.tertiary.copy(alpha = 0.12f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bars = displayedWaveform.size
        if (bars == 0) return@Canvas
        val barWidth = w / bars
        val baseline = h * 0.92f
        val maxBarHeight = h * 0.22f

        for (i in 0 until bars) {
            val amplitude = displayedWaveform[i].coerceIn(0f, 1f)
            val barHeight = maxBarHeight * amplitude
            val x = i * barWidth + barWidth / 2f
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(barColorTop, barColorBottom),
                    startY = baseline - barHeight,
                    endY = baseline
                ),
                start = Offset(x, baseline),
                end = Offset(x, baseline - barHeight),
                strokeWidth = (barWidth * 0.55f).coerceAtLeast(1f),
                cap = StrokeCap.Round
            )
        }
    }
}

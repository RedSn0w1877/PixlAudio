@file:kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.size.Size
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.delay
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.theveloper.pixelplay.ui.theme.QuantizedCornerShapeCache
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.toShape
import androidx.compose.ui.graphics.StrokeCap

internal val LocalMaterialTheme = compositionLocalOf<ColorScheme> { error("No ColorScheme provided") }

val MiniPlayerHeight = 64.dp
const val ANIMATION_DURATION_MS = 255
val MiniPlayerBottomSpacer = 8.dp

@Composable
fun getNavigationBarHeight(): Dp {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    return sanitizeNavigationBarBottomInset(insets.calculateBottomPadding())
}

/**
 * The mini player (new UI): a floating pill above the split navigation bar. Cover (an expressive
 * cookie shape), title and artist, then Previous, a 48 dp play/pause button and Next. The mockup
 * dropped Previous, but swipe-to-skip is off by default (the swipe dismisses instead), so without
 * the button a sighted user had no way back a track from the mini player.
 *
 * Progress (redesign 2, owner: "make it a thin line near the bottom"): a 3 dp rounded line,
 * [MiniLineInset] in from each side and [MiniLineBottom] above the pill's bottom edge, track at
 * 16 % onPrimaryContainer, progress in primary. It replaced the wavy ring around play.
 *
 * Performance: [progressProvider] is read only in the line's draw (the caller updates it a few
 * times a second, not per frame), so a progress tick redraws the line and nothing else; nothing
 * is allocated while drawing. The play/pause corner morph is read in the layer, through a
 * quantized shape cache.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MiniPlayerContentInternal(
    song: Song,
    isPlaying: Boolean,
    isCastConnecting: Boolean,
    isPreparingPlayback: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    canScroll: Boolean = true,
    /** Spotify Connect: the device playing the queue ("Playing on <device>" in place of the artist line). */
    remoteDeviceName: String? = null,
    /** 0..1, read in the progress line's draw only. */
    progressProvider: () -> Float = { 0f },
) {
    val hapticFeedback = LocalHapticFeedback.current
    // Only the cast hand-over locks the buttons. "Preparing playback" used to as well, and with every
    // song now a Spotify stream that waits for a YouTube match, so pause, skip and previous were
    // dead for the whole load (and for good when a song failed). Pausing a loading song is valid.
    val controlsEnabled = !isCastConnecting
    val scheme = LocalMaterialTheme.current

    val playPauseInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    val previousInteraction = remember { MutableInteractionSource() }
    val miniPlayerIndication = remember { ripple(bounded = false) }

    val lineTrackColor = scheme.onPrimaryContainer.copy(alpha = 0.16f)
    val lineColor = scheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .drawBehind {
                // The thin progress line near the bottom of the pill; progress read here only.
                val stroke = MiniLineHeight.toPx()
                val inset = MiniLineInset.toPx()
                val y = size.height - MiniLineBottom.toPx() - stroke / 2f
                val start = inset
                val end = size.width - inset
                if (end <= start) return@drawBehind
                drawLine(lineTrackColor, Offset(start, y), Offset(end, y), stroke, StrokeCap.Round)
                val p = progressProvider().coerceIn(0f, 1f)
                if (p > 0f) {
                    drawLine(lineColor, Offset(start, y), Offset(start + (end - start) * p, y), stroke, StrokeCap.Round)
                }
            }
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val albumArtModel = song.albumArtUriString?.takeIf { it.isNotBlank() }
        val artShape = MaterialShapes.Cookie9Sided.toShape()
        Box(contentAlignment = Alignment.Center) {
            key(song.id) {
                SmartImage(
                    model = albumArtModel,
                    contentDescription = "Carátula de ${song.title}",
                    shape = artShape,
                    targetSize = Size(150, 150),
                    modifier = Modifier.size(48.dp),
                    placeholderModel = if (albumArtModel?.startsWith("telegram_art") == true) {
                        "$albumArtModel?quality=thumb"
                    } else null
                )
            }
            if (isCastConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = scheme.onPrimaryContainer
                )
            } else if (isPreparingPlayback) {
                CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            val titleStyle = MaterialTheme.typography.titleSmall.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                fontFamily = GoogleSansRounded,
                color = scheme.onPrimaryContainer
            )
            val artistStyle = MaterialTheme.typography.bodySmall.copy(
                fontSize = 13.sp,
                letterSpacing = 0.sp,
                fontFamily = GoogleSansRounded,
                color = scheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            AutoScrollingText(
                text = when {
                    isCastConnecting -> "Connecting to device…"
                    isPreparingPlayback -> "Preparing playback…"
                    else -> song.title
                },
                style = titleStyle,
                gradientEdgeColor = scheme.primaryContainer,
                canScroll = canScroll
            )
            if (remoteDeviceName != null && !isPreparingPlayback) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Speaker,
                        contentDescription = null,
                        tint = artistStyle.color,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    AutoScrollingText(
                        text = "Playing on $remoteDeviceName",
                        style = artistStyle,
                        gradientEdgeColor = scheme.primaryContainer,
                        canScroll = canScroll
                    )
                }
            } else {
                AutoScrollingText(
                    text = if (isPreparingPlayback) "Loading audio…" else song.displayArtist,
                    style = artistStyle,
                    gradientEdgeColor = scheme.primaryContainer,
                    canScroll = canScroll
                )
            }
        }
        Spacer(modifier = Modifier.width(2.dp))

        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 48.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = previousInteraction,
                    indication = miniPlayerIndication,
                    enabled = controlsEnabled
                ) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPrevious()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = "Anterior",
                tint = scheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
        }

        // Play / pause: 48 dp, 16 dp corners while playing, a circle while paused.
        Box(
            modifier = Modifier.size(MiniPlayButtonSize),
            contentAlignment = Alignment.Center
        ) {
            val cornerShapes = remember { QuantizedCornerShapeCache() }
            val playCorner = animateDpAsState(
                targetValue = if (isPlaying) 16.dp else MiniPlayButtonSize / 2,
                animationSpec = remember { MotionScheme.expressive().fastSpatialSpec<Dp>() },
                label = "miniPlayCorner"
            )
            Box(
                modifier = Modifier
                    .size(MiniPlayButtonSize)
                    .graphicsLayer {
                        shape = cornerShapes.get(playCorner.value)
                        clip = true
                    }
                    .background(scheme.primary)
                    .clickable(
                        interactionSource = playPauseInteraction,
                        indication = miniPlayerIndication,
                        enabled = controlsEnabled
                    ) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPlayPause()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 48.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = nextInteraction,
                    indication = miniPlayerIndication,
                    enabled = controlsEnabled
                ) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNext()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "Siguiente",
                tint = scheme.onPrimaryContainer,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

private val MiniPlayButtonSize = 48.dp
/** Progress line: 3 dp thick, 24 dp in from each end (clear of the 32 dp pill corners), 5 dp up. */
private val MiniLineHeight = 3.dp
private val MiniLineInset = 24.dp
private val MiniLineBottom = 5.dp

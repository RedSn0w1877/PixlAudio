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
import androidx.compose.foundation.layout.fillMaxSize
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
import com.theveloper.pixelplay.ui.glass.components.GlassIcon
import com.theveloper.pixelplay.ui.glass.components.GlassPanel
import com.theveloper.pixelplay.ui.glass.components.GlassText
import com.theveloper.pixelplay.ui.glass.controls.FillBarTrackDark
import com.theveloper.pixelplay.ui.glass.controls.FillBarTrackLight
import com.theveloper.pixelplay.ui.glass.controls.drawGlassFillBar
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
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
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.theveloper.pixelplay.ui.glass.LocalGlassCapability
import com.theveloper.pixelplay.ui.glass.glassMorphCard
import com.theveloper.pixelplay.ui.theme.QuantizedCornerShapeCache

internal val LocalMaterialTheme = compositionLocalOf<ColorScheme> { error("No ColorScheme provided") }

val MiniPlayerHeight = 64.dp
const val ANIMATION_DURATION_MS = 255
val MiniPlayerBottomSpacer = 8.dp

@Composable
fun getNavigationBarHeight(): Dp {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    return sanitizeNavigationBarBottomInset(insets.calculateBottomPadding())
}

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
    canScroll: Boolean = true
) {
    val hapticFeedback = LocalHapticFeedback.current
    val controlsEnabled = !isCastConnecting && !isPreparingPlayback

    val previousInteraction = remember { MutableInteractionSource() }
    val playPauseInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    val miniPlayerIndication = remember { ripple(bounded = false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val albumArtModel = song.albumArtUriString?.takeIf { it.isNotBlank() }
        Box(contentAlignment = Alignment.Center) {
            key(song.id) {
                SmartImage(
                    model = albumArtModel,
                    contentDescription = "Carátula de ${song.title}",
                    shape = CircleShape,
                    targetSize = Size(150, 150),
                    modifier = Modifier.size(44.dp),
                    placeholderModel = if (albumArtModel?.startsWith("telegram_art") == true) {
                        "$albumArtModel?quality=thumb"
                    } else null
                )
            }
            if (isCastConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = LocalMaterialTheme.current.onPrimaryContainer
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
                color = LocalMaterialTheme.current.onPrimaryContainer
            )
            val artistStyle = MaterialTheme.typography.bodySmall.copy(
                fontSize = 13.sp,
                letterSpacing = 0.sp,
                fontFamily = GoogleSansRounded,
                color = LocalMaterialTheme.current.onPrimaryContainer.copy(alpha = 0.7f)
            )

            AutoScrollingText(
                text = when {
                    isCastConnecting -> "Connecting to device…"
                    isPreparingPlayback -> "Preparing playback…"
                    else -> song.title
                },
                style = titleStyle,
                gradientEdgeColor = LocalMaterialTheme.current.primaryContainer,
                canScroll = canScroll
            )
            AutoScrollingText(
                text = if (isPreparingPlayback) "Loading audio…" else song.displayArtist,
                style = artistStyle,
                gradientEdgeColor = LocalMaterialTheme.current.primaryContainer,
                canScroll = canScroll
            )
        }
        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(LocalMaterialTheme.current.onPrimary)
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
                tint = LocalMaterialTheme.current.primary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(LocalMaterialTheme.current.primary)
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
                tint = LocalMaterialTheme.current.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(LocalMaterialTheme.current.onPrimary)
                .clickable(
                    interactionSource = nextInteraction,
                    indication = miniPlayerIndication,
                    enabled = controlsEnabled
                ) { onNext() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "Siguiente",
                tint = LocalMaterialTheme.current.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}


/** The collapsed glass player card's corner radius: a capsule for the 64 dp mini player. */
internal val GlassMiniPlayerCorner = MiniPlayerHeight / 2

/** NexHome's wide-bar press swell, for the glass mini player capsule. */
internal const val GlassMiniPlayerPressScale = 1.04f

/** Steps the glass card's glass amount is quantised to. */
internal const val GlassCardAmountSteps = 16

/**
 * The glass player card's background, drawn as a SIBLING behind the player content (never around
 * it, see the call site). While [amountState] (1 = collapsed, 0 = expanded, already quantised) is
 * above 0 it is the one glass node of [glassMorphCard]: the mini player's floating capsule fading
 * its glass out. At 0 the glass node detaches and the card is a plain copy of the baked ambient,
 * lined up with the ambient layer and clipped to [clipShape] (no offscreen layer, no RenderEffect),
 * which draws nothing while [coveredByBase] (the same ambient already shows behind it).
 */
@Composable
internal fun GlassPlayerCardBackground(
    modifier: Modifier,
    cornerProvider: () -> Dp,
    clipShape: Shape,
    tint: Color,
    amountState: State<Float>,
    coveredByBase: () -> Boolean,
) {
    val detached by remember(amountState) { derivedStateOf { amountState.value <= 0f } }
    if (!detached) {
        val backdrop = LocalGlassBackdrop.current
        val capability = LocalGlassCapability.current
        val glassModifier = remember(backdrop, capability, tint, cornerProvider, amountState) {
            val shapes = QuantizedCornerShapeCache()
            Modifier.glassMorphCard(
                backdrop = backdrop,
                shape = { shapes.get(cornerProvider()) },
                capability = capability,
                tint = tint,
                glassAmount = { amountState.value },
            )
        }
        Box(modifier.then(glassModifier))
    } else {
        val fillAlpha: () -> Float = remember(coveredByBase) { { if (coveredByBase()) 0f else 1f } }
        Box(
            modifier
                .clip(clipShape)
                .glassAmbientFill(fillAlpha)
        )
    }
}

/**
 * Glass mode's mini player (research-nexhome-design §10.2): the content of NexHome's floating
 * capsule. The capsule itself is drawn behind the player sheet's card ([GlassPlayerCardBackground]),
 * so this draws no background. Art 44 dp at
 * radius 12 (a plain image), title in BodyStrong, artist in Caption Secondary, then two quick orbs:
 * play/pause (lit with the accent at 0.55 while playing) and next (unlit). A 3 dp progress rail
 * sits along the bottom (inset 14, bottom 9) in its own layer.
 *
 * The rail's position is sampled four times a second while playing (once a second while paused,
 * to catch seeks made elsewhere) and only while [progressActive] (the mini player is showing) and
 * the app is started. It is read in draw only.
 */
@Composable
internal fun GlassMiniPlayerContent(
    song: Song,
    isPlaying: Boolean,
    isCastConnecting: Boolean,
    isPreparingPlayback: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    positionProvider: () -> Long,
    durationProvider: () -> Long,
    progressActive: Boolean,
    modifier: Modifier = Modifier,
    /** False while the mini player is invisible: its orbs then sample an empty backdrop. */
    glassLive: Boolean = true,
) {
    val palette = LocalGlassPalette.current
    val orbBackdrop = if (glassLive) LocalGlassBackdrop.current else emptyBackdrop()
    val controlsEnabled = !isCastConnecting && !isPreparingPlayback
    val latestPosition by rememberUpdatedState(positionProvider)
    val latestDuration by rememberUpdatedState(durationProvider)
    val progress = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(song.id, isPlaying, progressActive, lifecycle) {
        if (!progressActive) return@LaunchedEffect
        // Only while the app is on screen: in the background (most of a music app's life) the
        // poll stops at ON_STOP and resumes, refreshed at once, at ON_START.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val duration = latestDuration().coerceAtLeast(1L)
                progress.floatValue = (latestPosition().toFloat() / duration.toFloat()).fastCoerceIn(0f, 1f)
                delay(if (isPlaying) GLASS_MINI_PROGRESS_PLAYING_MS else GLASS_MINI_PROGRESS_PAUSED_MS)
            }
        }
    }
    val railTrack = if (palette.isDark) FillBarTrackDark else FillBarTrackLight
    val railAccent = palette.accent

    Box(modifier = modifier.fillMaxWidth().height(MiniPlayerHeight)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val albumArtModel = song.albumArtUriString?.takeIf { it.isNotBlank() }
            Box(contentAlignment = Alignment.Center) {
                key(song.id) {
                    SmartImage(
                        model = albumArtModel,
                        contentDescription = song.title,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(44.dp),
                    )
                }
                if (isCastConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = palette.primary
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
                GlassText(
                    text = when {
                        isCastConnecting -> "Connecting to device…"
                        isPreparingPlayback -> "Preparing playback…"
                        else -> song.title
                    },
                    style = GlassType.BodyStrong,
                    maxLines = 1
                )
                GlassText(
                    text = if (isPreparingPlayback) "Loading audio…" else song.displayArtist,
                    style = GlassType.Caption,
                    color = palette.secondary,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            GlassQuickOrb(
                lit = isPlaying,
                enabled = controlsEnabled,
                backdrop = orbBackdrop,
                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                onClick = onPlayPause
            ) {
                GlassIcon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    size = 20.dp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            GlassQuickOrb(
                lit = false,
                enabled = controlsEnabled,
                backdrop = orbBackdrop,
                contentDescription = "Siguiente",
                onClick = onNext
            ) {
                GlassIcon(Icons.Rounded.SkipNext, size = 20.dp)
            }
        }
        // The rail, in its own layer: a position update redraws only this.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer()
                .drawBehind {
                    drawGlassFillBar(
                        fraction = progress.floatValue,
                        accent = railAccent,
                        inset = 14.dp,
                        bottom = 9.dp,
                        track = railTrack
                    )
                }
        )
    }
}

/**
 * NexHome's `QuickOrb`: a 40 dp light circular glass panel (tint Black@0.14 in dark, the palette's
 * orb surface in light), lens 12/24 (halved for a light panel), press swell 1.16, emitting the
 * accent while touched. [lit] floods it with the accent at 0.55 in its own surface layer.
 */
@Composable
private fun GlassQuickOrb(
    lit: Boolean,
    enabled: Boolean,
    backdrop: com.kyant.backdrop.Backdrop,
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val haptic = LocalHapticFeedback.current
    val fill = remember { Animatable(if (lit) 1f else 0f, 0.001f) }
    LaunchedEffect(lit) { fill.animateTo(if (lit) 1f else 0f, LiquidMotion.GlowSpring) }
    val accent = palette.accent
    val latestOnClick by rememberUpdatedState(onClick)
    val latestEnabled by rememberUpdatedState(enabled)
    val click = remember(haptic) {
        {
            if (latestEnabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                latestOnClick()
            }
        }
    }
    GlassPanel(
        modifier = Modifier
            .size(40.dp)
            .semantics { this.contentDescription = contentDescription },
        backdrop = backdrop,
        shape = CircleShape,
        tint = if (palette.isDark) GlassQuickOrbTintDark else palette.orbSurface,
        accent = accent,
        onClick = click,
        refractionHeight = 12.dp,
        refractionAmount = 24.dp,
        pressScale = LiquidMotion.ButtonPressScale,
        onDrawSurface = {
            val f = fill.value.fastCoerceIn(0f, 1f)
            if (f > 0.01f) drawRect(accent.copy(alpha = 0.55f * f))
        },
    ) {
        Box(Modifier.align(Alignment.Center)) { content() }
    }
}

private val GlassQuickOrbTintDark = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.14f)
private const val GLASS_MINI_PROGRESS_PLAYING_MS = 250L
private const val GLASS_MINI_PROGRESS_PAUSED_MS = 1_000L

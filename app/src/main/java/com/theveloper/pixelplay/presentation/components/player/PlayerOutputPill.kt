package com.theveloper.pixelplay.presentation.components.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.spotify.connect.SpotifyDeviceKind
import com.theveloper.pixelplay.presentation.components.LocalMaterialTheme
import com.theveloper.pixelplay.presentation.components.spotifyDeviceIcon
import com.theveloper.pixelplay.presentation.viewmodel.AudioOutputCategory
import com.theveloper.pixelplay.presentation.viewmodel.LocalAudioOutput
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.SpotifyConnectChip

/**
 * The full player's top-bar output pill (owner decision 2026-10-07, same as iOS): no "Now
 * Playing" title any more; the pill names where the music plays. The phone's own speaker is
 * icon-only; Bluetooth, USB, wired, HDMI, Cast and Spotify Connect show their name (or what kind
 * of output it is when the system gives no name).
 */
internal enum class PlayerOutputKind { PHONE, BLUETOOTH, WIRED, USB, DIGITAL, OTHER, CAST, SPOTIFY_CONNECT }

@Immutable
internal data class PlayerOutputUi(
    val kind: PlayerOutputKind,
    /** The device's own name; null when there is none (the label then falls back to the kind). */
    val name: String?,
    /** It plays on another device (the dot). */
    val isRemote: Boolean = false,
    /** A Cast route or a Connect session is starting ("Connecting…" and a spinner). */
    val isConnecting: Boolean = false,
    /** The Connect device's type, for its icon. */
    val connectKind: SpotifyDeviceKind? = null,
)

/**
 * One output for the pill, from what plays where. Cast connecting first (it is the user's latest
 * action), then a Connect device before a Cast route (the precedence the pill had before), then
 * where this phone routes its audio.
 */
internal fun resolvePlayerOutput(
    isCastConnecting: Boolean,
    isCastRemoteActive: Boolean,
    castRouteName: String?,
    connect: SpotifyConnectChip?,
    local: LocalAudioOutput,
): PlayerOutputUi = when {
    isCastConnecting -> PlayerOutputUi(PlayerOutputKind.CAST, name = null, isConnecting = true)
    connect != null -> PlayerOutputUi(
        kind = PlayerOutputKind.SPOTIFY_CONNECT,
        name = connect.name,
        isRemote = !connect.connecting,
        isConnecting = connect.connecting,
        connectKind = connect.kind,
    )
    isCastRemoteActive -> PlayerOutputUi(
        PlayerOutputKind.CAST,
        name = castRouteName?.takeIf { it.isNotBlank() },
        isRemote = true,
    )
    else -> when (local.category) {
        AudioOutputCategory.BuiltIn -> PlayerOutputUi(PlayerOutputKind.PHONE, name = null)
        AudioOutputCategory.Bluetooth -> PlayerOutputUi(PlayerOutputKind.BLUETOOTH, local.name)
        AudioOutputCategory.Usb -> PlayerOutputUi(PlayerOutputKind.USB, local.name)
        AudioOutputCategory.Wired -> PlayerOutputUi(PlayerOutputKind.WIRED, local.name)
        AudioOutputCategory.Cast -> PlayerOutputUi(PlayerOutputKind.DIGITAL, local.name)
        AudioOutputCategory.Other -> PlayerOutputUi(PlayerOutputKind.OTHER, local.name)
    }
}

/** The pill's text: null on the phone speaker (icon only), "Connecting…" while starting. */
@Composable
internal fun PlayerOutputUi.label(): String? = when {
    kind == PlayerOutputKind.PHONE -> null
    isConnecting -> stringResource(R.string.player_connecting)
    else -> name ?: stringResource(
        when (kind) {
            PlayerOutputKind.BLUETOOTH -> R.string.settings_devcaps_output_bluetooth
            PlayerOutputKind.USB -> R.string.settings_devcaps_output_usb
            PlayerOutputKind.WIRED -> R.string.settings_devcaps_output_wired
            PlayerOutputKind.DIGITAL -> R.string.settings_devcaps_output_digital
            PlayerOutputKind.CAST, PlayerOutputKind.SPOTIFY_CONNECT -> R.string.player_cd_cast
            PlayerOutputKind.OTHER, PlayerOutputKind.PHONE -> R.string.settings_devcaps_output_other
        }
    )
}

/** What TalkBack reads for the whole pill ("Playing on <device>" / "Playing on this phone"). */
@Composable
internal fun PlayerOutputUi.spokenDescription(label: String?): String = when {
    kind == PlayerOutputKind.PHONE || label == null -> stringResource(R.string.player_cd_output_this_phone)
    isConnecting -> name?.let { stringResource(R.string.player_cd_output_connecting_to, it) }
        ?: stringResource(R.string.player_connecting)
    else -> stringResource(R.string.player_cd_output_playing_on, label)
}

@DrawableRes
private fun iconResFor(kind: PlayerOutputKind): Int = when (kind) {
    PlayerOutputKind.PHONE -> R.drawable.rounded_mobile_speaker_24
    PlayerOutputKind.BLUETOOTH -> R.drawable.rounded_bluetooth_24
    PlayerOutputKind.WIRED, PlayerOutputKind.USB -> R.drawable.rounded_headphones_24
    PlayerOutputKind.DIGITAL -> R.drawable.rounded_tv_24
    PlayerOutputKind.OTHER -> R.drawable.rounded_speaker_24
    PlayerOutputKind.CAST, PlayerOutputKind.SPOTIFY_CONNECT -> R.drawable.rounded_cast_24
}

/** The output's icon; a Connect device shows its own type (speaker, TV, car…), as on iOS. */
@Composable
internal fun PlayerOutputUi.iconPainter(): Painter =
    if (kind == PlayerOutputKind.SPOTIFY_CONNECT) {
        rememberVectorPainter(spotifyDeviceIcon(connectKind ?: SpotifyDeviceKind.UNKNOWN))
    } else {
        painterResource(iconResFor(kind))
    }

/**
 * The full player's top-bar actions in both modes: the output pill and the queue button. It
 * collects its own flows, so an output change (earbuds connecting, a cast route, a Connect
 * device) recomposes only the top bar, never the 3000-line full player around it.
 */
@Composable
internal fun FullPlayerTopActions(
    playerViewModel: PlayerViewModel,
    isCastConnecting: Boolean,
    isExpanded: Boolean,
    glassMode: Boolean,
    accent: Color,
    onAccent: Color,
    onOutputClick: () -> Unit,
    onQueueClick: () -> Unit,
) {
    val isCastRemoteActive by playerViewModel.isRemotePlaybackActive.collectAsStateWithLifecycle()
    val castRouteName by playerViewModel.selectedRouteName.collectAsStateWithLifecycle()
    val connectChip by playerViewModel.spotifyConnect.topBarDevice.collectAsStateWithLifecycle()
    val localOutput by playerViewModel.localAudioOutput.collectAsStateWithLifecycle()
    // The route callbacks can miss an Output Switcher move; opening the player re-reads it.
    LaunchedEffect(isExpanded) {
        if (isExpanded) playerViewModel.refreshLocalAudioOutput()
    }
    val output = resolvePlayerOutput(isCastConnecting, isCastRemoteActive, castRouteName, connectChip, localOutput)
    val label = output.label()
    val spoken = output.spokenDescription(label)

    Row(
        modifier = Modifier.padding(start = PlayerTopBarLeadingReserve, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight(fill = false): the pill hugs its content and only grows into the width the
        // collapse button leaves, where a long name ellipsizes.
        if (glassMode) {
            GlassPlayerOutputPill(output, label, spoken, onOutputClick, Modifier.weight(1f, fill = false))
            GlassPlayerQueueOrb(onQueueClick)
        } else {
            MaterialPlayerOutputPill(output, label, spoken, accent, onAccent, onOutputClick, Modifier.weight(1f, fill = false))
            MaterialPlayerQueueButton(accent, onAccent, onQueueClick)
        }
    }
}

/**
 * TopAppBar (material3 1.5 alphas) measures its actions with the bar's full width, before the
 * title, so a long device name would draw under the collapse button. Reserve the bar's 4 dp start
 * inset, the 56 dp navigation slot and a 12 dp gap. The padding takes no touches, so the collapse
 * button underneath stays tappable.
 */
private val PlayerTopBarLeadingReserve = 72.dp

/**
 * Material 3 mode: the split button's leading half. Icon only it is the 50 dp pill with the
 * 6 dp inner corners next to the queue button; with a name it grows into a full capsule. The
 * corner morph is read in the layer block, so it never recomposes the bar.
 */
@Composable
private fun MaterialPlayerOutputPill(
    output: PlayerOutputUi,
    label: String?,
    spoken: String,
    accent: Color,
    onAccent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showLabel = label != null
    val trailingCornerState = animateDpAsState(
        targetValue = if (showLabel) PillCornerExpanded else PillCornerCompact,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "outputPillCorner"
    )
    Box(
        modifier = modifier
            .height(42.dp)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            .widthIn(min = 50.dp)
            .graphicsLayer {
                val trailing = trailingCornerState.value.coerceAtLeast(0.dp)
                shape = RoundedCornerShape(
                    topStart = PillCornerExpanded,
                    topEnd = trailing,
                    bottomStart = PillCornerExpanded,
                    bottomEnd = trailing
                )
                clip = true
            }
            .background(onAccent.copy(alpha = 0.7f))
            .semantics {
                contentDescription = spoken
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painter = output.iconPainter(), contentDescription = null, tint = accent)
            AnimatedVisibility(visible = showLabel) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    AnimatedContent(
                        targetState = label.orEmpty(),
                        transitionSpec = {
                            fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(120))
                        },
                        label = "outputPillLabel"
                    ) { text ->
                        Row(
                            modifier = Modifier.padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelMedium,
                                color = accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // The pill's description already says it.
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clearAndSetSemantics { }
                            )
                            if (output.isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = accent
                                )
                            } else if (output.isRemote) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(LocalMaterialTheme.current.onTertiaryContainer)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Material 3 mode: the split button's trailing half, 50 × 42 dp. */
@Composable
private fun MaterialPlayerQueueButton(accent: Color, onAccent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(height = 42.dp, width = 50.dp)
            .clip(
                RoundedCornerShape(
                    topStart = PillCornerCompact,
                    topEnd = PillCornerExpanded,
                    bottomStart = PillCornerCompact,
                    bottomEnd = PillCornerExpanded
                )
            )
            .background(onAccent.copy(alpha = 0.7f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.rounded_queue_music_24),
            contentDescription = stringResource(R.string.player_cd_open_queue),
            tint = accent
        )
    }
}

private val PillCornerExpanded = 50.dp
private val PillCornerCompact = 6.dp

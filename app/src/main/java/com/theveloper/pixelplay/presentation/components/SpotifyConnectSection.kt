package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ConnectedTv
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SettingsInputHdmi
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectAvailability
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectDevice
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectUiState
import com.theveloper.pixelplay.data.spotify.connect.SpotifyDeviceKind
import com.theveloper.pixelplay.utils.shapes.RoundedStarShape

/** The Material icon for a Connect device `type`. */
internal fun spotifyDeviceIcon(kind: SpotifyDeviceKind): ImageVector = when (kind) {
    SpotifyDeviceKind.SPEAKER -> Icons.Rounded.Speaker
    SpotifyDeviceKind.TV -> Icons.Rounded.Tv
    SpotifyDeviceKind.COMPUTER -> Icons.Rounded.Computer
    SpotifyDeviceKind.SMARTPHONE -> Icons.Rounded.Smartphone
    SpotifyDeviceKind.TABLET -> Icons.Rounded.TabletAndroid
    SpotifyDeviceKind.AVR -> Icons.Rounded.SpeakerGroup
    SpotifyDeviceKind.STB -> Icons.Rounded.ConnectedTv
    SpotifyDeviceKind.CAST_AUDIO -> Icons.Rounded.Cast
    SpotifyDeviceKind.CAST_VIDEO -> Icons.Rounded.Tv
    SpotifyDeviceKind.AUTOMOBILE -> Icons.Rounded.DirectionsCar
    SpotifyDeviceKind.GAME_CONSOLE -> Icons.Rounded.SportsEsports
    SpotifyDeviceKind.AUDIO_DONGLE -> Icons.Rounded.SettingsInputHdmi
    SpotifyDeviceKind.SMARTWATCH -> Icons.Rounded.Watch
    SpotifyDeviceKind.UNKNOWN -> Icons.Rounded.Speaker
}

/**
 * The devices page's "Spotify Connect" section (shared spec with iOS `SpotifyConnectSection`): the
 * account's Connect devices from `GET /me/player/devices`, fetched again whenever the sheet opens.
 * Tapping one plays the queue there; "Stop playing on <device>" brings it back to this phone.
 * Hidden while Spotify isn't linked; a login from before Connect existed shows only the reconnect row.
 */
internal fun LazyListScope.spotifyConnectSection(
    state: SpotifyConnectUiState,
    onDevice: (SpotifyConnectDevice) -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onStop: () -> Unit
) {
    if (state.availability == SpotifyConnectAvailability.HIDDEN) return

    item(key = "spotifyConnectHeader") {
        SpotifyConnectHeader(
            isRefreshing = state.isRefreshing,
            showRefresh = state.availability == SpotifyConnectAvailability.READY,
            onRefresh = onRefresh
        )
    }

    if (state.availability == SpotifyConnectAvailability.NEEDS_RECONNECT) {
        item(key = "spotifyConnectReconnect") {
            SpotifyConnectRow(
                title = "Reconnect Spotify to use Connect",
                status = "Sign in again once",
                icon = Icons.Rounded.Link,
                highlighted = false,
                enabled = true,
                onClick = onReconnect
            )
        }
        return
    }

    item(key = "spotifyConnectRefreshing") {
        AnimatedVisibility(
            visible = state.isRefreshing,
            enter = fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(180)),
            label = "spotifyConnectRefreshing"
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )
        }
    }

    state.active?.let { active ->
        item(key = "spotifyConnectStop") {
            SpotifyConnectRow(
                title = "Stop playing on ${active.name}",
                status = "Back to this phone",
                icon = Icons.Rounded.PhoneAndroid,
                highlighted = false,
                enabled = !state.isStopping,
                busy = state.isStopping,
                onClick = onStop
            )
        }
    }

    if (state.hasLoadedDevices && state.devices.isEmpty()) {
        item(key = "spotifyConnectEmpty") {
            SpotifyConnectEmpty(error = state.deviceListError)
        }
    } else {
        items(state.devices, key = { "spotify_${it.key}" }) { device ->
            val connecting = state.connectingDeviceId != null && state.connectingDeviceId == device.deviceId
            val playingHere = state.active?.deviceId != null && state.active.deviceId == device.deviceId
            SpotifyConnectRow(
                title = device.name,
                status = when {
                    connecting -> "Connecting…"
                    playingHere -> "Playing here"
                    device.deviceId == null -> "Unavailable"
                    device.isRestricted -> "Can't be controlled"
                    device.isActive -> "Active in Spotify"
                    else -> "Available"
                },
                icon = spotifyDeviceIcon(device.kind),
                highlighted = playingHere,
                enabled = device.isControllable && !playingHere && state.connectingDeviceId == null,
                busy = connecting,
                onClick = { onDevice(device) }
            )
        }
    }
}

@Composable
private fun SpotifyConnectHeader(isRefreshing: Boolean, showRefresh: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Spotify Connect",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = if (isRefreshing) "Looking for devices…" else "Play on speakers signed in to your Spotify",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showRefresh) {
            IconButton(
                onClick = onRefresh,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.clip(RoundedCornerShape(16.dp))
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh Spotify devices")
            }
        }
    }
}

@Composable
private fun SpotifyConnectEmpty(error: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Speaker,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = error ?: "No Spotify devices found",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Devices appear when they're online and signed in to your Spotify. For an Echo, link Spotify in the Alexa app.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A device row in the style of the sheet's Cast rows (capsule surface, icon badge, status chip). */
@Composable
private fun SpotifyConnectRow(
    title: String,
    status: String,
    icon: ImageVector,
    highlighted: Boolean,
    enabled: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    val (containerColor, onContainer) = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .alpha(if (enabled || highlighted || busy) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick),
        color = containerColor,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .padding(start = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            color = onContainer.copy(alpha = 0.12f),
                            shape = if (highlighted) RoundedStarShape(sides = 8, curve = 0.10, rotation = 0f) else CircleShape
                        )
                )
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = onContainer
                    )
                } else {
                    Icon(imageVector = icon, contentDescription = null, tint = onContainer, modifier = Modifier.size(24.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                SpotifyStatusChip(text = status, contentColor = onContainer)
            }
        }
    }
}

@Composable
private fun SpotifyStatusChip(text: String, contentColor: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(contentColor.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
            overflow = TextOverflow.Ellipsis,
            color = contentColor
        )
    }
}


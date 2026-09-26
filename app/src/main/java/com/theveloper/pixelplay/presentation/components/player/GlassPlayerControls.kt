package com.theveloper.pixelplay.presentation.components.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.kyant.shapes.Capsule
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.ui.glass.GlassCircleAction
import com.theveloper.pixelplay.ui.glass.components.GlassPanel
import com.theveloper.pixelplay.ui.glass.controls.LiquidChip
import com.theveloper.pixelplay.ui.glass.controls.MediaOrb
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

/**
 * Glass mode's transport (research-nexhome-design §8.6 / §10.2): NexHome's `MediaOrb` row —
 * previous 54 dp, play/pause 80 dp, next 54 dp, spaced evenly. Play/pause is lit with the album
 * accent while playing (a static lit state: no beat pulse). Next and previous send their command
 * on the tap; the orb's swell runs alongside (P2). Three glass nodes.
 */
@Composable
internal fun GlassTransportRow(
    isPlayingProvider: () -> Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPlaying = isPlayingProvider()
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Room for the 80 dp orb's 1.12 swell.
            .height(96.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaOrb(
            onClick = onPrevious,
            size = 54.dp,
            contentDescription = stringResource(R.string.common_previous_track),
        ) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = null, modifier = Modifier.size(28.dp))
        }
        MediaOrb(
            onClick = onPlayPause,
            size = 80.dp,
            lit = if (isPlaying) 1f else 0f,
            contentDescription = stringResource(
                if (isPlaying) R.string.common_pause else R.string.common_play
            ),
        ) {
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
            )
        }
        MediaOrb(
            onClick = onNext,
            size = 54.dp,
            contentDescription = stringResource(R.string.common_next_track),
        ) {
            Icon(Icons.Rounded.SkipNext, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}

/**
 * Glass mode's toggle row: NexHome's Shuffle / Repeat chip row (`LiquidChip`, 40 dp capsules,
 * icon + label, flooded with the accent when on), plus Favourite in place of NexHome's Mute.
 * Repeat cycles off / all / one exactly as the Material 3 row does. Three glass nodes.
 */
@Composable
internal fun GlassPlayerToggleRow(
    isShuffleEnabled: Boolean,
    isShuffleTransitionInProgress: Boolean,
    repeatMode: Int,
    isFavoriteProvider: () -> Boolean,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isFavorite = isFavoriteProvider()
    // NexHome's LiquidChipRow behaviour (horizontal scroll, 2 / 6 dp padding for the swell), kept
    // centred while the chips fit: long localised labels or a narrow screen scroll instead of
    // squeezing or clipping the chips.
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidChip(
            label = stringResource(R.string.common_shuffle),
            selected = isShuffleEnabled,
            onClick = { if (!isShuffleTransitionInProgress) onShuffleToggle() },
            icon = Icons.Rounded.Shuffle,
        )
        LiquidChip(
            label = stringResource(R.string.common_repeat),
            selected = repeatMode != Player.REPEAT_MODE_OFF,
            onClick = onRepeatToggle,
            icon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
        )
        LiquidChip(
            label = stringResource(R.string.common_favorite),
            selected = isFavorite,
            onClick = onFavoriteToggle,
            icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        )
    }
    }
}

/**
 * A 42 dp capsule glass action for the full player's top bar (the cast / output pill): a light
 * [GlassPanel] with the subtle tint, a shallow lens, NexHome's button swell and no glint.
 */
@Composable
internal fun GlassPlayerPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalGlassPalette.current
    GlassPanel(
        modifier = modifier.height(42.dp),
        shape = Capsule(),
        tint = palette.tintSubtle,
        accent = palette.accent,
        onClick = onClick,
        showHighlight = false,
        refractionHeight = 16.dp,
        refractionAmount = 32.dp,
        pressScale = LiquidMotion.ButtonPressScale,
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * The full player's top-bar collapse button in glass mode: NexHome's 42 dp top-bar orb.
 */
@Composable
internal fun GlassPlayerCollapseButton(onCollapse: () -> Unit) {
    GlassCircleAction(
        onClick = onCollapse,
        size = 42.dp,
        contentDescription = stringResource(R.string.player_cd_collapse),
    ) {
        Icon(
            painterResource(R.drawable.rounded_keyboard_arrow_down_24),
            contentDescription = null,
            modifier = Modifier.size(GlassOrbIconSize)
        )
    }
}

/**
 * The full player's top-bar actions in glass mode: the output pill (cast / Bluetooth / phone,
 * with the route name while casting) and the queue orb. Same routes and labels as the Material 3
 * buttons, drawn as NexHome's light capsule and orb. Two glass nodes.
 */
@Composable
internal fun GlassPlayerTopActions(
    isCastConnecting: Boolean,
    isRemotePlaybackActive: Boolean,
    selectedRouteName: String?,
    isBluetoothEnabled: Boolean,
    bluetoothName: String?,
    onCastClick: () -> Unit,
    onQueueClick: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val showCastLabel = isCastConnecting || (isRemotePlaybackActive && selectedRouteName != null)
        val isBluetoothActive =
            isBluetoothEnabled && !bluetoothName.isNullOrEmpty() && !isRemotePlaybackActive && !isCastConnecting
        val castIcon = when {
            isCastConnecting || isRemotePlaybackActive -> R.drawable.rounded_cast_24
            isBluetoothActive -> R.drawable.rounded_bluetooth_24
            else -> R.drawable.rounded_mobile_speaker_24
        }
        val castDescription = when {
            isCastConnecting || isRemotePlaybackActive -> stringResource(R.string.player_cd_cast)
            isBluetoothActive -> stringResource(R.string.player_cd_bluetooth)
            else -> stringResource(R.string.player_cd_local_playback)
        }
        GlassPlayerPill(onClick = onCastClick, modifier = Modifier.widthIn(max = 190.dp)) {
            Icon(painterResource(castIcon), contentDescription = castDescription, modifier = Modifier.size(GlassOrbIconSize))
            AnimatedVisibility(visible = showCastLabel) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            isCastConnecting -> stringResource(R.string.player_connecting)
                            isRemotePlaybackActive && selectedRouteName != null -> selectedRouteName
                            else -> ""
                        },
                        style = GlassType.Label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        GlassCircleAction(
            onClick = onQueueClick,
            size = 42.dp,
            contentDescription = stringResource(R.string.player_cd_open_queue),
        ) {
            Icon(
                painterResource(R.drawable.rounded_queue_music_24),
                contentDescription = null,
                modifier = Modifier.size(GlassOrbIconSize)
            )
        }
    }
}

/** NexHome top-bar orb icon size. */
private val GlassOrbIconSize = 20.dp

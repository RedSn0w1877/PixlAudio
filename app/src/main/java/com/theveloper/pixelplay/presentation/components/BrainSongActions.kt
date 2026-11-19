package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.utils.LocalBrainUi
import com.theveloper.pixelplay.presentation.viewmodel.BrainNotice

private val ActionOuter = 24.dp
private val ActionInner = 6.dp
private val ActionTop: Shape = RoundedCornerShape(topStart = ActionOuter, topEnd = ActionOuter, bottomStart = ActionInner, bottomEnd = ActionInner)
private val ActionMiddle: Shape = RoundedCornerShape(ActionInner)
private val ActionBottom: Shape = RoundedCornerShape(topStart = ActionInner, topEnd = ActionInner, bottomStart = ActionOuter, bottomEnd = ActionOuter)

/**
 * "Start station" and "Not for me" for one song (docs/brain-spec.md, 8.3, 8.5): two rows joined like
 * a Material 3 Expressive segmented list, shown in the song options sheet, which is every song menu
 * and the player's more-menu. Draws nothing when the app root provides no [LocalBrainUi].
 */
@Composable
fun BrainSongActions(
    song: Song,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brain = LocalBrainUi.current ?: return
    // The artist station is offered when the song names an artist; the rest are for every song.
    val artist = remember(song.id) { com.theveloper.pixelplay.data.brain.BrainFeedback.artistOf(song) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BrainActionRow(
            icon = Icons.Rounded.Radio,
            title = stringResource(R.string.brain_start_station),
            supporting = stringResource(R.string.brain_start_station_song_supporting),
            shape = ActionTop,
            onClick = {
                brain.startSongStation(song)
                onDone()
            },
        )
        if (artist.isNotEmpty()) {
            BrainActionRow(
                icon = Icons.Rounded.Person,
                title = stringResource(R.string.brain_start_artist_station),
                supporting = stringResource(R.string.brain_start_artist_station_supporting),
                shape = ActionMiddle,
                onClick = {
                    brain.startArtistStation(artist, emptyList())
                    onDone()
                },
            )
        }
        BrainActionRow(
            icon = Icons.Rounded.ThumbUp,
            title = stringResource(R.string.brain_more_like_this),
            supporting = stringResource(R.string.brain_more_like_this_supporting),
            shape = ActionMiddle,
            onClick = {
                brain.moreLikeThis(song)
                onDone()
            },
        )
        BrainActionRow(
            icon = Icons.Rounded.ThumbDown,
            title = stringResource(R.string.brain_not_for_me),
            supporting = stringResource(R.string.brain_not_for_me_supporting),
            shape = ActionMiddle,
            onClick = {
                brain.notForMe(song)
                onDone()
            },
        )
        BrainActionRow(
            icon = Icons.Rounded.VisibilityOff,
            title = stringResource(R.string.brain_dont_count),
            supporting = stringResource(R.string.brain_dont_count_supporting),
            shape = ActionBottom,
            onClick = {
                brain.dontCount(song)
                onDone()
            },
        )
    }
}

@Composable
private fun BrainActionRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    shape: Shape,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = colors.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(colors.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The bar that follows a piece of feedback (8.5): what happened, Undo, and after "Not for me" the
 * step to "Less from the artist". It rises from the bottom on the expressive motion scheme and is a
 * polite live region, so a screen reader says it without stealing focus.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BrainNoticeBar(
    notice: BrainNotice?,
    onUndo: () -> Unit,
    onLessFromArtist: () -> Unit,
    modifier: Modifier = Modifier,
    onMuteArtist: () -> Unit = {},
) {
    val motion = remember { MotionScheme.expressive() }
    val enterSpec = remember(motion) { motion.defaultSpatialSpec<IntOffset>() }
    val exitSpec = remember(motion) { motion.fastSpatialSpec<IntOffset>() }
    val fadeSpec = remember(motion) { motion.fastEffectsSpec<Float>() }
    // The last notice stays drawn while the bar leaves.
    val shown = remember { arrayOfNulls<BrainNotice>(1) }
    if (notice != null) shown[0] = notice
    AnimatedVisibility(
        visible = notice != null,
        enter = slideInVertically(animationSpec = enterSpec) { it } + fadeIn(animationSpec = fadeSpec),
        exit = slideOutVertically(animationSpec = exitSpec) { it } + fadeOut(animationSpec = fadeSpec),
        modifier = modifier,
    ) {
        val current = shown[0] ?: return@AnimatedVisibility
        val colors = MaterialTheme.colorScheme
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = colors.inverseSurface,
            contentColor = colors.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Text(
                    text = when (current.kind) {
                        BrainNotice.Kind.NOT_FOR_ME -> stringResource(R.string.brain_not_for_me_done)
                        BrainNotice.Kind.NOT_SAVED -> stringResource(R.string.brain_not_for_me_failed)
                        BrainNotice.Kind.LESS_FROM -> stringResource(R.string.brain_less_from_done, current.artistName)
                        BrainNotice.Kind.UNDONE -> stringResource(R.string.brain_undone)
                        BrainNotice.Kind.MORE_LIKE -> stringResource(R.string.brain_more_like_done, current.artistName)
                        BrainNotice.Kind.OFFER_MUTE -> stringResource(R.string.brain_mute_artist_offer, current.artistName)
                        BrainNotice.Kind.MUTED_ARTIST -> stringResource(R.string.brain_mute_artist_done, current.artistName)
                        BrainNotice.Kind.DONT_COUNT -> stringResource(R.string.brain_dont_count_done)
                        BrainNotice.Kind.NOTHING_TO_LEAVE_OUT -> stringResource(R.string.brain_dont_count_nothing)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
                )
                val canUndo = current.adjustmentId != null
                val canLess = current.kind == BrainNotice.Kind.NOT_FOR_ME && current.artistName.isNotBlank()
                // The second step after "Less from X": mute them (8.5).
                val canMute = (current.kind == BrainNotice.Kind.LESS_FROM || current.kind == BrainNotice.Kind.OFFER_MUTE) &&
                    current.artistName.isNotBlank()
                if (canUndo || canLess || canMute) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (canLess) {
                            TextButton(onClick = onLessFromArtist, modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    stringResource(R.string.brain_less_from, current.artistName),
                                    color = colors.inversePrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (canMute) {
                            TextButton(onClick = onMuteArtist, modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    stringResource(R.string.brain_mute_artist, current.artistName),
                                    color = colors.inversePrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (canUndo) {
                            TextButton(onClick = onUndo) {
                                Text(stringResource(R.string.brain_undo), color = colors.inversePrimary)
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }
}

/**
 * The "Private" pill (docs/brain-spec.md, 8.5): shown at the top of the screen while a private session
 * is on, so it can never be on without the listener seeing it. One tap ends the session.
 */
@Composable
fun PrivatePill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.brain_private_pill_cd)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 3.dp,
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 6.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.brain_private_pill), style = MaterialTheme.typography.labelLarge)
        }
    }
}

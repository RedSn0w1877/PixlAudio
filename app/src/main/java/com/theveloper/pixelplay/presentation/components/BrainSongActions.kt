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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.ThumbDown
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
        BrainActionRow(
            icon = Icons.Rounded.ThumbDown,
            title = stringResource(R.string.brain_not_for_me),
            supporting = stringResource(R.string.brain_not_for_me_supporting),
            shape = ActionBottom,
            onClick = {
                brain.notForMe(song)
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
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
                )
                val canUndo = current.adjustmentId != null
                val canLess = current.kind == BrainNotice.Kind.NOT_FOR_ME && current.artistName.isNotBlank()
                if (canUndo || canLess) {
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

package com.theveloper.pixelplay.presentation.spatial

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoneyOff
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialStatusLine
import com.theveloper.pixelplay.presentation.components.AdaptiveModalBottomSheet
import com.theveloper.pixelplay.presentation.viewmodel.SpatialAudioViewModel
import com.theveloper.pixelplay.presentation.viewmodel.SpatialSongChoice
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Spatial audio's sheet (cloud-audio-spec §6.5): the status line for the current song, Off and the six profiles with
 * their one-liners, and under the list the quiet line about level. Picking a row applies at once (the holder only
 * stores it; the player and the cloud side react to the stored value) and the sheet stays open, so profiles can be
 * compared by ear. It collects only while it is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpatialAudioSheet(
    onDismissRequest: () -> Unit,
    viewModel: SpatialAudioViewModel = hiltViewModel(),
) {
    // Kept inside, so callers need no opt-in for Material's experimental sheet state.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val holder = viewModel.spatial
    val selected by remember(holder) {
        holder.settings.map { it.profile }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = holder.settings.value.profile)
    val statusLine by holder.statusLine.collectAsStateWithLifecycle()
    val song by holder.currentSong.collectAsStateWithLifecycle()

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        SpatialAudioSheetContent(
            selected = selected,
            statusLine = statusLine,
            onSelect = holder::selectProfile,
            song = song,
            onSongSpatialChange = holder::setCurrentSongSpatial,
        )
    }
}

/** The sheet's content, stateless: what [SpatialAudioSheet] shows for a [selected] profile and a [statusLine]. */
@Composable
internal fun SpatialAudioSheetContent(
    selected: SpatialProfile?,
    statusLine: SpatialStatusLine,
    onSelect: (SpatialProfile?) -> Unit,
    modifier: Modifier = Modifier,
    song: SpatialSongChoice? = null,
    onSongSpatialChange: (Boolean) -> Unit = {},
) {
    val choices = remember(selected) { SpatialAudioUi.choices(selected) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        item(key = "header", contentType = "header") {
            SpatialSheetHeader(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp))
        }
        item(key = "song", contentType = "song") {
            // Only while a profile is selected and a song plays: with Spatial audio off everything is the original.
            val visible = selected != null && song != null
            // Holds the last song while the card collapses, so it doesn't empty out as it goes.
            var shown by remember { mutableStateOf(song) }
            if (song != null) shown = song
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(200)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut(tween(120)) + shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            ) {
                shown?.let { choice -> SpatialThisSongCard(on = !choice.off, onChange = onSongSpatialChange) }
            }
        }
        item(key = "status", contentType = "status") {
            SpatialStatusCard(line = statusLine)
        }
        item(key = "choices", contentType = "choices") {
            // One selectable group for TalkBack ("1 of 7"); seven fixed rows need no lazy items of their own.
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                choices.forEachIndexed { index, choice ->
                    // The key keeps each row's animation state with its profile when the selection moves.
                    key(choice.key) {
                        SpatialChoiceRow(
                            choice = choice,
                            isFirst = index == 0,
                            isLast = index == choices.lastIndex,
                            onClick = { if (!choice.selected) onSelect(choice.profile) },
                        )
                    }
                }
            }
        }
        item(key = "note", contentType = "note") {
            Text(
                text = stringResource(R.string.spatial_ui_quieter_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp),
            )
        }
    }
}

@Composable
private fun SpatialSheetHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.rounded_spatial_audio_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.spatial_ui_audio),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.spatial_ui_for_headphones),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "This song": Spatial audio on or off for the song playing, remembered for every later play. Off plays the original
 * at full quality. The whole card is the switch (one target for TalkBack); the icon disc and the line under the title
 * follow the state, and the card's tone swaps with it (drawn in the draw phase, like the profile rows).
 */
@Composable
private fun SpatialThisSongCard(on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fill = rememberSpatialChoiceFill(
        selected = on,
        unselectedColor = colors.surfaceContainerHigh,
        selectedColor = colors.primaryContainer,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .graphicsLayer {
                shape = RoundedCornerShape(OuterCorner)
                clip = true
            }
            .drawBehind { drawRect(fill()) }
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(if (on) colors.primary else colors.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.rounded_spatial_audio_24),
                contentDescription = null,
                tint = if (on) colors.onPrimary else colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.spatial_ui_this_song),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (on) colors.onPrimaryContainer else colors.onSurface,
            )
            AnimatedContent(
                targetState = on,
                transitionSpec = {
                    (fadeIn(tween(180, delayMillis = 40)) togetherWith fadeOut(tween(100)))
                        .using(SizeTransform(clip = false))
                },
                label = "spatialThisSongLine",
            ) { isOn ->
                Text(
                    text = stringResource(if (isOn) R.string.spatial_ui_this_song_on else R.string.spatial_ui_this_song_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isOn) colors.onPrimaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                )
            }
        }
        // Decorative: the card itself is the toggle.
        Switch(
            checked = on,
            onCheckedChange = null,
            thumbContent = {
                Icon(
                    imageVector = if (on) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            },
        )
    }
}

/**
 * The status line for the current song (§6.5), or nothing for [SpatialStatusLine.NONE]. A change crossfades and the
 * card's height follows in the layout phase; the outgoing line stays readable while it fades.
 */
@Composable
private fun SpatialStatusCard(line: SpatialStatusLine) {
    AnimatedContent(
        targetState = line,
        transitionSpec = {
            (fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 60)) togetherWith
                fadeOut(animationSpec = tween(durationMillis = 120)))
                .using(SizeTransform(clip = false))
        },
        label = "spatialStatusLine",
    ) { shown ->
        val res = SpatialAudioUi.statusLineRes(shown)
        if (res == null) {
            Box(Modifier.fillMaxWidth())
        } else {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(imageVector = statusIcon(shown), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(text = stringResource(res), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun statusIcon(line: SpatialStatusLine): ImageVector = when (line) {
    SpatialStatusLine.ON_PHONE_FOR_NOW -> Icons.Rounded.HourglassTop
    SpatialStatusLine.WAITING_FOR_WIFI -> Icons.Rounded.Wifi
    SpatialStatusLine.CLOUD_LIMIT_REACHED -> Icons.Rounded.MoneyOff
    SpatialStatusLine.GETTING_CLOUD_VERSION -> Icons.Rounded.CloudDownload
    SpatialStatusLine.CLOUD_VERSION -> Icons.Rounded.CloudDone
    SpatialStatusLine.ON_PHONE_ONLY -> Icons.Rounded.Smartphone
    SpatialStatusLine.CLOUD_FAILED -> Icons.Rounded.ErrorOutline
    SpatialStatusLine.HEADPHONES_ONLY -> Icons.Rounded.Headphones
    SpatialStatusLine.PAUSED_FOR_SING -> Icons.Rounded.Mic
    SpatialStatusLine.NONE -> Icons.Rounded.Smartphone
}

/**
 * One row of the list: the name and its one-liner. The selected row takes the tonal container and its full corners
 * (the others keep the tight inner corners of a grouped list), and its check scales in. Container colour, corners and
 * check are read in the draw and layer phases only, so a pick recomposes nothing per frame. Opened from the player,
 * the sheet wears the album colours, which fade over about 40 frames after a skip: the fill takes each frame's colours
 * at once, with the text, and only a change of selection animates ([rememberSpatialChoiceFill]).
 */
@Composable
private fun SpatialChoiceRow(
    choice: SpatialChoice,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fill = rememberSpatialChoiceFill(
        selected = choice.selected,
        unselectedColor = colors.surfaceContainerHigh,
        selectedColor = colors.secondaryContainer,
    )
    val cornerSpring = spring<Dp>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    val top = animateDpAsState(
        targetValue = if (choice.selected || isFirst) OuterCorner else InnerCorner,
        animationSpec = cornerSpring,
        label = "spatialChoiceTop",
    )
    val bottom = animateDpAsState(
        targetValue = if (choice.selected || isLast) OuterCorner else InnerCorner,
        animationSpec = cornerSpring,
        label = "spatialChoiceBottom",
    )
    val check = animateFloatAsState(
        targetValue = if (choice.selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "spatialChoiceCheck",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .graphicsLayer {
                shape = RoundedCornerShape(
                    topStart = top.value,
                    topEnd = top.value,
                    bottomStart = bottom.value,
                    bottomEnd = bottom.value,
                )
                clip = true
            }
            .drawBehind { drawRect(fill()) }
            .selectable(selected = choice.selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(choice.nameRes),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(choice.lineRes),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = colors.onSecondaryContainer,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    val shown = check.value
                    scaleX = shown
                    scaleY = shown
                    alpha = shown.coerceIn(0f, 1f)
                },
        )
    }
}

private val RowGap = 2.dp
private val OuterCorner = 24.dp
private val InnerCorner = 6.dp

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun SpatialAudioSheetOffPreview() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SpatialAudioSheetContent(selected = null, statusLine = SpatialStatusLine.NONE, onSelect = {})
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun SpatialAudioSheetOnDevicePreview() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SpatialAudioSheetContent(
                selected = SpatialProfile.HALL,
                statusLine = SpatialStatusLine.ON_PHONE_FOR_NOW,
                onSelect = {},
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun SpatialAudioSheetHeadphonesOnlyPreview() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SpatialAudioSheetContent(
                selected = SpatialProfile.STUDIO,
                statusLine = SpatialStatusLine.HEADPHONES_ONLY,
                onSelect = {},
            )
        }
    }
}

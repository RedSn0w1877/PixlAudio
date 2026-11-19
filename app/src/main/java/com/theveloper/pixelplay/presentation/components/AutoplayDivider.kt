package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.service.player.AutoplayUiState

/**
 * The line of the queue everything under which was picked by the engine (docs/brain-spec.md, 8.3,
 * 8.4). For plain autoplay it is "Autoplay" with its off switch; when the queue is a station it is
 * "Station · X". Under it sits the Familiar / Blend / Discover switch, and the way to "How picks
 * work". Shown above the first engine item, or at the end of the queue while there is none yet.
 */
@Composable
fun AutoplayDivider(
    state: AutoplayUiState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onNoveltyChange: ((Int) -> Unit)? = null,
    onHowPicksWork: (() -> Unit)? = null,
    /** "Refresh picks": null while there is nothing upcoming to refresh. */
    onRefreshPicks: (() -> Unit)? = null,
) {
    val stationTitle = state.stationTitle
    val isStation = stationTitle != null
    val title = if (stationTitle != null) {
        stringResource(R.string.brain_station_title, stationTitle)
    } else {
        stringResource(R.string.queue_autoplay_title)
    }
    val subtitle = stringResource(
        when {
            // A Cast device plays only what was sent when casting started: no picks reach it.
            state.offWhileCasting -> R.string.queue_autoplay_off_casting
            !isStation && !state.enabled -> R.string.queue_autoplay_off
            state.finding -> R.string.queue_autoplay_finding
            state.changedDirection -> R.string.queue_autoplay_changed_direction
            state.fromLibrary -> R.string.queue_autoplay_from_library
            isStation && state.stationIsArtist -> R.string.brain_station_subtitle_artist
            isStation && state.stationSource == com.theveloper.pixelplay.data.brain.Station.SOURCE_ALBUM -> R.string.brain_station_subtitle_album
            isStation && state.stationSource == com.theveloper.pixelplay.data.brain.Station.SOURCE_PLAYLIST -> R.string.brain_station_subtitle_playlist
            isStation && (state.stationSource == com.theveloper.pixelplay.data.brain.Station.SOURCE_GENRE ||
                state.stationSource == com.theveloper.pixelplay.data.brain.Station.SOURCE_MOOD) -> R.string.brain_station_subtitle_genre
            isStation -> R.string.brain_station_subtitle_song
            else -> R.string.queue_autoplay_on
        }
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (isStation) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = if (isStation) FontWeight.SemiBold else null,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onHowPicksWork != null) {
                IconButton(onClick = onHowPicksWork, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.brain_how_picks_work),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (!isStation && !state.offWhileCasting) {
                // A station is something the listener asked for by hand: it has no off switch here.
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = title },
                )
            }
        }
        if (onNoveltyChange != null && (isStation || state.enabled) && !state.fromLibrary && !state.offWhileCasting) {
            Spacer(modifier = Modifier.height(8.dp))
            NoveltySwitch(
                level = state.novelty,
                onLevelChange = onNoveltyChange,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (onRefreshPicks != null && (isStation || state.enabled) && !state.fromLibrary && !state.offWhileCasting) {
            androidx.compose.material3.TextButton(
                onClick = onRefreshPicks,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.brain_refresh_picks))
            }
        }
    }
}

/**
 * The reason line under an engine pick (8.8). One line, quiet, in the primary colour; a tap opens the
 * sheet behind it.
 */
@Composable
fun WhyLineLabel(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val description = stringResource(R.string.brain_why_cd)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClickLabel = description, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 2.dp),
    )
}

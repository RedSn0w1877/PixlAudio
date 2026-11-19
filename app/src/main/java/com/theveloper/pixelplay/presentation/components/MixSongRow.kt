package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.brain.MixReason
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem

/**
 * One row of Daily Mix or Your Mix (docs/brain-spec.md, 8.2, stage 2a). It is the normal song row, plus
 * a line under it when there is something true to say: the reason the track is there, and, for a track
 * from outside the library, Save. A saved track becomes a library track (the same ids it already plays
 * under), and the mix itself is not changed by it.
 */
@Composable
fun MixSongRow(
    song: Song,
    reason: MixReason?,
    isNovel: Boolean,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMoreOptionsClick: (Song) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        EnhancedSongListItem(
            song = song,
            isCurrentSong = isCurrentSong,
            isPlaying = isPlaying,
            onClick = onClick,
            onMoreOptionsClick = onMoreOptionsClick,
        )
        val line = reason?.let { whyLineText(it.key, it.arg) }
        if (line != null || isNovel) {
            var saved by remember(song.id) { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 74.dp, end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = line.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isNovel) {
                    TextButton(
                        onClick = {
                            saved = true
                            onSave()
                        },
                        enabled = !saved,
                    ) {
                        Text(stringResource(if (saved) R.string.brain_mix_saved else R.string.brain_mix_save))
                    }
                }
            }
        }
    }
}

/**
 * The line under a Recently Played row of a track from outside the library (docs/brain-spec.md, 7.2): a
 * "Save" that imports it for good, through the same importer Like uses. Without it a listener cannot keep
 * the song autoplay just played them.
 */
@Composable
fun EngineSaveRow(onSave: () -> Unit, modifier: Modifier = Modifier) {
    var saved by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().padding(end = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                saved = true
                onSave()
            },
            enabled = !saved,
        ) {
            Text(stringResource(if (saved) R.string.brain_mix_saved else R.string.brain_recent_save))
        }
    }
}

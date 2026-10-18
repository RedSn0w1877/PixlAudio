package com.theveloper.pixelplay.presentation.components

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.utils.LocalBlockedArtistsUi

/** The small "Blocked" tag shown on a dimmed song row. */
@Composable
fun BlockedBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Text(
            text = stringResource(R.string.blocked_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** "Play anyway?" shown when the owner taps a song by a blocked artist. */
@Composable
fun PlayBlockedSongDialog(song: Song, onPlayAnyway: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Block, contentDescription = null) },
        title = { Text(stringResource(R.string.blocked_play_anyway_title)) },
        text = { Text(stringResource(R.string.blocked_play_anyway_message, song.displayArtist)) },
        confirmButton = {
            TextButton(onClick = onPlayAnyway) { Text(stringResource(R.string.blocked_play_anyway)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * "Block artist" / "Unblock artist" row for an artist overflow menu. Does nothing visible when the
 * blocked-artists list is not provided (previews).
 */
@Composable
fun BlockArtistMenuItem(artistId: Long, artistName: String, onDone: () -> Unit) {
    val ui = LocalBlockedArtistsUi.current ?: return
    val context = LocalContext.current
    val blocked = remember(ui.index, artistId, artistName) { ui.index.contains(artistId, artistName) }
    val blockedMessage = stringResource(R.string.blocked_artist_toast_blocked, artistName)
    val unblockedMessage = stringResource(R.string.blocked_artist_toast_unblocked, artistName)
    DropdownMenuItem(
        text = {
            Text(
                if (blocked) stringResource(R.string.blocked_unblock_artist)
                else stringResource(R.string.blocked_block_artist)
            )
        },
        leadingIcon = {
            Icon(if (blocked) Icons.Rounded.CheckCircle else Icons.Rounded.Block, contentDescription = null)
        },
        onClick = {
            if (blocked) {
                ui.unblock(artistId, artistName)
                Toast.makeText(context, unblockedMessage, Toast.LENGTH_SHORT).show()
            } else {
                ui.block(artistId, artistName)
                Toast.makeText(context, blockedMessage, Toast.LENGTH_SHORT).show()
            }
            onDone()
        }
    )
}

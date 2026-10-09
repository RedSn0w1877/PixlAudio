package com.theveloper.pixelplay.presentation.viewmodel

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Queues bigger than this wait (briefly) for the tapped song to be ready before the rest of
 * the queue is attached, so the one big attach does not land in the middle of the player-sheet
 * expand animation. Smaller queues attach straight away, exactly as before.
 */
internal const val QUEUE_ATTACH_SETTLE_THRESHOLD = 200

/** Upper bound for that wait; a stream that is slow to start never holds the queue back longer. */
internal const val QUEUE_ATTACH_MAX_SETTLE_MS = 400L

private const val QUEUE_ATTACH_POLL_MS = 25L

/**
 * Attaches the rest of a freshly started queue around the already-playing tapped song:
 * [beforeCurrent] goes in front of it and [afterCurrent] behind it.
 *
 * Every `addMediaItems` call is a separate timeline change, and every timeline change makes
 * the media session republish the WHOLE queue (legacy queue conversion + binder call), rebuild
 * the notification and re-run the engine/transition listeners. Doing it in 200-item batches
 * therefore cost roughly n/200 full republishes (quadratic in the queue size); two calls are
 * enough and the resulting queue is identical.
 *
 * Returns false (and changes nothing) when the player no longer holds exactly the tapped song,
 * i.e. the user already started something else.
 */
internal suspend fun attachQueueSegmentsIfCurrent(
    player: Player,
    startSongId: String,
    beforeCurrent: List<MediaItem>,
    afterCurrent: List<MediaItem>,
): Boolean {
    if (beforeCurrent.size + afterCurrent.size > QUEUE_ATTACH_SETTLE_THRESHOLD) {
        withTimeoutOrNull(QUEUE_ATTACH_MAX_SETTLE_MS) {
            while (player.playbackState != Player.STATE_READY) {
                delay(QUEUE_ATTACH_POLL_MS)
            }
        }
    }

    if (player.currentMediaItem?.mediaId != startSongId) return false
    if (player.mediaItemCount != 1) return false
    if (player.getMediaItemAt(0).mediaId != startSongId) return false

    if (beforeCurrent.isNotEmpty()) {
        player.addMediaItems(0, beforeCurrent)
        yield()
    }
    if (afterCurrent.isNotEmpty()) {
        player.addMediaItems(beforeCurrent.size + 1, afterCurrent)
    }
    return true
}

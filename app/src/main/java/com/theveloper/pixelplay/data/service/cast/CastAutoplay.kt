package com.theveloper.pixelplay.data.service.cast

import android.content.ContentResolver
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.service.http.CastStreamRelay
import com.theveloper.pixelplay.data.service.http.MediaFileHttpServerService
import com.theveloper.pixelplay.data.service.player.AutoplayController
import com.theveloper.pixelplay.data.service.player.CastPlayer
import com.theveloper.pixelplay.utils.MediaItemBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

/**
 * Autoplay while casting (handoff/2026-11-25-cast-stream.md): the phone's own player sits paused
 * during a Cast session, so its track changes never reach the autoplay trigger. The Cast status
 * does: this turns status updates into the two events the trigger knows.
 */
internal class CastAutoplayTrigger {

    enum class Event { NONE, TRANSITION, QUEUE_ENDED }

    private var lastItemId = MediaQueueItem.INVALID_ITEM_ID
    private var endedReported = false

    /** A new session (or none): the next status starts from nothing. */
    fun reset() {
        lastItemId = MediaQueueItem.INVALID_ITEM_ID
        endedReported = false
    }

    /**
     * One status update: [currentItemId] is the receiver's current queue item, [playerState] and
     * [idleReason] its [MediaStatus] values. A new current item is a [Event.TRANSITION]; the receiver
     * idle because it FINISHED is [Event.QUEUE_ENDED], once until something plays again.
     */
    fun onStatus(currentItemId: Int, playerState: Int, idleReason: Int): Event {
        if (isFinished(playerState, idleReason)) {
            if (endedReported) return Event.NONE
            endedReported = true
            return Event.QUEUE_ENDED
        }
        if (playerState == MediaStatus.PLAYER_STATE_PLAYING || playerState == MediaStatus.PLAYER_STATE_BUFFERING) {
            endedReported = false
        }
        if (currentItemId == MediaQueueItem.INVALID_ITEM_ID || currentItemId == lastItemId) return Event.NONE
        val first = lastItemId == MediaQueueItem.INVALID_ITEM_ID
        // An idle receiver (a queue being loaded, an error) has not moved on yet: the item counts
        // once it buffers or plays. The first item seen is where a session starts, whatever its state.
        if (!first && playerState == MediaStatus.PLAYER_STATE_IDLE) return Event.NONE
        lastItemId = currentItemId
        return Event.TRANSITION
    }

    companion object {
        fun isFinished(playerState: Int, idleReason: Int): Boolean =
            playerState == MediaStatus.PLAYER_STATE_IDLE && idleReason == MediaStatus.IDLE_REASON_FINISHED
    }
}

/** Where "current" is in a Cast queue. Pure. */
internal object CastQueueIndex {
    /**
     * The index of [currentItemId] in [itemIds]. After the queue ended the receiver may name no
     * current item any more: then it is [lastKnownIndex] when that is still in range, else the last
     * item. -1 when nothing is current and the queue did not end.
     */
    fun resolve(itemIds: IntArray, currentItemId: Int, ended: Boolean, lastKnownIndex: Int): Int {
        val at = itemIds.indexOf(currentItemId)
        if (at >= 0) return at
        if (!ended || itemIds.isEmpty()) return -1
        return if (lastKnownIndex in itemIds.indices) lastKnownIndex else itemIds.size - 1
    }

    /** [Player] repeat mode of a Cast queue repeat mode. */
    fun playerRepeatMode(castRepeatMode: Int): Int = when (castRepeatMode) {
        MediaStatus.REPEAT_MODE_REPEAT_SINGLE -> Player.REPEAT_MODE_ONE
        MediaStatus.REPEAT_MODE_REPEAT_ALL,
        MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_OFF
    }

    /** A queue item the phone has not been told about yet. */
    fun placeholderMediaId(itemId: Int): String = "cast-item:$itemId"
}

/**
 * The Cast device's queue for [AutoplayController] ([AutoplayController.Host.castQueue]), and the
 * relay check for its picks. Main thread, except [playable].
 */
internal class CastAutoplayBridge(
    private val session: () -> CastSession?,
    private val contentResolver: ContentResolver,
    /** Resolves a track's stream for the relay; null when it does not resolve. */
    private val prepareStream: suspend (trackId: String) -> String?,
) {
    /** Items the engine appended, by media id: they keep their extras (which rows are the engine's). */
    private val appended = object : LinkedHashMap<String, MediaItem>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaItem>?) = size > APPENDED_LIMIT
    }
    private var lastKnownIndex = -1

    fun queue(): AutoplayController.CastQueue? {
        val castSession = session() ?: return null
        val client = castSession.remoteMediaClient ?: return null
        val status = client.mediaStatus ?: return null
        val itemIds = client.mediaQueue.itemIds
        if (itemIds.isEmpty()) return null
        // A status names only a window of the queue (often the current item and the next): the
        // media queue has every id, and the items it fetched; the window fills in the rest.
        val window = HashMap<Int, MediaQueueItem>()
        status.queueItems.forEach { window[it.itemId] = it }
        val items = ArrayList<MediaItem>(itemIds.size)
        for (i in itemIds.indices) {
            val id = itemIds[i]
            val queueItem = window[id] ?: client.mediaQueue.getItemAtIndex(i, false)
            items.add(mediaItemOf(id, queueItem))
        }
        val ended = CastAutoplayTrigger.isFinished(status.playerState, status.idleReason)
        val index = CastQueueIndex.resolve(itemIds, status.currentItemId, ended, lastKnownIndex)
        if (index >= 0) lastKnownIndex = index
        val playWhenReady = ended || CastRemotePlaybackState.project(status, previousPlayIntent = true).playWhenReady
        return Snapshot(
            items = items,
            currentIndex = index,
            repeatMode = CastQueueIndex.playerRepeatMode(status.queueRepeatMode),
            hasEnded = ended,
            playWhenReady = playWhenReady,
            castSession = castSession,
        )
    }

    /** Which of [items] the receiver can play: a streamed track whose stream resolves now. */
    suspend fun playable(items: List<MediaItem>): BooleanArray = coroutineScope {
        val answers = items.map { item ->
            async {
                val trackId = CastStreamRelay.trackIdOf(contentUriOf(item)) ?: return@async false
                runCatching { prepareStream(trackId) != null }.getOrDefault(false)
            }
        }.awaitAll()
        BooleanArray(answers.size) { answers[it] }
    }

    fun reset() {
        appended.clear()
        lastKnownIndex = -1
    }

    private fun mediaItemOf(itemId: Int, queueItem: MediaQueueItem?): MediaItem {
        val songId = queueItem?.customData?.optString("songId")?.takeIf { it.isNotBlank() }
            ?: return MediaItem.Builder().setMediaId(CastQueueIndex.placeholderMediaId(itemId)).build()
        appended[songId]?.let { return it }
        val metadata = queueItem.media?.metadata
        return MediaItem.Builder()
            .setMediaId(songId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(metadata?.getString(CastMediaMetadata.KEY_TITLE))
                    .setArtist(metadata?.getString(CastMediaMetadata.KEY_ARTIST))
                    .build()
            )
            .build()
    }

    private inner class Snapshot(
        override val items: List<MediaItem>,
        override val currentIndex: Int,
        override val repeatMode: Int,
        override val hasEnded: Boolean,
        override val playWhenReady: Boolean,
        private val castSession: CastSession,
    ) : AutoplayController.CastQueue {
        override fun append(items: List<MediaItem>, playFirst: Boolean) {
            val songs = items.mapNotNull(::songOf)
            if (songs.size < items.size) {
                Timber.i("cast autoplay: %d of %d picks left out, no stream to relay", items.size - songs.size, items.size)
            }
            if (songs.isEmpty()) return
            val serverAddress = MediaFileHttpServerService.serverAddress ?: run {
                Timber.w("cast autoplay: the file server is not running, picks not sent")
                return
            }
            val policy = MediaFileHttpServerService.extendCastSessionAccess(
                addedSongIds = songs.map(Song::id),
                addedRelayTrackIds = CastStreamRelay.relayTrackIdsOf(songs),
            )
            for (item in items) appended[item.mediaId] = item
            CastPlayer(castSession, contentResolver).appendSongs(
                songs = songs,
                serverAddress = serverAddress,
                authToken = policy.authToken,
                playFirst = playFirst,
            ) { ok -> if (!ok) Timber.w("cast autoplay: the receiver did not take %d picks", songs.size) }
        }
    }

    private companion object {
        const val APPENDED_LIMIT = 400

        fun contentUriOf(item: MediaItem): String? =
            item.localConfiguration?.uri?.toString()
                ?: item.mediaMetadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)

        /** The [Song] a pick's media item was built from, as far as Cast needs it; null without a stream. */
        fun songOf(item: MediaItem): Song? {
            val contentUri = contentUriOf(item) ?: return null
            if (CastStreamRelay.trackIdOf(contentUri) == null) return null
            val extras = item.mediaMetadata.extras
            val metadata = item.mediaMetadata
            return Song(
                id = item.mediaId,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                artistId = -1L,
                album = extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM) ?: metadata.albumTitle?.toString().orEmpty(),
                albumId = -1L,
                path = "",
                contentUriString = contentUri,
                albumArtUriString = extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART) ?: metadata.artworkUri?.toString(),
                duration = extras?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION) ?: 0L,
                mimeType = null,
                bitrate = null,
                sampleRate = null,
                spotifyId = extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_SPOTIFY_ID),
            )
        }
    }
}

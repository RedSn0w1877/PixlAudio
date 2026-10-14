package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * The transport commands (play/pause, previous, next, seek) in one place, so the mini player, the
 * full player, the lock screen and Wear all behave the same on a player that is not "playing" yet.
 *
 * Why this exists: every song is a Spotify stream now. Between a tap and the first audio the
 * player sits in BUFFERING for as long as the song takes to be matched and resolved, and after a
 * failed resolve it sits in IDLE. The old handlers decided on `isPlaying`, which is false in both
 * states, so a pause during loading turned into a no-op `play()`, and a skip, a rewind or a play
 * on the IDLE player was either dropped by the controller (the commands are unavailable until the
 * item is prepared and seekable) or moved the index without ever preparing it.
 */
object PlayerTransport {

    /** How far into a song "previous" restarts it instead of going back a song. */
    const val RESTART_THRESHOLD_MS = 10_000L

    /**
     * True when the player is set to play and is not stopped: a song that is still loading counts,
     * because the user already asked for it. This is what the play/pause button should show and act on.
     */
    fun isPlayIntent(player: Player): Boolean =
        player.playWhenReady && (
            player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            )

    /** An IDLE player that holds songs (after an error, or a queue restored paused) is prepared. */
    fun ensurePrepared(player: Player): Boolean {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.prepare()
            return true
        }
        return false
    }

    /** Pauses when the user asked for playback (even while it loads), otherwise starts it. */
    fun togglePlayPause(player: Player): Boolean {
        if (isPlayIntent(player)) {
            player.pause()
            return false
        }
        play(player)
        return true
    }

    fun play(player: Player) {
        ensurePrepared(player)
        if (player.playbackState == Player.STATE_ENDED && player.mediaItemCount > 0) {
            player.seekToDefaultPosition()
        }
        player.play()
    }

    fun skipNext(player: Player) {
        ensurePrepared(player)
        player.seekToNext()
    }

    fun skipPrevious(player: Player, pending: PendingSeek? = null) {
        ensurePrepared(player)
        if (player.currentPosition > RESTART_THRESHOLD_MS) {
            seekInCurrent(player, 0L, pending)
        } else {
            player.seekToPrevious()
        }
    }

    /**
     * Seeks inside the current song. A song that is still loading is not seekable yet and the
     * controller silently drops the command, so the target is held in [pending] and applied by
     * [PendingSeek.applyIfReady] once the player can take it. Returns true when it was applied now.
     */
    fun seekInCurrent(player: Player, positionMs: Long, pending: PendingSeek? = null): Boolean {
        val target = positionMs.coerceAtLeast(0L)
        ensurePrepared(player)
        if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            pending?.clear()
            player.seekTo(target)
            return true
        }
        pending?.hold(player.currentMediaItem?.mediaId, target)
        return false
    }
}

/** A seek asked for before the song was seekable, applied as soon as it is. */
class PendingSeek {
    private var mediaId: String? = null
    private var positionMs: Long = C.TIME_UNSET

    val isHeld: Boolean get() = positionMs != C.TIME_UNSET

    fun hold(mediaId: String?, positionMs: Long) {
        this.mediaId = mediaId
        this.positionMs = positionMs
    }

    fun clear() {
        mediaId = null
        positionMs = C.TIME_UNSET
    }

    /** Applies the held seek when the same song is ready and seekable; drops it when the song changed. */
    fun applyIfReady(player: Player): Boolean {
        if (!isHeld) return false
        if (player.currentMediaItem?.mediaId != mediaId) {
            clear()
            return false
        }
        if (player.playbackState != Player.STATE_READY ||
            !player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        ) {
            return false
        }
        val target = positionMs
        clear()
        player.seekTo(target)
        return true
    }
}

/**
 * Decides what to do when the player fails on a song. A failed song used to leave a silent,
 * IDLE player behind; now playback moves on to the next song, but gives up after a few failures
 * in a row (offline, YouTube blocked) instead of racing through the whole queue.
 */
class PlaybackErrorSkipPolicy(
    private val maxConsecutiveSkips: Int = 3,
    private val windowMs: Long = 60_000L,
    private val retryInPlaceFromMs: Long = 5_000L,
) {
    enum class Decision { RETRY_CURRENT, SKIP_TO_NEXT, STOP }

    private val failures = ArrayDeque<Long>()
    private var retriedMediaId: String? = null

    /**
     * A song that already played for a while and then lost its stream (a dropped connection, an
     * expired link) is retried in place once, from where it stopped. Anything else moves on.
     */
    fun onError(nowMs: Long, hasNext: Boolean, mediaId: String? = null, positionMs: Long = 0L): Decision {
        if (mediaId != null && positionMs >= retryInPlaceFromMs && retriedMediaId != mediaId) {
            retriedMediaId = mediaId
            return Decision.RETRY_CURRENT
        }
        while (failures.isNotEmpty() && nowMs - failures.first() > windowMs) failures.removeFirst()
        failures.addLast(nowMs)
        return if (hasNext && failures.size <= maxConsecutiveSkips) Decision.SKIP_TO_NEXT else Decision.STOP
    }

    /** Audio is playing again: the next failure starts a fresh count. */
    fun onPlaybackStarted() {
        failures.clear()
    }
}

/**
 * Holds skips tapped in the moment between a song starting and the rest of its queue being
 * attached (the player then has one song, so "next" has nothing to go to), and replays them once
 * the queue is there. Tokens keep a superseded attach from clearing a newer one.
 */
class QueueAttachGate(private val maxDeferred: Int = 5) {
    enum class Skip { NEXT, PREVIOUS }

    private var token = 0
    private var attaching = false
    private val deferred = ArrayDeque<Skip>()

    val isAttaching: Boolean get() = attaching

    fun begin(): Int {
        token++
        attaching = true
        deferred.clear()
        return token
    }

    /** True when [skip] was held back and will be replayed by [end]. */
    fun deferIfAttaching(skip: Skip): Boolean {
        if (!attaching) return false
        if (deferred.size < maxDeferred) deferred.addLast(skip)
        return true
    }

    /** The queue is attached: returns the skips to replay, in order. A stale token returns nothing. */
    fun end(token: Int): List<Skip> {
        if (token != this.token) return emptyList()
        attaching = false
        return deferred.toList().also { deferred.clear() }
    }

    /** The attach was cancelled or superseded: forget the held skips. */
    fun abandon(token: Int) {
        if (token != this.token) return
        attaching = false
        deferred.clear()
    }
}

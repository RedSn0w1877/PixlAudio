package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.Route
import com.theveloper.pixelplay.brain.StartReason
import com.theveloper.pixelplay.brain.Surface

/**
 * What the service knows about a play when it starts (docs/brain-spec.md, 4.2): which item it is, how
 * it came to play, and where it was started from. Built from the `MediaItem` in
 * `MusicService.syncLocalListeningStatsFromPlayer` and kept on the tracker's session, so the event
 * recorder gets raw facts and never has to guess.
 */
data class PlayContext(
    val title: String,
    /** The item's artist line as shown: may name several artists. */
    val artistDisplay: String,
    /** ONE artist name when the item carries it (engine items do); null means "look it up". */
    val primaryArtist: String? = null,
    /** A Spotify track id or a synthetic 22-hex id; null for a local file. */
    val spotifyId: String? = null,
    /** `StartReason` code. */
    val startReason: Int = StartReason.UNKNOWN,
    /** Surface, surface id, list id, slot and arm, from the `brain.*` extras; null when there are none. */
    val tag: BrainItemTag? = null,
    val retrQ: Int = -1,
    val radioRank: Int = -1,
    val shuffle: Boolean = false,
    val repeatOne: Boolean = false,
    /** `Route` code. */
    val route: Int = Route.UNKNOWN,
    val offline: Boolean = false,
) {
    val isEngineOwned: Boolean get() = tag?.isEngineOwned == true
}

/** One play attempt as the tracker saw it end: raw facts, nothing derived. */
data class FinishedPlay(
    /** The media id the item played under. */
    val songId: String,
    val context: PlayContext,
    /** Wall clock, UTC, when the play started. */
    val startedAtMs: Long,
    /** Audible milliseconds (time spent playing, not the position). */
    val playedMs: Long,
    /** 0 when unknown. */
    val durationMs: Long,
    /** `EndReason` code. */
    val endReason: Int,
    /** `EventFlags` bits set while it played (liked, replayed, ...); shuffle and repeat-one are added by the recorder. */
    val flags: Int = 0,
    /** Minutes east of UTC when it started. */
    val tzOffsetMin: Int = 0,
)

/**
 * Where the tracker sends what it sees. The brain's recorder implements it; tests and the Cast path
 * use [NONE].
 */
interface PlaySink {
    /** A play ended. Called for EVERY play, however short: there is no five-second floor here. */
    fun onPlayFinished(play: FinishedPlay)

    /**
     * The play in flight, as far as it got (called when it starts and when it pauses); null clears it.
     * It is written down so that a play cut off by a process death is still recorded, as `killed`.
     */
    fun onPlayOpen(play: FinishedPlay?)

    /**
     * True when [songId] is a track from outside the library that has no library row. The legacy
     * counters (`feedback_v1`, `song_engagements`) are not written for such a play (4.2).
     */
    suspend fun isOutsideLibrary(songId: String, context: PlayContext?): Boolean

    companion object {
        val NONE = object : PlaySink {
            override fun onPlayFinished(play: FinishedPlay) = Unit
            override fun onPlayOpen(play: FinishedPlay?) = Unit
            override suspend fun isOutsideLibrary(songId: String, context: PlayContext?): Boolean = false
        }
    }
}

/**
 * The reason table of docs/brain-spec.md, 4.2: what a player event says about the play that ends and
 * the one that starts. Pure, so the table is unit-tested without a player.
 */
object PlayReasons {
    // Media3 `Player.MEDIA_ITEM_TRANSITION_REASON_*`, repeated here so this file needs no player.
    const val TRANSITION_REPEAT = 0
    const val TRANSITION_AUTO = 1
    const val TRANSITION_SEEK = 2
    const val TRANSITION_PLAYLIST_CHANGED = 3

    class Reasons(val end: Int, val start: Int)

    /**
     * [oldIndex] and [newIndex] are ABSOLUTE queue indices (`engine.getCurrentAbsoluteIndex()`), -1
     * when unknown: after a crossfade the player's own index is an index into a 200-item window.
     * [oldQueueSize] and [newQueueSize] tell a queue edit (the current item was removed: one item
     * fewer) from a new queue.
     */
    fun forTransition(
        reason: Int,
        oldIndex: Int,
        newIndex: Int,
        oldQueueSize: Int,
        newQueueSize: Int,
        newItemIsEnginePick: Boolean,
        isRestoring: Boolean,
    ): Reasons = when (reason) {
        TRANSITION_AUTO ->
            Reasons(EndReason.TRACK_DONE, if (newItemIsEnginePick) StartReason.AUTOPLAY else StartReason.AUTO_ADVANCE)
        TRANSITION_REPEAT -> Reasons(EndReason.TRACK_DONE, StartReason.AUTO_ADVANCE)
        TRANSITION_SEEK -> when {
            oldIndex >= 0 && newIndex == oldIndex + 1 -> Reasons(EndReason.NEXT, StartReason.NEXT)
            oldIndex >= 0 && newIndex == oldIndex - 1 -> Reasons(EndReason.PREV, StartReason.PREV)
            // 4.1 has no "pickedOther" start reason: picking an item of the queue is a tap.
            else -> Reasons(EndReason.PICKED_OTHER, StartReason.TAP)
        }
        TRANSITION_PLAYLIST_CHANGED -> {
            val removedCurrent = oldQueueSize > 1 && newQueueSize == oldQueueSize - 1
            Reasons(
                if (removedCurrent) EndReason.QUEUE_EDIT else EndReason.PICKED_OTHER,
                if (isRestoring) StartReason.RESTORE else StartReason.TAP,
            )
        }
        else -> Reasons(EndReason.UNKNOWN, StartReason.UNKNOWN)
    }

    /** A crossfade hand-over publishes the next player: the old track played out, the new one follows on its own. */
    fun forHandOver(newItemIsEnginePick: Boolean): Reasons =
        Reasons(EndReason.TRACK_DONE, if (newItemIsEnginePick) StartReason.AUTOPLAY else StartReason.AUTO_ADVANCE)

    /**
     * True when the player that is published holds the very track a running hand-over was leaving:
     * the hand-over was cancelled and the old track is back. [leavingMediaId] is null when no
     * hand-over runs.
     */
    fun isCancelledHandOver(leavingMediaId: String?, publishedMediaId: String?): Boolean =
        leavingMediaId != null && leavingMediaId == publishedMediaId

    /**
     * A cancelled hand-over: the incoming track was heard for a moment and then the old one came back.
     * That is not a finished play and not a skip. `unknown` with under 30 s heard weighs nothing (4.3).
     */
    fun forCancelledHandOver(): Reasons = Reasons(EndReason.UNKNOWN, StartReason.UNKNOWN)

    /**
     * True when a `PLAYLIST_CHANGED` transition is the same play going on: the queue was rebuilt
     * around the track that is playing (shuffle switched off, a queue replaced in one go), so the
     * track and its position are what they were. Tapping the same song again starts it from the top,
     * which is a new play, unless the old one had barely begun.
     */
    fun continuesSamePlay(reason: Int, oldMediaId: String?, newMediaId: String?, oldPositionMs: Long, newPositionMs: Long): Boolean {
        if (reason != TRANSITION_PLAYLIST_CHANGED) return false
        if (oldMediaId == null || oldMediaId != newMediaId) return false
        val moved = newPositionMs - oldPositionMs
        return moved > -SAME_PLAY_TOLERANCE_MS && moved < SAME_PLAY_TOLERANCE_MS
    }

    private const val SAME_PLAY_TOLERANCE_MS = 3_000L
}

/**
 * Where a play the UI starts comes from, as far as the dispatch path knows (docs/brain-spec.md, 4.2).
 * The dispatch path has a playlist id and a display name of the queue, nothing more: a playlist is
 * recognised by its id, and Liked Songs, Your Mix, Daily Mix and Taizo by the names the app itself
 * passes. Everything else (an album, an artist, search) is `unknown`, which Reward treats like any
 * other surface the listener picked from.
 */
object BrainSurfaces {
    const val QUEUE_LIKED = "Liked Songs"
    const val QUEUE_YOUR_MIX = "Your Mix"
    const val QUEUE_DAILY_MIX_PREFIX = "Daily Mix"
    const val QUEUE_TAIZO = "Taizo"

    fun forQueue(queueName: String?, playlistId: String?): BrainItemTag? {
        if (!playlistId.isNullOrBlank()) {
            return BrainItemTag(surface = Surface.nameOf(Surface.PLAYLIST), surfaceId = hashedId(playlistId))
        }
        val surface = when {
            queueName == null -> return null
            queueName == QUEUE_LIKED -> Surface.LIKED
            queueName == QUEUE_YOUR_MIX -> Surface.YOUR_MIX
            queueName.startsWith(QUEUE_DAILY_MIX_PREFIX) -> Surface.DAILY_MIX
            queueName == QUEUE_TAIZO -> Surface.ASSISTANT
            else -> return null
        }
        return BrainItemTag(surface = Surface.nameOf(surface))
    }

    /** A track the listener put in the queue by hand ("Add to queue", "Play next"). */
    val manualQueue: BrainItemTag = BrainItemTag(surface = Surface.nameOf(Surface.MANUAL_QUEUE))

    /** A playlist id is the listener's own data: the log keeps a hash of it, not the id. */
    fun hashedId(id: String): String = java.lang.Long.toHexString(BrainHash.fnv1a64(id))
}

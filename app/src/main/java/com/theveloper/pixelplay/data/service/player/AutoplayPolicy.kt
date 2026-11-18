package com.theveloper.pixelplay.data.service.player

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainText

/**
 * The decisions of Autoplay (docs/brain-spec.md, 7.1, 6.1 filters, 6.4 hard constraints), as pure
 * functions: when the queue is topped up, when a queue edit must wait, and which tracks of the pool go
 * in, in what order. No player, no clock, no I/O: [AutoplayController] supplies the facts and carries
 * out the answer.
 *
 * Which tracks of a radio pool are picked, and in what order, is `StationPlanner` in `:brain`. This
 * file keeps the trigger, the filters that decide what may enter a pool at all, where a re-score may
 * start, and [pickBatch], the plain in-order pick the library-only pool still uses (offline, or with
 * the YouTube source off: those tracks have no recording ids).
 */
object AutoplayPolicy {

    /** Top up when this many tracks or fewer are left after the current one. */
    const val TRIGGER_REMAINING = 2

    /** Tracks appended per queue edit: one edit, because each edit republishes the whole queue. */
    const val BATCH_SIZE = 5

    /** A radio request starts no earlier than this far into a track: nothing competes with its first bytes. */
    const val SETTLE_MS = 1_500L

    /** After the queue ended with no picks ready, picks that land within this window resume playback. */
    const val BACKSTOP_WINDOW_MS = 10_000L

    /** The pool is refilled in the background when it drops under this. */
    const val POOL_REFILL_BELOW = 15

    /** Session anchors: the last tracks the listener chose. */
    const val MAX_ANCHORS = 3

    const val MIN_DURATION_MS = 60_000L
    const val MAX_DURATION_MS = 900_000L

    /** 6.4: the same primary artist not within this many slots. */
    const val ARTIST_GAP = 4

    /** 6.4: the same album not within this many slots. */
    const val ALBUM_GAP = 8

    private const val REPEAT_MODE_OFF = 0

    // ── Trigger ──────────────────────────────────────────────────────────────

    enum class Decision {
        TOP_UP,

        /** The Autoplay switch is off. */
        OFF,

        /** Repeat-one or repeat-all is on: the queue already never ends. */
        REPEATING,

        /** Cast or Spotify Connect plays: autoplay is off during remote sessions in stage 1. */
        REMOTE,

        /** A saved queue is being restored; its own engine items come back with it. */
        RESTORING,

        /**
         * A song was tapped in an album or a playlist and the rest of that queue is still on its way to
         * the player: for a moment the queue is one item long. Nothing is decided until it is whole.
         */
        ATTACHING,
        NO_QUEUE,
        ENOUGH_QUEUED,
    }

    /**
     * How many tracks are left after the current one. Both arguments are about the FULL queue
     * (`engine.getFullQueue().size`, `engine.getCurrentAbsoluteIndex()`): after a crossfade the active
     * player can hold only a 200-item window of a longer queue, so the player's own count and index
     * would say "two left" in the middle of it.
     */
    fun remainingAfterCurrent(fullQueueSize: Int, currentAbsoluteIndex: Int): Int =
        (fullQueueSize - currentAbsoluteIndex - 1).coerceAtLeast(0)

    fun decide(
        autoplayEnabled: Boolean,
        repeatMode: Int,
        isRemoteSession: Boolean,
        isRestoring: Boolean,
        fullQueueSize: Int,
        currentAbsoluteIndex: Int,
        isAttachingQueue: Boolean = false,
    ): Decision = when {
        !autoplayEnabled -> Decision.OFF
        isRemoteSession -> Decision.REMOTE
        isRestoring -> Decision.RESTORING
        repeatMode != REPEAT_MODE_OFF -> Decision.REPEATING
        isAttachingQueue -> Decision.ATTACHING
        fullQueueSize <= 0 || currentAbsoluteIndex < 0 -> Decision.NO_QUEUE
        remainingAfterCurrent(fullQueueSize, currentAbsoluteIndex) > TRIGGER_REMAINING -> Decision.ENOUGH_QUEUED
        else -> Decision.TOP_UP
    }

    /**
     * A queue edit must not land while `DualPlayerEngine` hands one player over to the other: it is
     * held and released from the engine's transition-finished listener.
     */
    fun mustDeferQueueEdit(isTransitionRunning: Boolean): Boolean = isTransitionRunning

    /**
     * A batch that was held during a hand-over may go in only if the queue is still the one it was
     * planned for: the same pool, the same length, the same last track, and still in need of a top-up.
     * When the listener starts another queue during the fade the hand-over is cancelled, and picks
     * planned for the old queue must not land in the new one.
     */
    fun heldBatchStillFits(
        decision: Decision,
        plannedPoolKey: String,
        poolKey: String,
        plannedQueueSize: Int,
        queueSize: Int,
        plannedLastMediaId: String?,
        lastMediaId: String?,
    ): Boolean = decision == Decision.TOP_UP && plannedPoolKey == poolKey &&
        plannedQueueSize == queueSize && plannedLastMediaId == lastMediaId

    /**
     * Whether a change of direction (three skipped picks) decides the seeds right now. It does while
     * the engine's own picks play. When the track that is playing is one the listener chose, they are
     * steering: autoplay follows that track, whatever was skipped before. [turn] is 0 for "not turned";
     * [reactionIsFresh] is false once the session that turned has gone quiet.
     */
    fun turnApplies(turn: Int, reactionIsFresh: Boolean, currentIsListenersChoice: Boolean): Boolean =
        turn != 0 && reactionIsFresh && !currentIsListenersChoice

    /**
     * True when a library track plays with no network: a file of the device, or a track with a
     * complete copy in the audio cache ([cachedIds], by Spotify id). Everything else is a stream.
     */
    fun playsWithoutNetwork(contentUri: String, spotifyId: String?, cachedIds: Set<String>): Boolean {
        if (spotifyId != null && spotifyId in cachedIds) return true
        if (contentUri.startsWith("/")) return true
        return when (contentUri.substringBefore("://", "")) {
            "content", "file", "android.resource" -> true
            else -> false
        }
    }

    /**
     * The first index in `[start, size)` whose item is by [artistKey], or -1. From there on the caller
     * rebuilds the tail without that artist, in one queue edit.
     */
    fun firstIndexOfArtist(start: Int, size: Int, artistKey: String, artistKeyAt: (Int) -> String): Int {
        if (artistKey.isEmpty() || start < 0) return -1
        for (i in start until size) if (artistKeyAt(i) == artistKey) return i
        return -1
    }

    /** The current track is far enough in for a radio request not to compete with its first bytes. */
    fun isSettled(positionMs: Long): Boolean = positionMs >= SETTLE_MS

    /**
     * Whether a pool may be built from the network at all. Offline, or with the YouTube source switched
     * off, the pool is the cache and then the library, and the queue says "from your library".
     */
    fun mayUseNetwork(online: Boolean, youTubeSourceEnabled: Boolean): Boolean = online && youTubeSourceEnabled

    /**
     * The backstop: the queue ended before any pick was ready. Picks that arrive now are appended and
     * playback resumes only inside the window and only if the listener did not press pause meanwhile.
     */
    fun backstopMayResume(endedAtMs: Long, nowMs: Long, playWhenReady: Boolean): Boolean =
        playWhenReady && nowMs >= endedAtMs && nowMs - endedAtMs <= BACKSTOP_WINDOW_MS

    /**
     * Next was pressed with nothing queued, and picks have landed. The player moves on to them only
     * inside the backstop window and only if the track the press was about is still the current one.
     */
    fun nextMayStillSkip(askedAtMs: Long, nowMs: Long, askedMediaId: String?, currentMediaId: String?): Boolean =
        askedMediaId != null && askedMediaId == currentMediaId &&
            nowMs >= askedAtMs && nowMs - askedAtMs <= BACKSTOP_WINDOW_MS

    // ── Candidates ───────────────────────────────────────────────────────────

    /** One track the pool could play, reduced to what the rules read. */
    data class Candidate(
        /** The media id it would play under. */
        val mediaId: String,
        /** Recording id; two candidates with one rid are the same recording. */
        val rid: Long,
        /** Folded primary artist; "" when unknown. */
        val artistKey: String,
        /** Folded album name; null when unknown. */
        val albumKey: String?,
        /** 0 when unknown (Related-tab rows carry no length). */
        val durationMs: Long,
        /** `BrainText` version flags of the title. */
        val versionFlags: Int,
        val officialAudio: Boolean,
        /** True when the recording is a library track (it plays from the library row). */
        val inLibrary: Boolean,
        /** `Arm` code. */
        val arm: Int,
        /** 1-based rank in the radio that produced it; 0 when it did not come from a radio. */
        val radioRank: Int,
    )

    /** One engine-owned slot already in the queue, oldest first. */
    data class Slot(val artistKey: String, val albumKey: String?)

    private const val NEVER_FLAGS =
        BrainText.FLAG_KARAOKE or BrainText.FLAG_COVER or BrainText.FLAG_SPED_UP or BrainText.FLAG_SLOWED
    private const val SEED_MATCHED_FLAGS =
        BrainText.FLAG_LIVE or BrainText.FLAG_INSTRUMENTAL or BrainText.FLAG_REMIX

    /**
     * The filters of 6.1 that depend only on the candidate and the seeds. [seedFlags] is the union of the
     * anchors' version flags: a live, instrumental or remix candidate is allowed only when a seed is one.
     * A library track is the listener's own choice of version, so the version filters do not apply to it.
     */
    fun passesFilters(candidate: Candidate, seedFlags: Int): Boolean {
        if (candidate.durationMs != 0L &&
            (candidate.durationMs < MIN_DURATION_MS || candidate.durationMs > MAX_DURATION_MS)
        ) return false
        if (candidate.inLibrary) return true
        if (!candidate.officialAudio) return false
        if (candidate.versionFlags and NEVER_FLAGS != 0) return false
        val needsSeed = candidate.versionFlags and SEED_MATCHED_FLAGS
        if (needsSeed != 0 && needsSeed and seedFlags != needsSeed) return false
        return true
    }

    /**
     * Picks up to [count] tracks from [pool], in pool order, under the hard list constraints of 6.4:
     * no recording twice, nothing in [excludedMediaIds] or [excludedRids] (already queued, played in the
     * last 4 hours, ignored three times), the same primary artist not within [ARTIST_GAP] slots and at
     * most 2 per 20 and 3 per 50, the same album not within [ALBUM_GAP] slots.
     *
     * The spacing rules run over the engine's own list: [engineTail] (engine items already in the queue,
     * oldest first) followed by what this call picks. Tracks the listener queued are not part of it.
     * Returns fewer than [count] when the pool cannot fill the batch without breaking a rule.
     */
    fun pickBatch(
        pool: List<Candidate>,
        engineTail: List<Slot>,
        excludedMediaIds: Set<String>,
        excludedRids: Set<Long>,
        count: Int = BATCH_SIZE,
    ): List<Candidate> {
        if (count <= 0 || pool.isEmpty()) return emptyList()
        val list = ArrayList<Slot>(engineTail.size + count)
        list.addAll(engineTail)
        val picked = ArrayList<Candidate>(count)
        val usedRids = HashSet<Long>()
        val usedMediaIds = HashSet<String>()
        while (picked.size < count) {
            var chosen: Candidate? = null
            for (candidate in pool) {
                if (candidate.mediaId in excludedMediaIds || candidate.mediaId in usedMediaIds) continue
                if (candidate.rid != 0L && (candidate.rid in excludedRids || candidate.rid in usedRids)) continue
                if (!fitsAfter(list, candidate)) continue
                chosen = candidate
                break
            }
            val next = chosen ?: break
            picked.add(next)
            usedMediaIds.add(next.mediaId)
            if (next.rid != 0L) usedRids.add(next.rid)
            list.add(Slot(next.artistKey, next.albumKey))
        }
        return picked
    }

    /** True when [candidate] may take the slot after [list] without breaking an artist or album rule. */
    fun fitsAfter(list: List<Slot>, candidate: Candidate): Boolean {
        val size = list.size
        val artist = candidate.artistKey
        if (artist.isNotEmpty()) {
            var within20 = 0
            var within50 = 0
            var back = 1
            while (back <= 49 && size - back >= 0) {
                if (list[size - back].artistKey == artist) {
                    if (back <= ARTIST_GAP) return false
                    if (back <= 19) within20++
                    within50++
                }
                back++
            }
            // Counting this slot: at most 2 in any 20, 3 in any 50.
            if (within20 >= 2 || within50 >= 3) return false
        }
        val album = candidate.albumKey
        if (!album.isNullOrEmpty()) {
            var back = 1
            while (back <= ALBUM_GAP && size - back >= 0) {
                if (list[size - back].albumKey == album) return false
                back++
            }
        }
        return true
    }

    /**
     * Merges the radios of several anchors into one pool order: rank by rank, newest anchor first, so the
     * first pick is the best row of the newest anchor and no anchor's radio drowns the others. A
     * recording that several radios name keeps its first (best) position.
     */
    fun interleave(lists: List<List<Candidate>>): List<Candidate> {
        if (lists.isEmpty()) return emptyList()
        if (lists.size == 1) return dedupe(lists[0])
        var longest = 0
        for (list in lists) if (list.size > longest) longest = list.size
        val merged = ArrayList<Candidate>(longest * lists.size)
        for (rank in 0 until longest) {
            for (list in lists) if (rank < list.size) merged.add(list[rank])
        }
        return dedupe(merged)
    }

    private fun dedupe(candidates: List<Candidate>): List<Candidate> {
        val seenRids = HashSet<Long>(candidates.size * 2)
        val seenMediaIds = HashSet<String>(candidates.size * 2)
        val out = ArrayList<Candidate>(candidates.size)
        for (candidate in candidates) {
            if (!seenMediaIds.add(candidate.mediaId)) continue
            if (candidate.rid != 0L && !seenRids.add(candidate.rid)) continue
            out.add(candidate)
        }
        return out
    }

    /**
     * The anchors of a queue (7.1, stage 1a form): the last [MAX_ANCHORS] distinct tracks the listener
     * chose, at or before the current position, newest first. Tracks the engine picked are never
     * anchors, so an hour of autoplay is still the radio of what the listener chose, not the radio of
     * a radio. [engineOwned] says, per queue index, whether the engine put that item there.
     */
    fun anchorIndices(
        currentAbsoluteIndex: Int,
        queueSize: Int,
        engineOwned: (Int) -> Boolean,
        mediaIdAt: (Int) -> String,
        max: Int = MAX_ANCHORS,
    ): List<Int> {
        if (queueSize <= 0 || max <= 0) return emptyList()
        val out = ArrayList<Int>(max)
        val seen = HashSet<String>()
        var i = currentAbsoluteIndex.coerceAtMost(queueSize - 1)
        while (i >= 0 && out.size < max) {
            if (!engineOwned(i) && seen.add(mediaIdAt(i))) out.add(i)
            i--
        }
        return out
    }

    // ── Re-scoring the tail (7.3, decision D8) ───────────────────────────────

    /**
     * How many tracks after the current one the stream prefetcher has warmed: the next one on cellular,
     * the next two on unmetered Wi-Fi. A re-score never replaces them, so a skip never changes the
     * track that starts because of it.
     */
    fun warmedAhead(unmeteredWifi: Boolean): Int = if (unmeteredWifi) 2 else 1

    /**
     * The first queue index a re-score may replace, or -1 when there is nothing to re-score. It lies
     * after the current track and the [warmedAhead] ones, and after the last track the listener put in
     * the queue: only an unbroken run of engine items at the very end is the engine's to change.
     * Indices are into the FULL queue.
     */
    fun rescoreStart(queueSize: Int, currentAbsoluteIndex: Int, warmedAhead: Int, engineOwned: (Int) -> Boolean): Int {
        if (queueSize <= 0 || currentAbsoluteIndex < 0) return -1
        var start = currentAbsoluteIndex + 1 + warmedAhead.coerceAtLeast(1)
        if (start >= queueSize) return -1
        var i = queueSize - 1
        while (i >= start) {
            if (!engineOwned(i)) {
                start = i + 1
                break
            }
            i--
        }
        return if (start >= queueSize) -1 else start
    }

    /** The arm of a library track the pool plays: FAV when it was ever played, DEEP when it never was. */
    fun libraryArm(everPlayed: Boolean): Int = if (everPlayed) Arm.FAV else Arm.DEEP
}

package com.theveloper.pixelplay.brain

/**
 * What the listener is doing right now (docs/brain-spec.md, 5.5): short lists and counters, in
 * memory only, reset after 30 minutes without a play. It holds recording and artist ids, never
 * vectors (decision D7), so it needs no embedding and is trivially the same on both platforms.
 */
class SessionState(
    /** The surface's novelty share at the start of the session. */
    val nuStart: Double = Session.NU_DEFAULT,
) {
    var sessionId: Long = 0L
    /** Plays started in this session so far. */
    var plays: Int = 0
    var passiveRun: Int = 0
    /** When the last recorded play ended. */
    var lastEndMs: Long = 0L

    /** Newest first. A liked track is pushed twice. */
    val recentPos = LongArray(Session.RECENT)
    val recentPosAid = LongArray(Session.RECENT)
    var posCount = 0

    /** Newest first, with the strength of each skip. */
    val recentNeg = LongArray(Session.RECENT)
    val recentNegAid = LongArray(Session.RECENT)
    val recentNegH = DoubleArray(Session.RECENT)
    var negCount = 0

    /** Artists not to queue for a while: `blockLeft` tracks, or [Session.REST_OF_SESSION]. Insertion order. */
    val blockAid = ArrayList<Long>()
    val blockLeft = ArrayList<Int>()

    /** Hard skips (`h >= 0.6`) per artist in this session. Insertion order. */
    val skippedAid = ArrayList<Long>()
    val skippedCount = ArrayList<Int>()

    var skipRun = 0
    var lastCompleted = 0L
    var lastCompletedAid = 0L
    /** The previous track was finished (feature 25). */
    var prevCompleted = false
    var nuSession: Double = nuStart

    /** The station or autoplay seed; 0 when there is none. */
    var seed = 0L
    var seedAid = 0L
    var seedKind = Session.SEED_NONE
    /** A station's own seed artist after skips: 0 no limit, 1 one track in six, 2 none; for `seedLimitLeft` tracks. */
    var seedLimit = 0
    var seedLimitLeft = 0

    /** Newest first: tracks the listener chose and heard, and engine picks they liked, saved or replayed. */
    val anchors = LongArray(Session.MAX_ANCHORS)
    val anchorsAid = LongArray(Session.MAX_ANCHORS)
    var anchorCount = 0

    /** Two hard skips in a row: the next engine pick must be familiar and by [lastCompletedAid]. */
    var familiarNext = false
    /** Bumped at every third hard skip in a row: the engine changes direction (the next anchor). */
    var reseedCount = 0

    /** A track was liked: one track by the same artist (or its nearest neighbour) goes into the next [likedWithin] slots. */
    var likedRid = 0L
    var likedAid = 0L
    var likedWithin = 0

    fun isBlocked(aid: Long): Boolean {
        if (aid == 0L) return false
        for (i in blockAid.indices) if (blockAid[i] == aid) return true
        return false
    }

    /** Hard skips of [aid] in this session. */
    fun skipsOf(aid: Long): Int {
        for (i in skippedAid.indices) if (skippedAid[i] == aid) return skippedCount[i]
        return 0
    }

    /** One canonical JSON object: the whole state, for the golden vectors and for debugging. */
    fun encode(): String {
        val w = BrainJson.Writer().beginObject()
        w.long("sessionId", sessionId).int("plays", plays).int("passiveRun", passiveRun).long("lastEndMs", lastEndMs)
        w.longs("recentPos", recentPos.copyOf(posCount))
        w.longs("recentNeg", recentNeg.copyOf(negCount))
        w.beginArray("recentNegH")
        for (i in 0 until negCount) w.doubleItem(recentNegH[i])
        w.endArray()
        w.longs("blockAid", blockAid.toLongArray()).ints("blockLeft", blockLeft.toIntArray())
        w.longs("skippedAid", skippedAid.toLongArray()).ints("skippedCount", skippedCount.toIntArray())
        w.int("skipRun", skipRun).long("lastCompleted", lastCompleted).bool("prevCompleted", prevCompleted)
        w.double("nuSession", nuSession)
        w.int("seedLimit", seedLimit).int("seedLimitLeft", seedLimitLeft)
        w.longs("anchors", anchors.copyOf(anchorCount))
        w.bool("familiarNext", familiarNext).int("reseedCount", reseedCount)
        w.long("likedRid", likedRid).int("likedWithin", likedWithin)
        return w.endObject().toString()
    }
}

/** The session rules of 5.5 and 7.3. Pure: state in, state out, no clock. */
object Session {
    const val RECENT = 5
    const val MAX_ANCHORS = 3
    const val GAP_MS = 30L * 60 * 1000
    const val NU_DEFAULT = 0.25
    const val NU_FLOOR = 0.08
    const val NU_CEILING = 0.6
    const val BLOCK_TRACKS = 10
    const val REST_OF_SESSION = -1
    const val HARD_SKIP = 0.6
    const val LIKED_WITHIN = 5

    const val SEED_NONE = 0
    const val SEED_AUTOPLAY = 1
    const val SEED_STATION_SONG = 2
    const val SEED_STATION_ARTIST = 3

    /** What a play that starts now is stamped with (4.1 `sessionId`, `posInSession`, `passiveRun`). */
    class Stamp(val sessionId: Long, val posInSession: Int, val passiveRun: Int)

    /** True when a play starting at [startedAtMs] opens a new session. */
    fun isExpired(state: SessionState, startedAtMs: Long): Boolean =
        state.sessionId == 0L || startedAtMs - state.lastEndMs > GAP_MS

    /**
     * Stamps a play that starts at [startedAtMs]. The caller replaces an expired state with a fresh one
     * first ([isExpired]); a state that never played becomes a session here.
     */
    fun begin(state: SessionState, startedAtMs: Long, startReason: Int): Stamp {
        if (state.sessionId == 0L) state.sessionId = if (startedAtMs != 0L) startedAtMs else 1L
        val pos = state.plays
        state.plays = pos + 1
        state.passiveRun =
            if (startReason == StartReason.AUTO_ADVANCE || startReason == StartReason.AUTOPLAY) state.passiveRun + 1 else 0
        // A play that has started keeps the session alive even before it ends.
        if (startedAtMs > state.lastEndMs) state.lastEndMs = startedAtMs
        return Stamp(state.sessionId, if (pos > 32767) 32767 else pos, if (state.passiveRun > 32767) 32767 else state.passiveRun)
    }

    /**
     * Applies one finished play. [novel] says the listener had never played the track before this play.
     * An event with `c = 0` changes nothing except [SessionState.lastCompleted] and the block countdown.
     */
    fun apply(state: SessionState, e: PlayEvent, g: Reward.Graded, novel: Boolean) {
        if (e.endedAtMs > state.lastEndMs) state.lastEndMs = e.endedAtMs
        countDown(state)
        val notForMe = e.has(EventFlags.NOT_FOR_ME)
        val liked = !notForMe && e.flags and
            (EventFlags.LIKED_DURING or EventFlags.SAVED_DURING or EventFlags.ADDED_TO_PLAYLIST or EventFlags.REPLAYED) != 0
        if (g.c == 0.0) {
            if (g.isCompletion) {
                state.lastCompleted = e.rid
                state.lastCompletedAid = e.primaryAid
            }
            return
        }
        val h = if (notForMe) 1.0 else g.h
        if (h > 0.0) {
            pushNeg(state, e.rid, e.primaryAid, h)
            state.prevCompleted = false
            if (h >= HARD_SKIP) {
                state.skipRun++
                if (e.primaryAid != 0L) hardSkipOfArtist(state, e.primaryAid)
                if (novel) state.nuSession = BrainMath.max(NU_FLOOR, state.nuSession * 0.67)
                if (state.skipRun >= 3) {
                    state.reseedCount++
                    state.skipRun = 0
                    state.familiarNext = false
                } else if (state.skipRun == 2) {
                    state.familiarNext = true
                }
            }
        } else if (g.isCompletion || liked) {
            pushPos(state, e.rid, e.primaryAid)
            if (liked) pushPos(state, e.rid, e.primaryAid)
            state.skipRun = 0
            state.familiarNext = false
            state.lastCompleted = e.rid
            state.lastCompletedAid = e.primaryAid
            state.prevCompleted = true
            if (liked) {
                if (novel) state.nuSession = BrainMath.min(NU_CEILING, state.nuSession * 1.25)
                state.likedRid = e.rid
                state.likedAid = e.primaryAid
                state.likedWithin = LIKED_WITHIN
            }
        } else {
            state.prevCompleted = false
        }
        // Anchors: only the listener's own choices, or engine picks they acted on (5.12, rule 10).
        val enginePick = e.arm != Arm.NONE
        if (e.rid != 0L && ((!enginePick && g.r >= 0.5 && h == 0.0) || (enginePick && liked))) {
            pushAnchor(state, e.rid, e.primaryAid)
        }
    }

    /** The strength of a swipe on an engine row of the queue (8.4): the weakest skip of 5.5. */
    const val SWIPE_H = 0.2

    /**
     * The listener removed an engine pick from the queue without hearing it (8.4: "swipe removes it, a
     * weak negative, `h = 0.2`"). It only pushes the track onto `recentNeg` with that strength, so tracks
     * like it rank lower for the rest of the session: no affinity changes, no block, no skip run.
     */
    fun weakNegative(state: SessionState, rid: Long, aid: Long) {
        pushNeg(state, rid, aid, SWIPE_H)
    }

    private fun countDown(state: SessionState) {
        var i = 0
        while (i < state.blockAid.size) {
            val left = state.blockLeft[i]
            if (left == REST_OF_SESSION) { i++; continue }
            if (left <= 1) {
                state.blockAid.removeAt(i)
                state.blockLeft.removeAt(i)
            } else {
                state.blockLeft[i] = left - 1
                i++
            }
        }
        if (state.seedLimitLeft > 0) {
            state.seedLimitLeft--
            if (state.seedLimitLeft == 0) state.seedLimit = 0
        }
    }

    private fun hardSkipOfArtist(state: SessionState, aid: Long) {
        var n = 1
        var at = -1
        for (i in state.skippedAid.indices) if (state.skippedAid[i] == aid) at = i
        if (at >= 0) {
            n = state.skippedCount[at] + 1
            state.skippedCount[at] = n
        } else {
            state.skippedAid.add(aid)
            state.skippedCount.add(1)
        }
        val station = state.seedKind == SEED_STATION_SONG || state.seedKind == SEED_STATION_ARTIST
        if (station && aid == state.seedAid) {
            // The listener asked for this artist: thin it out, never ban it for the session.
            state.seedLimit = if (n >= 2) 2 else 1
            state.seedLimitLeft = BLOCK_TRACKS
            return
        }
        val left = if (n >= 2) REST_OF_SESSION else BLOCK_TRACKS
        for (i in state.blockAid.indices) {
            if (state.blockAid[i] == aid) {
                state.blockLeft[i] = left
                return
            }
        }
        state.blockAid.add(aid)
        state.blockLeft.add(left)
    }

    private fun pushPos(state: SessionState, rid: Long, aid: Long) {
        if (rid == 0L) return
        var i = if (state.posCount < RECENT) state.posCount else RECENT - 1
        while (i > 0) {
            state.recentPos[i] = state.recentPos[i - 1]
            state.recentPosAid[i] = state.recentPosAid[i - 1]
            i--
        }
        state.recentPos[0] = rid
        state.recentPosAid[0] = aid
        if (state.posCount < RECENT) state.posCount++
    }

    private fun pushNeg(state: SessionState, rid: Long, aid: Long, h: Double) {
        if (rid == 0L) return
        var i = if (state.negCount < RECENT) state.negCount else RECENT - 1
        while (i > 0) {
            state.recentNeg[i] = state.recentNeg[i - 1]
            state.recentNegAid[i] = state.recentNegAid[i - 1]
            state.recentNegH[i] = state.recentNegH[i - 1]
            i--
        }
        state.recentNeg[0] = rid
        state.recentNegAid[0] = aid
        state.recentNegH[0] = h
        if (state.negCount < RECENT) state.negCount++
    }

    private fun pushAnchor(state: SessionState, rid: Long, aid: Long) {
        // Already an anchor: move it to the front.
        var at = -1
        for (i in 0 until state.anchorCount) if (state.anchors[i] == rid) at = i
        var i = if (at >= 0) at else if (state.anchorCount < MAX_ANCHORS) state.anchorCount else MAX_ANCHORS - 1
        while (i > 0) {
            state.anchors[i] = state.anchors[i - 1]
            state.anchorsAid[i] = state.anchorsAid[i - 1]
            i--
        }
        state.anchors[0] = rid
        state.anchorsAid[0] = aid
        if (at < 0 && state.anchorCount < MAX_ANCHORS) state.anchorCount++
    }

    /**
     * The session's anchors in the order the engine should seed from: newest first, turned by one place
     * for every change of direction (7.3, third skip in a row: "the next anchor of the session").
     */
    fun seedOrder(state: SessionState): LongArray {
        val n = state.anchorCount
        if (n == 0) return LongArray(0)
        val shift = state.reseedCount % n
        return LongArray(n) { state.anchors[(it + shift) % n] }
    }

    /**
     * Re-scores the engine-owned tail of the queue after a reaction (2.3 step 7, 7.3). [request] holds
     * the tail's own candidates together with the pool, and asks for as many tracks as the tail has.
     * Returns the new tail, or null when nothing changes. The caller never passes a track that is
     * starting or already warmed (decision D8), and applies the answer in one queue edit.
     */
    fun patchQueue(
        state: SessionState,
        tail: LongArray,
        request: StationPlanner.Request,
        affinity: Affinity.State,
        graph: Graph,
    ): StationPlanner.Plan? {
        val plan = StationPlanner.plan(request, affinity, state, graph)
        if (plan.picks.size == tail.size) {
            var same = true
            for (i in tail.indices) if (plan.picks[i].rid != tail[i]) same = false
            if (same) return null
        }
        return plan
    }
}

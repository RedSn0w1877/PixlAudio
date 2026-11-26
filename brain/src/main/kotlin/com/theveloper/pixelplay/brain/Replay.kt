package com.theveloper.pixelplay.brain

/**
 * Facts that are not events (docs/brain-spec.md, 4.4, step 4): they become pseudo-counts in the
 * affinity tables. Everything is given by id; the caller resolved names before the pure core sees it.
 */
class LibraryFacts(
    /** Spotify Liked Songs and in-app favourites: `(rid, primaryAid)`. */
    val liked: List<TrackFact> = emptyList(),
    val topArtists: List<TopArtist> = emptyList(),
    val topTracks: List<TopTrack> = emptyList(),
    /** Followed artists, by aid. */
    val followed: LongArray = LongArray(0),
    val adjustments: List<Adjustment> = emptyList(),
) {
    class TrackFact(val rid: Long, val primaryAid: Long)

    /** [range] is one of [RANGE_SHORT], [RANGE_MEDIUM], [RANGE_LONG]; [position] is 0 for the first entry. */
    class TopArtist(val aid: Long, val range: Int, val position: Int, val listSize: Int)

    class TopTrack(val rid: Long, val primaryAid: Long, val range: Int, val position: Int, val listSize: Int)

    /** A taste adjustment "more" / "less" on a track or an artist; [strength] is 1, 2 or 3. */
    class Adjustment(val family: Int, val key: Long, val more: Boolean, val strength: Int)

    companion object {
        const val RANGE_SHORT = 0
        const val RANGE_MEDIUM = 1
        const val RANGE_LONG = 2

        val EMPTY = LibraryFacts()
    }
}

/**
 * Grades events in order, carrying the two things grading needs from earlier events: how often each
 * track was finished, and the run of skips directly before a play. The live recorder and [Replay] use
 * the same class, so replaying the log gives the state the live path built.
 */
class GradeTracker(private val completions: Map<Long, Int>, private val trust: Reward.Trust = Reward.Trust.DEFAULT) {
    private var streak = 0
    private var lastSkipEndMs = 0L
    private var lastSessionId = 0L

    /** The run of skips before [e] that counts for it: live plays of one session, each within 60 s of the next. */
    fun grade(e: PlayEvent): Reward.Graded {
        val live = e.origin == Origin.LIVE
        if (live && e.sessionId != lastSessionId) {
            streak = 0
            lastSessionId = e.sessionId
        }
        val gap = e.endedAtMs - lastSkipEndMs
        val streakNow = if (live && streak > 0 && gap >= 0L && gap <= SKIP_STREAK_GAP_MS) streak else 0
        val g = Reward.grade(e, Reward.Context(completions[e.rid] ?: 0, streakNow), trust)
        if (live) {
            if (g.isSkip) {
                streak = streakNow + 1
                lastSkipEndMs = e.endedAtMs
            } else {
                streak = 0
            }
        }
        return g
    }

    companion object {
        const val SKIP_STREAK_GAP_MS = 60_000L
    }
}

/**
 * Rebuilds the affinity tables from the event log and the facts (docs/brain-spec.md, 4.4, 5.2;
 * principle 2: the log is the only source of truth). Stage 1 rebuilds affinities only.
 */
object Replay {

    /**
     * [events] must be in ascending `eventId` (stored events and imported plays already merged, and
     * every rid already mapped through `track_merge`). Every event time is clamped to [nowMs], so a
     * clock that was once set far ahead cannot pin the counters in the future.
     */
    fun rebuild(events: Iterator<PlayEvent>, facts: LibraryFacts, nowMs: Long): Affinity.State {
        val state = Affinity.State()
        val tracker = GradeTracker(state.completions)
        while (events.hasNext()) {
            val e = events.next()
            val g = tracker.grade(e)
            Affinity.applyEvent(state, e, g, if (e.startedAtMs > nowMs) nowMs else e.startedAtMs)
        }
        applyFacts(state, facts)
        state.drainTouched { _, _ -> }
        return state
    }

    private fun rangeWeight(range: Int): Double = when (range) {
        LibraryFacts.RANGE_SHORT -> 1.0
        LibraryFacts.RANGE_MEDIUM -> 0.8
        else -> 0.6
    }

    private fun rankFactor(range: Int, position: Int, listSize: Int): Double {
        val size = if (listSize > 0) listSize else 1
        val pos = if (position < 0) 0 else if (position > size) size else position
        return rangeWeight(range) * (1.0 - 0.7 * pos.toDouble() / size.toDouble())
    }

    /**
     * Replaces the pseudo-counts of [state] with the ones [facts] give (the table of 4.4). Sums run in
     * Q20 integers, so the order of the facts does not matter. An artist or a track in several top
     * ranges gets the largest of its values, not their sum.
     */
    fun applyFacts(state: Affinity.State, facts: LibraryFacts) {
        state.clearPriors()
        for (f in facts.liked) {
            if (f.rid != 0L) state.addPrior(Family.TRACK, f.rid, BrainMath.toQ(2.0), 0L)
            if (f.primaryAid != 0L) state.addPrior(Family.ARTIST, f.primaryAid, BrainMath.toQ(0.5), 0L)
        }
        val bestArtist = HashMap<Long, Long>()
        for (t in facts.topArtists) {
            if (t.aid == 0L) continue
            val q = BrainMath.toQ(6.0 * rankFactor(t.range, t.position, t.listSize))
            val old = bestArtist[t.aid]
            if (old == null || q > old) bestArtist[t.aid] = q
        }
        for (aid in bestArtist.keys.toLongArray().also { it.sort() }) {
            state.addPrior(Family.ARTIST, aid, bestArtist[aid]!!, 0L)
        }
        val bestTrack = HashMap<Long, Long>()
        val trackArtist = HashMap<Long, Long>()
        for (t in facts.topTracks) {
            if (t.rid == 0L) continue
            val q = BrainMath.toQ(3.0 * rankFactor(t.range, t.position, t.listSize))
            val old = bestTrack[t.rid]
            if (old == null || q > old) bestTrack[t.rid] = q
            if (t.primaryAid != 0L && !trackArtist.containsKey(t.rid)) trackArtist[t.rid] = t.primaryAid
        }
        for (rid in bestTrack.keys.toLongArray().also { it.sort() }) {
            state.addPrior(Family.TRACK, rid, bestTrack[rid]!!, 0L)
            val aid = trackArtist[rid]
            if (aid != null) state.addPrior(Family.ARTIST, aid, BrainMath.toQ(0.5), 0L)
        }
        val followed = HashSet<Long>()
        for (aid in facts.followed) {
            if (aid != 0L && followed.add(aid)) state.addPrior(Family.ARTIST, aid, BrainMath.toQ(2.0), 0L)
        }
        for (a in facts.adjustments) {
            if (a.key == 0L || (a.family != Family.TRACK && a.family != Family.ARTIST && a.family != Family.TAG)) continue
            val scale = if (a.strength >= 3) 2.0 else if (a.strength == 2) 1.5 else 1.0
            val q = BrainMath.toQ(6.0 * scale)
            if (a.more) state.addPrior(a.family, a.key, q, 0L) else state.addPrior(a.family, a.key, 0L, q)
        }
    }
}

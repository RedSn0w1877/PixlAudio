package com.theveloper.pixelplay.data.brain

/**
 * Which way the engine is turned (docs/brain-spec.md, 7.3 "Third skip in a row").
 *
 * Three engine picks skipped hard in a row turn the engine to another anchor of the session, and when
 * there is none, to the library. That turn is a detour, not the new normal:
 *
 *  - Only skips of the ENGINE's picks count. A listener skipping through their own playlist to reach
 *    a song is choosing, not rejecting what Autoplay offered.
 *  - The turn ends the moment the listener plays a track of their own. From then on the engine seeds
 *    from the normal anchors again, led by what they chose.
 *  - While it lasts the engine follows the one anchor it turned to, plus any pick the listener liked
 *    since (a like is a new anchor, and it belongs to the new direction).
 *  - A station stays on its seed: skips there thin out what was skipped, they do not turn it.
 *
 * The pure core's `SessionState.reseedCount` only ever grows and counts every hard skip; it is not
 * read for this any more. Session-scoped: a new session starts with a fresh one. Not thread-safe; it
 * lives inside [BrainEngine.Loaded] and is touched under its lock.
 */
class Direction {
    private var engineSkipRun = 0
    private var turnRid = 0L
    private val tried = ArrayList<Long>()
    private val baseline = HashSet<Long>()

    /** 0 while the engine follows the normal anchors; otherwise the number of the turn, counting up. */
    var turn: Int = 0
        private set

    private var turns = 0

    /**
     * One graded play. [anchors] is the session's anchors, newest first, AFTER the play was applied.
     * True when this play turned the engine.
     *
     * [hardSkip]: a skip with `h >= 0.6` that counts (`c > 0`). [positive]: a completion or a like.
     */
    fun onEvent(enginePick: Boolean, station: Boolean, hardSkip: Boolean, positive: Boolean, anchors: LongArray): Boolean {
        if (!enginePick) {
            // The listener's own track, however it ended: they are steering.
            engineSkipRun = 0
            turn = 0
            turnRid = 0L
            tried.clear()
            baseline.clear()
            return false
        }
        if (station) return false
        if (positive) {
            engineSkipRun = 0
            return false
        }
        if (!hardSkip) return false
        engineSkipRun++
        if (engineSkipRun < SKIPS_TO_TURN) return false
        engineSkipRun = 0
        if (turn == 0) {
            // The newest anchor led the pool that was just skipped out of.
            tried.clear()
            if (anchors.isNotEmpty()) tried.add(anchors[0])
        }
        var next = 0L
        for (rid in anchors) {
            if (rid !in tried) {
                next = rid
                break
            }
        }
        turnRid = next
        if (next != 0L) tried.add(next)
        baseline.clear()
        for (rid in anchors) baseline.add(rid)
        turns++
        turn = turns
        return true
    }

    /**
     * The anchors the engine seeds from while turned, best first: picks liked since the turn, then the
     * anchor it turned to. Empty while turned means "nothing of the session is left to turn to": the
     * caller seeds from the library instead. Not turned: [anchors] as they are.
     */
    fun follow(anchors: LongArray): LongArray {
        if (turn == 0) return anchors
        val out = ArrayList<Long>(anchors.size)
        for (rid in anchors) if (rid !in baseline) out.add(rid)
        if (turnRid != 0L && turnRid in anchors) out.add(turnRid)
        return out.toLongArray()
    }

    private companion object {
        const val SKIPS_TO_TURN = 3
    }
}

package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.RepeatActivation
import com.theveloper.pixelplay.brain.Reward
import com.theveloper.pixelplay.data.brain.store.PlayStamp

/**
 * Feature 10 of the scorer (docs/brain-spec.md, 5.6, 5.8) for a set of recordings, from their past plays: each
 * play weighs what the reward table says its trust weight `c` is (graded without the context of the moment, as
 * `Affinity.subtract` does), and [RepeatActivation] turns the weights and times into `clip((B + 4) / 6, 0, 1)`.
 * Plays older than [LOOK_BACK_MS] are not read: at the exponent 0.5 a play three months old counts for a fiftieth
 * of one an hour old, and the query stays small.
 */
object ActivationFeatures {
    const val LOOK_BACK_MS = 90L * 24 * 60 * 60 * 1000

    /** Recording id to feature 10; a recording with no play that counts has no entry (its feature is 0). */
    fun compute(stamps: List<PlayStamp>, nowMs: Long): Map<Long, Double> {
        val histories = HashMap<Long, RepeatActivation.History>()
        // Oldest first, as the history wants them.
        for (s in stamps.sortedBy { it.startedAtMs }) {
            val e = PlayEvent(
                eventId = 0L, origin = s.origin, rid = s.rid, primaryAid = 0L, startedAtMs = s.startedAtMs,
                playedMs = s.playedMs, durationMs = s.durationMs, endReason = s.endReason, flags = s.flags,
                surface = s.surface, arm = s.arm,
            )
            val g = Reward.grade(e, Reward.Context(0, 0))
            if (g.c <= 0.0) continue
            histories.getOrPut(s.rid) { RepeatActivation.History() }.add(s.startedAtMs, g.c)
        }
        val out = HashMap<Long, Double>(histories.size * 2)
        for ((rid, history) in histories) {
            val f = RepeatActivation.feature(history, nowMs)
            if (f > 0.0) out[rid] = f
        }
        return out
    }
}

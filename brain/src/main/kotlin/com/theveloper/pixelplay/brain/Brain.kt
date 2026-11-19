package com.theveloper.pixelplay.brain

/**
 * The step from a play to changed state (docs/brain-spec.md, 2.3, steps 3 to 5): grade the event,
 * update the affinity tables, update the session. The live recorder calls this once per finished
 * play; [Replay.rebuild] does the first two steps for every event of the log.
 */
object Brain {
    /** Bumped whenever the pure logic changes its output: new golden files, and a rebuild on first use. */
    const val SPEC_VERSION = 2

    /** Called with the graded play after grading and BEFORE the tables change: the learner reads the state as it was (5.9). */
    fun interface BeforeApply {
        fun beforeApply(e: PlayEvent, g: Reward.Graded)
    }

    fun onEvent(affinity: Affinity.State, tracker: GradeTracker, session: SessionState, e: PlayEvent, hook: BeforeApply? = null): Reward.Graded {
        val novel = !Affinity.isHeard(affinity, Family.TRACK, e.rid)
        val g = tracker.grade(e)
        hook?.beforeApply(e, g)
        Affinity.applyEvent(affinity, e, g, e.startedAtMs)
        Session.apply(session, e, g, novel)
        return g
    }
}

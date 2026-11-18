package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Family

/**
 * `BrainSwitch.engine` (docs/brain-spec.md, 2.1, 14 stage 2): which engine ranks Daily Mix and Your Mix.
 * `brain` is the default; `legacy` is the old scorer, byte for byte what it was. The listener never
 * sees the choice: the owner flips it under Developer mode to compare.
 */
object BrainEngineChoice {
    const val LEGACY = 0
    const val BRAIN = 1

    fun clamp(value: Int): Int = if (value == LEGACY) LEGACY else BRAIN

    /**
     * The brain has something to rank by once the listener has played a few different tracks. With
     * none it would serve the library in the order of a coin toss, and the legacy mixes (which
     * read the library's own engagement counters) are the better start: "falling back to legacy when no
     * state exists at all" (14, stage 2; 5.14).
     */
    const val MIN_HEARD_TRACKS = 10

    fun hasState(affinity: Affinity.State): Boolean = affinity.size(Family.TRACK) >= MIN_HEARD_TRACKS

    /** True when the brain scorer builds the mixes: it is switched on and has state to rank by. */
    fun useBrain(engine: Int, hasState: Boolean): Boolean = clamp(engine) == BRAIN && hasState
}

package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Family
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrainEngineChoiceTest {
    private fun state(tracks: Int): Affinity.State {
        val s = Affinity.State()
        for (i in 1..tracks) Affinity.apply(s, Family.TRACK, i.toLong(), 1.0, 1.0, 1.0, 1_000L + i)
        return s
    }

    @Test
    fun `the brain ranks the mixes only when it is switched on and has state, and legacy is the fallback`() {
        assertTrue(BrainEngineChoice.useBrain(BrainEngineChoice.BRAIN, hasState = true))
        assertFalse(BrainEngineChoice.useBrain(BrainEngineChoice.BRAIN, hasState = false))
        assertFalse(BrainEngineChoice.useBrain(BrainEngineChoice.LEGACY, hasState = true))
        assertEquals(BrainEngineChoice.BRAIN, BrainEngineChoice.clamp(7), "anything unknown is the default")
        assertEquals(BrainEngineChoice.LEGACY, BrainEngineChoice.clamp(0))
    }

    @Test
    fun `state means a handful of different tracks were played`() {
        assertFalse(BrainEngineChoice.hasState(Affinity.State()))
        assertFalse(BrainEngineChoice.hasState(state(BrainEngineChoice.MIN_HEARD_TRACKS - 1)))
        assertTrue(BrainEngineChoice.hasState(state(BrainEngineChoice.MIN_HEARD_TRACKS)))
    }
}

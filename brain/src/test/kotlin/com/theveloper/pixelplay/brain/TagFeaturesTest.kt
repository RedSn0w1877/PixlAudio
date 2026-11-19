package com.theveloper.pixelplay.brain

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Features 4 and 24 of docs/brain-spec.md, 5.8, and the tag penalties of 5.5. */
class TagFeaturesTest {
    private val atlas by lazy { GenreAtlas.parse(Stage2bGolden.atlasText(File("."))) }
    private fun v(vararg names: String): Tags.Vector =
        Tags.merge(atlas, names.map { Tags.Input(Tags.SRC_RECORDING, atlas.idForName(it), 1.0) })

    @Test
    fun `a skip penalises the top tags, a second skip of the same genre costs three times, a finish forgives`() {
        val s = SessionState()
        val trap = v("trap")
        Session.applyTags(s, trap, 1.0, completed = false)
        val first = s.tagPen[atlas.idForName("trap")]!!
        assertTrue(first > 0.0)
        Session.applyTags(s, trap, 1.0, completed = false)
        assertEquals(first * 4.0, s.tagPen[atlas.idForName("trap")]!!, 1e-12, "1 + 3 times")
        // A different top tag in between resets the tripling.
        val s2 = SessionState()
        Session.applyTags(s2, trap, 1.0, completed = false)
        Session.applyTags(s2, v("techno"), 1.0, completed = false)
        Session.applyTags(s2, trap, 1.0, completed = false)
        assertEquals(first * 2.0, s2.tagPen[atlas.idForName("trap")]!!, 1e-12)
        // A play that finished multiplies everything by 0.7.
        val before = s2.tagPen[atlas.idForName("trap")]!!
        Session.applyTags(s2, trap, 0.0, completed = true)
        assertEquals(before * 0.7, s2.tagPen[atlas.idForName("trap")]!!, 1e-12)
        // A play that was neither a skip nor a finish changes nothing; unknown genres change nothing.
        Session.applyTags(s2, trap, 0.0, completed = false)
        assertEquals(before * 0.7, s2.tagPen[atlas.idForName("trap")]!!, 1e-12)
        val s3 = SessionState()
        Session.applyTags(s3, Tags.Vector.EMPTY, 1.0, completed = false)
        assertTrue(s3.tagPen.isEmpty())
    }

    @Test
    fun `feature 24 is capped at 1 and feature 4 is 0 for a genre never played`() {
        val s = SessionState()
        repeat(5) { Session.applyTags(s, v("trap"), 1.0, completed = false) }
        assertEquals(1.0, Features.sessTagPen(v("trap"), s))
        assertEquals(0.0, Features.sessTagPen(v("shoegaze"), SessionState()))
        val state = Affinity.State()
        assertEquals(0.0, Features.tagAff(v("trap"), state, 1_000L, 0.5))
        val id = atlas.idForName("trap").toLong()
        repeat(6) { Affinity.apply(state, Family.TAG, id, 1.0, 1.0, 1.0, 1_000L + it) }
        assertTrue(Features.tagAff(v("trap"), state, 2_000L, 0.5) > 0.0, "a genre finished again and again is liked")
    }

    @Test
    fun `a candidate with no tags leaves both features at exactly 0`() {
        val out = DoubleArray(Features.D)
        val c = Candidate(rid = 1L, aid = 2L)
        Features.compute(c, Arm.NONE, 0.0, 0, Affinity.State(), SessionState(), Graph.EMPTY, 1_000L, 0.5, false, out)
        assertEquals(0.0, out[Features.TAG_AFF])
        assertEquals(0.0, out[Features.SESS_TAG_PEN])
        val withTags = Candidate(rid = 1L, aid = 2L, tags = v("trap"))
        val s = SessionState()
        Session.applyTags(s, v("trap"), 1.0, completed = false)
        Features.compute(withTags, Arm.NONE, 0.0, 0, Affinity.State(), s, Graph.EMPTY, 1_000L, 0.5, false, out)
        assertTrue(out[Features.SESS_TAG_PEN] > 0.0)
    }
}

package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Flow ordering (6.5 `flowCost`, stage 5): smooth steps, the energy arc, and no change at all without flow facts. */
class FlowTest {

    @Test
    fun `tempo distance folds octaves and unknown costs nothing`() {
        assertEquals(0.0, Flow.tempoDist(1200, 1200))
        assertEquals(0.0, Flow.tempoDist(1200, 600))
        assertEquals(0.0, Flow.tempoDist(1200, 2400))
        assertEquals(0.0, Flow.tempoDist(0, 1200))
        assertTrue(Flow.tempoDist(1200, 1800) > 0.4) // 120 against 180: 90 is a fold away, 0.415 octaves
        assertEquals(0.0, Flow.step(Flow.Facts(1200, -80, 120), Flow.Facts(1250, -90, 140)))
        assertTrue(Flow.step(Flow.Facts(700, -60, 220), Flow.Facts(1700, -150, 40)) > 0.15)
        assertEquals(0.0, Flow.step(Flow.Facts.UNKNOWN, Flow.Facts(1700, -150, 40)))
    }

    @Test
    fun `the arc rises to a peak two thirds in and settles`() {
        val mid = 128
        val t = IntArray(21) { Flow.target(mid, it, 21) }
        assertEquals(110, t[0])
        val peak = t.indices.maxByOrNull { t[it] }!!
        assertTrue(peak in 12..14, "peak at $peak")
        assertTrue(t[20] < t[peak] && t[20] > t[0])
        assertEquals(-1, Flow.target(-1, 3, 10))
        assertEquals(0.0, Flow.arc(mid, 13, 21, t[13]))
    }

    private fun pool(seed: Long, n: Int): Pair<List<ListBuilder.Scored>, Map<Long, Flow.Facts>> {
        val rng = BrainHash.Rng(seed)
        val scored = ArrayList<ListBuilder.Scored>()
        val facts = HashMap<Long, Flow.Facts>()
        for (i in 0 until n) {
            val rid = 10L + i
            scored.add(ListBuilder.Scored(Candidate(rid, 100L + i, inLibrary = true), Arm.FAV, rng.uniform() * 0.4, 0.5, false, 0, -1, 0, 0L, 500))
            facts[rid] = Flow.Facts(600 + (rng.uniform() * 1200.0).toInt(), -150 + (rng.uniform() * 120.0).toInt(), (rng.uniform() * 255.0).toInt())
        }
        return scored to facts
    }

    @Test
    fun `flow makes lists smoother and an empty context changes nothing`() {
        var rougher = 0.0
        var smoother = 0.0
        for (s in 0 until 40) {
            val (scored, facts) = pool(BrainHash.fnv1a64("flow test $s"), 60)
            fun build(ctx: Flow.Context?) = ListBuilder.build(
                ListBuilder.Request(count = 15, prior = emptyList(), nu = 0.0, tieSeed = s.toLong(), nowMs = 0L, tzOffsetMin = 0, flow = ctx),
                scored, Graph.Builder().build(),
            ).map { facts[it.scored.c.rid]!! }
            rougher += Flow.roughness(build(null))
            smoother += Flow.roughness(build(Flow.Context(facts)))
            // A context that knows nothing is the same list as no context.
            val plain = ListBuilder.build(
                ListBuilder.Request(count = 15, prior = emptyList(), nu = 0.0, tieSeed = s.toLong(), nowMs = 0L, tzOffsetMin = 0),
                scored, Graph.Builder().build(),
            ).map { it.scored.c.rid }
            val unknown = ListBuilder.build(
                ListBuilder.Request(count = 15, prior = emptyList(), nu = 0.0, tieSeed = s.toLong(), nowMs = 0L, tzOffsetMin = 0, flow = Flow.Context(emptyMap())),
                scored, Graph.Builder().build(),
            ).map { it.scored.c.rid }
            assertEquals(plain, unknown)
        }
        println("roughness without flow $rougher, with flow $smoother")
        assertTrue(smoother < rougher * 0.6, "flow should cut the roughness: $rougher -> $smoother")
    }
}

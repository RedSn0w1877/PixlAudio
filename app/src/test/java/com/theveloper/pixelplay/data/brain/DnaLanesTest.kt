package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.BrainRefreshRunner.Fetch
import com.theveloper.pixelplay.data.brain.BrainRefreshRunner.Stop
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lanes 3 to 5 inside the one refresh job (docs/brain-spec.md, 3.7): their order, their switches, and that they never double the job. */
class DnaLanesTest {

    private class Work(
        val lane3: MutableList<DnaTarget> = ArrayList(),
        val lane4: MutableList<DnaTarget> = ArrayList(),
        val lane5: MutableList<DnaTarget> = ArrayList(),
        var youTubeBudget: Int = 100,
        var saves: Boolean = true,
    ) : RefreshWork {
        val asked = ArrayList<String>()
        override suspend fun radioSeeds() = emptyList<RefreshTarget>()
        override suspend fun artistSeeds() = emptyList<RefreshTarget>()
        override suspend fun requestsLeft() = youTubeBudget
        override suspend fun radio(target: RefreshTarget) = Fetch.NONE
        override suspend fun artist(target: RefreshTarget) = Fetch.NONE
        override suspend fun dnaTargets(lane: Int, limit: Int): List<DnaTarget> = when (lane) { 3 -> lane3; 4 -> lane4; else -> lane5 }.take(limit)
        override suspend fun enrich(targets: List<DnaTarget>, gate: suspend () -> Boolean): DnaEnricher.Report {
            asked.add("enrich:" + targets.joinToString(",") { it.rid.toString() })
            if (!saves) return DnaEnricher.Report(0, false)
            val list = listOf(lane3, lane4, lane5).first { l -> l.any { it.rid == targets.first().rid } }
            list.removeAll { t -> targets.any { it.rid == t.rid } }
            return DnaEnricher.Report(targets.size, false)
        }
    }

    private fun t(rid: Long) = DnaTarget(rid, 0L, "sp$rid", null, "T$rid", "A$rid", durationMs = 1)

    private fun run(work: RefreshWork, allowed: () -> Boolean = { true }, playing: () -> Boolean = { false }, elapsed: () -> Long = { 0L }) =
        BrainRefreshRunner(work, { allowed() }, playing, elapsed)

    @Test
    fun `lane 3 comes before lane 4 and lane 5, in rounds of sixteen`() = runTest {
        val work = Work(lane3 = (1L..20L).map(::t).toMutableList(), lane4 = mutableListOf(t(100)), lane5 = mutableListOf(t(200)))
        val report = run(work).run()
        assertEquals(listOf("enrich:1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16", "enrich:17,18,19,20", "enrich:100", "enrich:200"), work.asked)
        assertEquals(20, report.dnaLane3)
        assertEquals(1, report.dnaLane4)
        assertEquals(1, report.dnaLane5)
        assertEquals(Stop.NONE, report.stopped)
    }

    @Test
    fun `the lanes use no YouTube request, so a spent YouTube budget does not stop them`() = runTest {
        val work = Work(lane3 = mutableListOf(t(1)), youTubeBudget = 0)
        val report = run(work).run()
        assertEquals(1, report.dnaLane3)
        assertEquals(Stop.BUDGET, report.stopped, "the YouTube lanes stopped; the DNA lane did not need them")
    }

    @Test
    fun `never while music plays and never with the switch off`() = runTest {
        val playing = Work(lane3 = mutableListOf(t(1)))
        val p = run(playing, playing = { true }).run()
        assertEquals(Stop.PLAYING, p.stopped)
        assertTrue(playing.asked.isEmpty())
        val off = Work(lane3 = mutableListOf(t(1)))
        assertEquals(Stop.OFF, run(off, allowed = { false }).run().stopped)
        assertTrue(off.asked.isEmpty())
    }

    @Test
    fun `a switch flipped between rounds stops the next one`() = runTest {
        var allowed = true
        val work = object : RefreshWork by Work(lane3 = (1L..40L).map(::t).toMutableList()) {
            val inner = Work(lane3 = (1L..40L).map(::t).toMutableList())
            override suspend fun dnaTargets(lane: Int, limit: Int) = inner.dnaTargets(lane, limit)
            override suspend fun enrich(targets: List<DnaTarget>, gate: suspend () -> Boolean): DnaEnricher.Report {
                allowed = false
                return inner.enrich(targets, gate)
            }
        }
        val report = run(work, allowed = { allowed }).run()
        assertEquals(Stop.OFF, report.stopped)
        assertEquals(16, report.dnaLane3, "the round that was running finished, the next never started")
    }

    @Test
    fun `a round that saved nothing ends the lane instead of asking again`() = runTest {
        val work = Work(lane3 = (1L..5L).map(::t).toMutableList(), saves = false)
        run(work).run()
        assertEquals(1, work.asked.size, "lane 3 asked once; lanes 4 and 5 had nothing")
    }

    @Test
    fun `the lanes stop on their own before a periodic work's ten minutes`() = runTest {
        val work = Work(lane3 = (1L..40L).map(::t).toMutableList())
        val report = run(work, elapsed = { BrainRefreshRunner.DNA_TIME_LIMIT_MS + 1 }).run()
        assertEquals(Stop.TIME, report.stopped)
        assertTrue(work.asked.isEmpty())
    }

    @Test
    fun `a work with no DNA lanes runs exactly as before`() = runTest {
        val plain = object : RefreshWork {
            override suspend fun radioSeeds() = emptyList<RefreshTarget>()
            override suspend fun artistSeeds() = emptyList<RefreshTarget>()
            override suspend fun requestsLeft() = 10
            override suspend fun radio(target: RefreshTarget) = Fetch.NONE
            override suspend fun artist(target: RefreshTarget) = Fetch.NONE
        }
        val r = run(plain).run()
        assertEquals(0, r.dnaTracks)
        assertEquals(Stop.NONE, r.stopped)
    }

    @Test
    fun `the note of a run is read back for the Developer screen`() {
        val n = BrainRefresh.parseNote("1795000000000|r5+3|a1+0|n0+2|m4|d7+8+9|PLAYING")!!
        assertEquals(1795000000000L, n.atMs)
        assertEquals(8, n.radios)
        assertEquals(1, n.artists)
        assertEquals(2, n.releases)
        assertEquals(24, n.tracksEnriched)
        assertEquals("PLAYING", n.stopped)
        // A stage 2a note has no DNA part.
        assertEquals(0, BrainRefresh.parseNote("5|r1+1|a0+0|n0+0|m0|NONE")!!.tracksEnriched)
        assertEquals(null, BrainRefresh.parseNote(""))
        assertEquals(null, BrainRefresh.parseNote("garbage"))
    }
}

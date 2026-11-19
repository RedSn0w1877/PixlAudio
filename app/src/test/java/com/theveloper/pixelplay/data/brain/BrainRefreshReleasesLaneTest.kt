package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.BrainRefreshRunner.Fetch
import com.theveloper.pixelplay.data.brain.BrainRefreshRunner.Stop
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The releases lane of the refresh worker (docs/brain-spec.md, 3.7 lane 2, 6.3). */
class BrainRefreshReleasesLaneTest {

    private class Work(
        val radios: List<RefreshTarget>,
        val artists: List<RefreshTarget>,
        val releaseOrder: List<RefreshTarget>,
        var budget: Int,
    ) : RefreshWork {
        val asked = ArrayList<String>()
        override suspend fun radioSeeds() = radios
        override suspend fun artistSeeds() = artists
        override suspend fun releaseTargets() = releaseOrder
        override suspend fun requestsLeft() = budget
        override suspend fun radio(target: RefreshTarget): Fetch { asked.add("radio:${target.rid}"); budget--; return Fetch.NETWORK }
        override suspend fun artist(target: RefreshTarget): Fetch { asked.add("artist:${target.rid}"); budget--; return Fetch.NETWORK }
        override suspend fun releases(target: RefreshTarget): Fetch {
            asked.add("releases:${target.rid}")
            budget--
            return if (target.rid == 9L) Fetch.NONE else Fetch.NETWORK
        }
    }

    private fun target(rid: Long) = RefreshTarget(RadioSeed("m$rid", "v$rid", "T$rid", "A$rid", 200_000L), rid, "A$rid")

    @Test
    fun `releases come after the radios and before the artist lists`() = runTest {
        val work = Work(listOf(target(1)), listOf(target(3)), listOf(target(2), target(9)), budget = 100)
        val report = BrainRefreshRunner(work, { true }, { false }).run()
        assertEquals(listOf("radio:1", "releases:2", "releases:9", "artist:3"), work.asked)
        assertEquals(1, report.releasesFetched)
        assertEquals(1, report.missed)
        assertEquals(3, report.fetched)
    }

    @Test
    fun `the budget stops the releases lane and the lanes after it`() = runTest {
        val work = Work(listOf(target(1)), listOf(target(3)), listOf(target(2), target(4)), budget = 2)
        val report = BrainRefreshRunner(work, { true }, { false }).run()
        assertEquals(Stop.BUDGET, report.stopped)
        assertEquals(listOf("radio:1", "releases:2"), work.asked)
    }

    @Test
    fun `a work with no releases lane runs as it did`() = runTest {
        val bare = object : RefreshWork {
            override suspend fun radioSeeds() = listOf(target(1))
            override suspend fun artistSeeds() = listOf(target(2))
            override suspend fun requestsLeft() = 10
            override suspend fun radio(target: RefreshTarget) = Fetch.NETWORK
            override suspend fun artist(target: RefreshTarget) = Fetch.NETWORK
        }
        val report = BrainRefreshRunner(bare, { true }, { false }).run()
        assertEquals(0, report.releasesFetched)
        assertEquals(2, report.fetched)
    }
}

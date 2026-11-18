package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.BrainRefreshRunner.Fetch
import com.theveloper.pixelplay.data.brain.BrainRefreshRunner.Stop
import com.theveloper.pixelplay.data.brain.source.BrainRemoteConfig
import com.theveloper.pixelplay.data.worker.BrainRefreshScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The refresh worker's rules (docs/brain-spec.md, 3.7, stage 2a), without WorkManager. */
class BrainRefreshRunnerTest {

    private class FakeWork(
        val radios: List<RefreshTarget> = emptyList(),
        val artists: List<RefreshTarget> = emptyList(),
        var budget: Int = 100,
    ) : RefreshWork {
        val cache = HashSet<Long>()
        val asked = ArrayList<String>()
        var cancelDuringRadio: (() -> Unit)? = null
        override suspend fun radioSeeds() = radios
        override suspend fun artistSeeds() = artists
        override suspend fun requestsLeft() = budget
        override suspend fun radio(target: RefreshTarget): Fetch {
            asked.add("radio:${target.rid}")
            cancelDuringRadio?.invoke()
            if (target.rid in cache) return Fetch.CACHED
            cache.add(target.rid)
            budget--
            return Fetch.NETWORK
        }
        override suspend fun artist(target: RefreshTarget): Fetch {
            asked.add("artist:${target.rid}")
            if (-target.rid in cache) return Fetch.CACHED
            cache.add(-target.rid)
            budget--
            return Fetch.NETWORK
        }
    }

    private fun target(rid: Long) = RefreshTarget(RadioSeed("m$rid", "v$rid", "T$rid", "A$rid", 200_000L), rid, "A$rid")

    private fun runner(work: RefreshWork, allowed: () -> Boolean = { true }, playing: () -> Boolean = { false }) =
        BrainRefreshRunner(work, { allowed() }, playing)

    @Test
    fun `lane 1 comes before lane 2, and each seed is asked once`() = runTest {
        val work = FakeWork(radios = listOf(target(1), target(2)), artists = listOf(target(3)))
        val report = runner(work).run()
        assertEquals(listOf("radio:1", "radio:2", "artist:3"), work.asked)
        assertEquals(2, report.radiosFetched)
        assertEquals(1, report.artistsFetched)
        assertEquals(Stop.NONE, report.stopped)
    }

    @Test
    fun `a second run the same day asks for nothing (idempotent)`() = runTest {
        val work = FakeWork(radios = listOf(target(1), target(2)), artists = listOf(target(3)))
        runner(work).run()
        val again = runner(work).run()
        assertEquals(0, again.fetched)
        assertEquals(2, again.radiosCached)
        assertEquals(1, again.artistsCached)
        assertEquals(97, work.budget, "nothing more was spent")
    }

    @Test
    fun `it never starts when a switch is off, and stops within one fetch when one is flipped`() = runTest {
        val work = FakeWork(radios = listOf(target(1), target(2), target(3)))
        val off = runner(work, allowed = { false }).run()
        assertEquals(Stop.OFF, off.stopped)
        assertTrue(work.asked.isEmpty())

        var allowed = true
        val flip = FakeWork(radios = listOf(target(1), target(2), target(3)))
        flip.cancelDuringRadio = { if (flip.asked.size == 1) allowed = false }
        val report = runner(flip, allowed = { allowed }).run()
        assertEquals(Stop.OFF, report.stopped)
        assertEquals(1, flip.asked.size, "the first fetch finished, the second never started")
    }

    @Test
    fun `it waits while music plays`() = runTest {
        val work = FakeWork(radios = listOf(target(1)))
        assertEquals(Stop.PLAYING, runner(work, playing = { true }).run().stopped)
        assertTrue(work.asked.isEmpty())
        var playing = false
        val later = FakeWork(radios = listOf(target(1), target(2)))
        later.cancelDuringRadio = { playing = true }
        val report = runner(later, playing = { playing }).run()
        assertEquals(Stop.PLAYING, report.stopped)
        assertEquals(1, later.asked.size)
    }

    @Test
    fun `it stops when the daily budget is spent, with the foreground's reserve already taken off`() = runTest {
        val work = FakeWork(radios = (1L..10L).map(::target), budget = 3)
        val report = runner(work).run()
        assertEquals(Stop.BUDGET, report.stopped)
        assertEquals(3, report.radiosFetched)
        assertEquals(3, work.asked.size)
    }

    @Test
    fun `a missed seed is counted and does not stop the run`() = runTest {
        val work = object : RefreshWork {
            override suspend fun radioSeeds() = listOf(target(1), target(2))
            override suspend fun artistSeeds() = emptyList<RefreshTarget>()
            override suspend fun requestsLeft() = 10
            override suspend fun radio(target: RefreshTarget) = if (target.rid == 1L) Fetch.NONE else Fetch.NETWORK
            override suspend fun artist(target: RefreshTarget) = Fetch.NONE
        }
        val report = runner(work).run()
        assertEquals(1, report.missed)
        assertEquals(1, report.radiosFetched)
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val work = object : RefreshWork {
            override suspend fun radioSeeds() = listOf(target(1), target(2))
            override suspend fun artistSeeds() = emptyList<RefreshTarget>()
            override suspend fun requestsLeft() = 10
            override suspend fun radio(target: RefreshTarget): Fetch { gate.await(); return Fetch.NETWORK }
            override suspend fun artist(target: RefreshTarget) = Fetch.NONE
        }
        val job = async { runner(work).run() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { kotlinx.coroutines.runBlocking { job.await() } }
    }

    @Test
    fun `the remote switch is on unless the config says it is off`() {
        assertTrue(BrainRemoteConfig.refreshAllowed(null))
        assertTrue(BrainRemoteConfig.refreshAllowed(""))
        assertTrue(BrainRemoteConfig.refreshAllowed("not json"))
        assertTrue(BrainRemoteConfig.refreshAllowed("""{"innertube":{"hedge":{"enabled":false}}}"""))
        assertTrue(BrainRemoteConfig.refreshAllowed("""{"brain":{"refresh":{"enabled":true}}}"""))
        assertTrue(BrainRemoteConfig.refreshAllowed("""{"brain":{"refresh":{}}}"""))
        assertFalse(BrainRemoteConfig.refreshAllowed("""{"brain":{"refresh":{"enabled":false}}}"""))
    }

    @Test
    fun `the job is one unique periodic work, a day long, unmetered, with a delay of at most an hour`() {
        assertEquals(24L, BrainRefreshScheduler.PERIOD_HOURS)
        assertEquals("brain_refresh", com.theveloper.pixelplay.data.worker.BrainRefreshWorker.WORK_NAME)
        val constraints = BrainRefreshScheduler.constraints()
        assertEquals(androidx.work.NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
        assertEquals(60L, BrainRefreshScheduler.MAX_INITIAL_DELAY_MINUTES)
        assertTrue(TimeUnit.MINUTES.toMillis(BrainRefreshScheduler.MAX_INITIAL_DELAY_MINUTES) <= TimeUnit.HOURS.toMillis(1))
    }
}

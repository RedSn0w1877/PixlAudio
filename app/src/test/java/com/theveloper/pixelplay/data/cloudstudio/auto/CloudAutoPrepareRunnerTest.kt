package com.theveloper.pixelplay.data.cloudstudio.auto

import com.theveloper.pixelplay.data.cloudstudio.CloudBudget
import com.theveloper.pixelplay.data.cloudstudio.CloudSecrets
import com.theveloper.pixelplay.data.cloudstudio.CloudSecretsState
import com.theveloper.pixelplay.data.cloudstudio.CloudSettingsSnapshot
import com.theveloper.pixelplay.data.cloudstudio.CloudSong
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioSettingsSource
import com.theveloper.pixelplay.data.cloudstudio.CloudWorkerCaps
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** One run of Smart auto-prepare (the 6-hourly worker's body), with fakes in place of the phone and the engine. */
class CloudAutoPrepareRunnerTest {
    private var now = 1_800_000_000_000L
    private val deadline = now + 8 * 60_000L

    private val settings = FakeSettings()
    private val environment = FakeEnvironment()
    private val facts = FakeFacts()
    private val engine = FakeEngine()
    private val cooldowns = FakeCooldowns()
    private val report = FakeReport()

    private fun runner() = CloudAutoPrepareRunner(
        settings = settings, environment = environment, facts = facts, engine = engine, cooldowns = cooldowns,
        report = report, nowMs = { now }, dayStartMs = { CloudBudget.dayStartMs(it) },
    )

    private fun song(id: String, favorite: Boolean = true, duration: Long = 180_000) = AutoCandidateFacts(
        songId = id, canonicalId = id, title = "Song $id", artist = "Artist", durationMs = duration, favorite = favorite,
    )

    private fun library(vararg ids: String) {
        facts.library = ids.map { song(it) }
        facts.cached = ids.toSet()
    }

    // ─── Whether it runs at all ─────────────────────────────────────────────────────────────

    @Test fun `with the switch off nothing is read or sent`() = runBlocking {
        library("a", "b")
        settings.snapshot = settings.snapshot.copy(autoPrepareEnabled = false)
        assertEquals(CloudAutoRunOutcome.OFF, runner().run(deadline).outcome)
        settings.snapshot = settings.snapshot.copy(autoPrepareEnabled = true, enabled = false)
        assertEquals(CloudAutoRunOutcome.OFF, runner().run(deadline).outcome)
        assertEquals(0, engine.loads)
        assertEquals(0, facts.gathers)
        assertTrue(engine.sent.isEmpty())
        assertTrue(engine.passes.isEmpty())
    }

    @Test fun `it stays out of the way of the person's own cloud work and of an unusable cloud`() = runBlocking {
        library("a", "b")
        engine.personWork = true
        assertEquals(CloudAutoRunOutcome.UNAVAILABLE, runner().run(deadline).outcome)
        engine.personWork = false
        engine.usable = false
        assertEquals(CloudAutoRunOutcome.UNAVAILABLE, runner().run(deadline).outcome)
        assertEquals(0, facts.gathers)
        assertTrue(engine.sent.isEmpty())
        assertTrue(engine.passes.isEmpty())
    }

    @Test fun `the phone not being ready is a wait, not a run`() = runBlocking {
        library("a", "b")
        environment.conditions = environment.conditions.copy(charging = false)
        val result = runner().run(deadline)
        assertEquals(CloudAutoRunOutcome.WAITING, result.outcome)
        assertEquals(AutoBlock.NOT_CHARGING, result.blockedBy)
        environment.conditions = environment.conditions.copy(charging = true, unmetered = false)
        assertEquals(AutoBlock.NOT_ON_WIFI, runner().run(deadline).blockedBy)
        environment.conditions = environment.conditions.copy(unmetered = true, playbackActive = true)
        assertEquals(AutoBlock.PLAYING, runner().run(deadline).blockedBy)
        assertEquals(0, facts.gathers)
        assertTrue(engine.passes.isEmpty(), "no pass on battery or mobile data")
    }

    // ─── What it sends ──────────────────────────────────────────────────────────────────────

    @Test fun `it makes jobs for the best songs whose audio is already on the phone, then runs a pass`() = runBlocking {
        library("a", "b", "c", "d", "e")
        facts.cached = setOf("b", "c", "d", "e") // "a" is the best but its audio isn't on the phone
        val result = runner().run(deadline)
        assertEquals(CloudAutoRunOutcome.SENT, result.outcome)
        assertEquals(3, result.added)
        assertEquals(listOf(listOf("b", "c", "d")), engine.sent.map { batch -> batch.map { it.id } })
        assertEquals(listOf(deadline), engine.passes, "the pass runs inside the worker, so its conditions hold")
        // The batch is sent as the picks are described: streamed or not, with audio, never a fresh download.
        assertTrue(engine.sent.single().all { it.hasAudioSource })
        // Each pick rests for a while, and the developer's list says what was picked.
        listOf("b", "c", "d").forEach { assertTrue(cooldowns.isResting(it, now + 1)) }
        assertFalse(cooldowns.isResting("e", now + 1))
        assertEquals(3, report.lines.size)
        assertTrue(report.lines.first().startsWith("Song b – Artist: a favorite"))
    }

    @Test fun `a song that was just picked is not picked again until it has rested`() = runBlocking {
        library("a", "b", "c", "d", "e")
        runner().run(deadline)
        engine.sent.clear()
        engine.outstanding = 0 // the first batch is done
        now += 3_600_000
        runner().run(deadline)
        assertEquals(listOf(listOf("d", "e")), engine.sent.map { batch -> batch.map { it.id } })
        engine.sent.clear()
        now += CloudAutoPrepare.COOLDOWN_MS + 1
        runner().run(deadline)
        assertEquals(listOf(listOf("a", "b", "c")), engine.sent.map { batch -> batch.map { it.id } })
    }

    @Test fun `the day's cap leaves room for only a few`() = runBlocking {
        library("a", "b", "c")
        settings.snapshot = settings.snapshot.copy(autoPrepareDailyCap = 12)
        settings.autoDay = 11
        val result = runner().run(deadline)
        assertEquals(1, result.added)
        assertEquals(listOf(listOf("a")), engine.sent.map { batch -> batch.map { it.id } })
        engine.sent.clear()
        settings.autoDay = 12
        assertEquals(CloudAutoRunOutcome.NOTHING_TO_SEND, runner().run(deadline).outcome)
        assertTrue(engine.sent.isEmpty())
    }

    @Test fun `both budgets limit the batch`() = runBlocking {
        library("a", "b", "c")
        // A 3-minute song costs (15 + 25) s at 192 µ$/s alone, 12,480 µ$ for two, 17,280 µ$ for three.
        settings.snapshot = settings.snapshot.copy(autoPrepareMonthlyBudgetMicroUsd = 15_000)
        assertEquals(2, runner().run(deadline).added)
        engine.sent.clear()
        cooldowns.clear()
        settings.snapshot = settings.snapshot.copy(autoPrepareMonthlyBudgetMicroUsd = 1_000_000, monthlyCapMicroUsd = 100_000)
        engine.committed = 90_000 // the shared month has room for one song
        assertEquals(1, runner().run(deadline).added)
        engine.sent.clear()
        cooldowns.clear()
        // The month has plenty again, but the automatic share has almost nothing left ($0.005 of $1.00).
        settings.snapshot = settings.snapshot.copy(monthlyCapMicroUsd = 3_000_000)
        engine.committed = 0
        engine.autoCommitted = 995_000
        assertEquals(CloudAutoRunOutcome.NOTHING_TO_SEND, runner().run(deadline).outcome)
        assertTrue(engine.sent.isEmpty())
    }

    @Test fun `with jobs already on their way it only gives them a pass`() = runBlocking {
        library("a", "b", "c")
        engine.outstanding = CloudAutoPrepare.BATCH_SIZE
        val result = runner().run(deadline)
        assertEquals(CloudAutoRunOutcome.PASS_ONLY, result.outcome)
        assertEquals(listOf(deadline), engine.passes)
        assertEquals(0, facts.gathers, "the library isn't read again")
        assertTrue(engine.sent.isEmpty())
    }

    @Test fun `with nothing worth preparing the leftovers still get their pass and the list explains`() = runBlocking {
        library("a", "b")
        facts.cached = emptySet() // none of the audio is on the phone
        val result = runner().run(deadline)
        assertEquals(CloudAutoRunOutcome.NOTHING_TO_SEND, result.outcome)
        assertEquals(0, result.added)
        assertEquals(listOf(deadline), engine.passes)
        assertTrue(engine.sent.isEmpty())
        assertEquals(2, report.lines.size, "the best of what was ranked, to show why nothing went")
        assertTrue(cooldowns.rested.isEmpty())
    }

    @Test fun `only the best sixty are checked for audio on the phone`() = runBlocking {
        val ids = (1..100).map { "s%03d".format(it) }
        library(*ids.toTypedArray())
        runner().run(deadline)
        assertEquals(CloudAutoPrepare.CACHE_CHECK_LIMIT, facts.cacheChecks)
    }

    @Test fun `songs that already have a job are left out of the ranking`() = runBlocking {
        library("a", "b", "c")
        engine.pending = setOf("a")
        runner().run(deadline)
        assertEquals(setOf("a"), facts.pendingSeen)
        assertEquals(listOf(listOf("b", "c")), engine.sent.map { batch -> batch.map { it.id } })
    }

    // ─── Fakes ──────────────────────────────────────────────────────────────────────────────

    private class FakeSettings : CloudStudioSettingsSource {
        var snapshot = CloudSettingsSnapshot(
            enabled = true, endpointId = "ep1", r2Endpoint = "0123456789abcdef0123456789abcdef", autoPrepareEnabled = true,
        )
        var autoDay = 0
        override fun snapshot() = snapshot
        override suspend fun secrets() = CloudSecretsState(CloudSecrets.EMPTY)
        override fun saveWorkerCaps(caps: CloudWorkerCaps?) = Unit
        override fun autoDayCount(dayStartMs: Long) = autoDay
    }

    private class FakeEnvironment : CloudAutoEnvironment {
        var conditions = AutoConditions(
            unmetered = true, charging = true, batteryPercent = 80, thermalStatus = 0,
            freeBytes = 10L * 1_073_741_824, playbackActive = false,
        )
        override fun conditions() = conditions
    }

    private class FakeFacts : CloudAutoFacts {
        var library: List<AutoCandidateFacts> = emptyList()
        var cached: Set<String> = emptySet()
        var gathers = 0
        var cacheChecks = 0
        var pendingSeen: Set<String> = emptySet()
        override suspend fun gather(pendingCanonicalIds: Set<String>, nowMs: Long): List<AutoCandidateFacts> {
            gathers++
            pendingSeen = pendingCanonicalIds
            return library.map { it.copy(hasPendingJob = it.canonicalId in pendingCanonicalIds) }
        }
        override suspend fun isAudioCached(facts: AutoCandidateFacts): Boolean {
            cacheChecks++
            return facts.songId in cached
        }
    }

    private class FakeEngine : CloudAutoEngine {
        var loads = 0
        var usable = true
        var personWork = false
        var pending: Set<String> = emptySet()
        var outstanding = 0
        var autoCommitted = 0L
        var committed = 0L
        val sent = mutableListOf<List<CloudSong>>()
        val passes = mutableListOf<Long>()
        override suspend fun load() { loads++ }
        override fun usable() = usable
        override fun personHasWork() = personWork
        override fun pendingCanonicalIds() = pending
        override fun outstandingAutoJobs() = outstanding
        override fun autoCommittedMicroUsd() = autoCommitted
        override fun committedMicroUsd() = committed
        override suspend fun sendAuto(songs: List<CloudSong>): Int {
            sent.add(songs)
            return songs.size
        }
        override suspend fun workerPass(deadlineMs: Long) { passes += deadlineMs }
    }

    private class FakeCooldowns : CloudAutoCooldowns {
        val rested = HashMap<String, Long>()
        override fun isResting(songId: String, nowMs: Long) = (rested[songId] ?: 0L) > nowMs
        override fun rest(songId: String, untilMs: Long) { rested[songId] = untilMs }
        fun clear() = rested.clear()
    }

    private class FakeReport : CloudAutoReport {
        var lines: List<String> = emptyList()
        override fun picked(lines: List<String>) { this.lines = lines }
    }
}

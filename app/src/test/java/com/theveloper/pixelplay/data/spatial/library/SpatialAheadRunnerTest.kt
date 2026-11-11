package com.theveloper.pixelplay.data.spatial.library

import com.theveloper.pixelplay.data.cloudstudio.CloudJobOrigin
import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudLibraryJob
import com.theveloper.pixelplay.data.cloudstudio.CloudLibrarySend
import com.theveloper.pixelplay.data.cloudstudio.CloudTask
import com.theveloper.pixelplay.data.cloudstudio.auto.AutoCandidateFacts
import com.theveloper.pixelplay.data.cloudstudio.auto.AutoSignal
import com.theveloper.pixelplay.data.cloudstudio.auto.CloudAutoFacts
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** cloud-audio-spec §6.4: the background queue's ranking, its order (lookups first, then jobs) and its limits. */
class SpatialAheadRunnerTest {
    private val day = 86_400_000L
    private var now = 1_800_000_000_000L
    private var settings = SpatialSettings(profile = SpatialProfile.WIDE, cloud = true, prepareAhead = true, dailyAhead = 25)
    private var cloudOn = true
    private var conditions = SpatialAheadConditions(SpatialNetwork(connected = true, unmetered = true), batteryPercent = 80)
    private val videoIds = mapOf("a" to "AAAAAAAAAAA", "b" to "BBBBBBBBBBB", "c" to "CCCCCCCCCCC", "d" to "DDDDDDDDDDD")
    private val target = FakeTarget()

    /** a: a favourite played yesterday; b: played yesterday; c: ten days ago; d: a month ago; e: never matched. */
    private val library = listOf(
        song("a", favorite = true, playedDaysAgo = 1),
        song("b", playedDaysAgo = 1),
        song("c", playedDaysAgo = 10),
        song("d", playedDaysAgo = 30),
        song("e", playedDaysAgo = 1, favorite = true),
    )

    private fun song(id: String, playedDaysAgo: Int, favorite: Boolean = false) = AutoCandidateFacts(
        songId = id, spotifyId = "spotify_$id", canonicalId = id, durationMs = 200_000, isStreamed = true,
        favorite = favorite, playCount = 3, lastPlayedMs = now - playedDaysAgo * day,
        signal = AutoSignal(sessions = 3, completions = 3), hasInstrumental = true, hasPendingJob = true,
    )

    private fun runner(songs: List<AutoCandidateFacts> = library) = SpatialAheadRunner(
        settings = { settings },
        cloudRendersOn = { cloudOn },
        conditions = { conditions },
        facts = object : CloudAutoFacts {
            override suspend fun gather(pendingCanonicalIds: Set<String>, nowMs: Long) = songs
            override suspend fun isAudioCached(facts: AutoCandidateFacts) = true
        },
        videoIdOf = { facts -> videoIds[facts.songId] },
        target = target,
        nowMs = { now },
    )

    private class FakeTarget : SpatialAheadTarget {
        val events = mutableListOf<String>()
        val local = HashSet<String>()
        val busy = HashSet<String>()
        val inLibrary = HashSet<String>()
        val broken = HashSet<String>()
        var capAfter = Int.MAX_VALUE
        var sentToday = 0
        val profiles = mutableListOf<SpatialProfile>()
        /** What the person does while a lookup or a send is under way. */
        var onLookUp: () -> Unit = {}
        var onSend: () -> Unit = {}

        override fun hasLocal(videoId: String, profile: SpatialProfile) = videoId in local
        override fun isBusy(videoId: String, profile: SpatialProfile) = videoId in busy
        override suspend fun lookUp(songId: String, videoId: String, profile: SpatialProfile): SharedLookup {
            events += "look $videoId"
            profiles += profile
            onLookUp()
            return when (videoId) {
                in broken -> SharedLookup.ERROR
                in inLibrary -> SharedLookup.FOUND.also { local += videoId }
                else -> SharedLookup.MISSING
            }
        }
        override suspend fun sendAhead(songId: String, videoId: String, profile: SpatialProfile): CloudLibrarySend {
            if (events.count { it.startsWith("send") } >= capAfter) return CloudLibrarySend.CapReached
            events += "send $videoId"
            profiles += profile
            sentToday++
            onSend()
            return CloudLibrarySend.Sent(
                CloudJobRecord(jobKey = "k", songId = songId, tasks = listOf(CloudTask.SPATIAL), origin = CloudJobOrigin.LIBRARY,
                    library = CloudLibraryJob(videoId, listOf(profile.id), ahead = true))
            )
        }
        override fun aheadSentToday() = sentToday
    }

    @Test fun `nothing runs without a profile, the queue or the cloud switches`() = runBlocking {
        for (off in listOf(settings.copy(profile = null), settings.copy(prepareAhead = false), settings.copy(cloud = false))) {
            settings = off
            assertEquals(SpatialAheadRun.Outcome.OFF, runner().run(now + 60_000).outcome)
        }
        settings = settings.copy(profile = SpatialProfile.WIDE, prepareAhead = true, cloud = true)
        cloudOn = false
        assertEquals(SpatialAheadRun.Outcome.OFF, runner().run(now + 60_000).outcome)
        assertTrue(target.events.isEmpty())
    }

    @Test fun `it waits for Wi-Fi, a battery that isn't low and Data Saver off`() = runBlocking {
        for (blocked in listOf(
            conditions.copy(network = SpatialNetwork(connected = true, unmetered = false)),
            conditions.copy(network = SpatialNetwork(connected = true, unmetered = true, dataSaver = true)),
            conditions.copy(batteryPercent = 10),
        )) {
            conditions = blocked
            val run = runner().run(now + 60_000)
            assertEquals(SpatialAheadRun.Outcome.WAITING, run.outcome)
            assertNotNull(run.waitingFor)
        }
        assertTrue(target.events.isEmpty())
    }

    @Test fun `the best-ranked songs are looked up first, then sent in the same order`() = runBlocking {
        target.inLibrary += "BBBBBBBBBBB"
        target.local += "DDDDDDDDDDD"
        val run = runner().run(now + 60_000)
        assertEquals(SpatialAheadRun.Outcome.RAN, run.outcome)
        // Sing's ranking (the favourite first, then recency), never mind the instrumental or a Sing job; d is already on
        // the phone and e has no matched video. Every lookup comes before any job.
        assertEquals(listOf("look AAAAAAAAAAA", "look BBBBBBBBBBB", "look CCCCCCCCCCC", "send AAAAAAAAAAA", "send CCCCCCCCCCC"), target.events)
        assertEquals(3, run.looked)
        assertEquals(1, run.fetched)
        assertEquals(2, run.sent)
        // The selected profile only.
        assertTrue(target.profiles.all { it == SpatialProfile.WIDE })
    }

    @Test fun `songs under way, waiting out a failure or failing to look up get no job`() = runBlocking {
        target.busy += "AAAAAAAAAAA"
        target.broken += "BBBBBBBBBBB"
        runner().run(now + 60_000)
        assertEquals(listOf("look BBBBBBBBBBB", "look CCCCCCCCCCC", "look DDDDDDDDDDD", "send CCCCCCCCCCC", "send DDDDDDDDDDD"), target.events)
    }

    @Test fun `jobs stay within the day's count, a run's share and the month's cap`() = runBlocking {
        // 23 of 25 sent today: two more.
        target.sentToday = 23
        assertEquals(2, runner().run(now + 60_000).sent)
        // The month's cap stops the run at once.
        target.events.clear()
        target.sentToday = 0
        target.capAfter = 1
        assertEquals(1, runner().run(now + 60_000).sent)
        assertEquals(1, target.events.count { it.startsWith("send") })
        // A run sends at most its share, however many songs are missing.
        val many = (1..20).map { song("s$it", playedDaysAgo = it) }
        val ids = many.associate { it.songId to "S%010d".format(it.songId.drop(1).toInt()) }
        val wide = SpatialAheadRunner(
            settings = { settings }, cloudRendersOn = { true }, conditions = { conditions },
            facts = object : CloudAutoFacts {
                override suspend fun gather(pendingCanonicalIds: Set<String>, nowMs: Long) = many
                override suspend fun isAudioCached(facts: AutoCandidateFacts) = true
            },
            videoIdOf = { ids[it.songId] }, target = FakeTarget(), nowMs = { now },
        )
        assertEquals(SpatialAheadRules.JOBS_PER_RUN, wide.run(now + 60_000).sent)
    }

    @Test fun `a run ends as soon as the profile or the queue changes, and fetches or sends nothing more for the old one`() = runBlocking {
        // The person picks Night while the run looks songs up: no further lookup, and no job for Wide.
        target.onLookUp = { settings = settings.copy(profile = SpatialProfile.NIGHT) }
        val looking = runner().run(now + 60_000)
        assertEquals(listOf("look AAAAAAAAAAA"), target.events)
        assertEquals(0, looking.sent)
        // Prepare ahead goes off while the run sends: the rest of its jobs never go.
        settings = settings.copy(profile = SpatialProfile.WIDE)
        target.events.clear()
        target.onLookUp = {}
        target.onSend = { settings = settings.copy(prepareAhead = false) }
        val sending = runner().run(now + 60_000)
        assertEquals(
            listOf("look AAAAAAAAAAA", "look BBBBBBBBBBB", "look CCCCCCCCCCC", "look DDDDDDDDDDD", "send AAAAAAAAAAA"),
            target.events,
        )
        assertEquals(1, sending.sent)
        // The cloud switches going off end it too.
        settings = settings.copy(prepareAhead = true)
        target.events.clear()
        target.onSend = {}
        target.onLookUp = { cloudOn = false }
        assertEquals(0, runner().run(now + 60_000).sent)
        assertEquals(listOf("look AAAAAAAAAAA"), target.events)
    }

    @Test fun `the run stops at its deadline`() = runBlocking {
        val run = runner().run(deadlineMs = now - 1)
        assertEquals(0, run.looked)
        assertEquals(0, run.sent)
    }
}

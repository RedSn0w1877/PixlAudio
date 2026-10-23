package com.theveloper.pixelplay.data.cloudstudio.auto

import com.theveloper.pixelplay.data.cloudstudio.CloudAutoPrepareDefaults
import com.theveloper.pixelplay.data.cloudstudio.CloudCost
import com.theveloper.pixelplay.data.cloudstudio.CloudSettingsSnapshot
import com.theveloper.pixelplay.data.worker.AutomaticStudioPolicy
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Smart auto-prepare's pure rules. The numbers are written out (not recomputed from the formula) because the iOS app
 * ranks with the same constants and has to give the same scores for the same inputs: these vectors are the iOS
 * `SingPrepareRankerTests` and `CloudAutoBudgetTests` ones.
 */
class CloudAutoPrepareTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun song(
        id: String,
        favorite: Boolean = false,
        album: String? = null,
        duration: Long = 180_000,
        dateAdded: Long = 0,
        plays: Int = 0,
        lastPlayed: Long = 0,
        signal: AutoSignal? = null,
        neighbour: Double = 0.0,
        instrumental: Boolean = false,
        pending: Boolean = false,
        blocked: Boolean = false,
        cached: Boolean = true,
    ) = AutoCandidateFacts(
        songId = id, canonicalId = id, albumKey = album, durationMs = duration, favorite = favorite, playCount = plays,
        lastPlayedMs = lastPlayed, dateAddedMs = dateAdded, signal = signal, neighbourBoost = neighbour,
        hasInstrumental = instrumental, hasPendingJob = pending, blockedArtist = blocked, audioCached = cached,
    )

    private fun rank(vararg songs: AutoCandidateFacts) = CloudAutoPrepare.rank(songs.toList(), now)
    private fun rank(songs: List<AutoCandidateFacts>) = CloudAutoPrepare.rank(songs, now)
    private fun score(id: String, ranked: List<AutoRanked>): Double = ranked.first { it.facts.songId == id }.score

    // ─── Each term on its own ───────────────────────────────────────────────────────────────

    @Test fun `a favourite alone scores three`() {
        val result = rank(song("fav", favorite = true), song("plain"))
        assertEquals(3.0, score("fav", result), 1e-9)
        assertEquals(0.0, score("plain", result), 1e-9)
        assertEquals("fav", result.first().facts.songId)
    }

    @Test fun `plays, recency and skips follow the formula`() {
        // 4 plays, 6 sessions of which 4 were finished and 1 skipped early, last played exactly 14 days ago:
        // 2.5·ln(5)·(6/10) + 2.0·2^−1 − 3.0·(1/8)
        val signal = AutoSignal(sessions = 6, completions = 4, earlySkips = 1)
        val result = rank(song("loved", plays = 4, lastPlayed = now - 14 * day, signal = signal))
        assertEquals(3.0391568686511503, score("loved", result), 1e-9)
        assertEquals(0.6, CloudAutoPrepare.completionRate(song("x", signal = signal)), 1e-12)
        assertEquals(0.125, CloudAutoPrepare.skipRate(song("x", signal = signal)), 1e-12)
    }

    @Test fun `without learned signals the plays and skip terms are zero`() {
        // Learning off, or nothing ever recorded for the song: only the recency of the last play is left.
        val result = rank(song("loved", plays = 4, lastPlayed = now - 14 * day, signal = null))
        assertEquals(1.0, score("loved", result), 1e-9)
        assertEquals(0.0, CloudAutoPrepare.completionRate(song("x")), 0.0)
        assertEquals(0.0, CloudAutoPrepare.skipRate(song("x")), 0.0)
    }

    @Test fun `recently added songs decay with a thirty day half-life`() {
        val result = rank(
            song("new", dateAdded = now - 60 * day), song("newer", favorite = true, dateAdded = now - 30 * day),
            song("unknown", dateAdded = 0),
        )
        assertEquals(0.25, score("new", result), 1e-9)
        assertEquals(3.5, score("newer", result), 1e-9)
        assertEquals(0.0, score("unknown", result), 1e-9)
    }

    @Test fun `a play in the future counts as now`() {
        assertEquals(2.0, score("odd", rank(song("odd", plays = 0, lastPlayed = now + day))), 1e-9)
    }

    @Test fun `a song with more finished plays and fewer skips scores higher`() {
        val keen = AutoSignal(sessions = 10, completions = 9, earlySkips = 0)
        val meh = AutoSignal(sessions = 10, completions = 1, earlySkips = 3)
        val result = rank(
            song("keen", plays = 10, lastPlayed = now, signal = keen), song("meh", plays = 10, lastPlayed = now, signal = meh),
        )
        assertEquals(listOf("keen", "meh"), result.map { it.facts.songId })
        assertTrue(score("keen", result) > score("meh", result))
    }

    // ─── Neighbours ─────────────────────────────────────────────────────────────────────────

    @Test fun `the songs after a recent song in a playlist get their share`() {
        // "a" was played last, "b" before it; "n2" follows "a" by two places and "b" by one.
        val boosts = CloudAutoPrepare.playlistNeighbourBoosts(
            recentFirst = listOf("a", "b"),
            playlists = listOf(listOf("x", "a", "n1", "n2", "n3", "n4", "n5", "n6"), listOf("b", "n2")),
        )
        assertEquals(0.5, boosts.getValue("n1"), 1e-9)
        assertEquals(0.8333333333333333, boosts.getValue("n2"), 1e-9)
        assertEquals(0.25, boosts.getValue("n3"), 1e-9)
        assertEquals(0.2, boosts.getValue("n4"), 1e-9)
        assertEquals(0.16666666666666666, boosts.getValue("n5"), 1e-9)
        // The sixth place after the seed, and the song before it, get nothing.
        assertNull(boosts["n6"])
        assertNull(boosts["x"])
        val ids = listOf("a", "b", "x", "n1", "n2", "n3", "n4", "n5", "n6")
        val result = rank(ids.map { song(it, neighbour = boosts[it] ?: 0.0) })
        assertEquals(listOf("n2", "n1", "n3", "n4", "n5"), result.take(5).map { it.facts.songId })
        assertEquals(0.0, score("n6", result), 1e-9)
        assertEquals(0.0, score("x", result), 1e-9)
    }

    @Test fun `only the five most recent distinct songs seed the neighbours`() {
        // s1 was played twice, so it takes one place; s6 is the sixth distinct song and seeds nothing.
        val boosts = CloudAutoPrepare.playlistNeighbourBoosts(
            recentFirst = listOf("s1", "s2", "s1", "s3", "s4", "s5", "s6"),
            playlists = listOf(listOf("s6", "t1"), listOf("s1", "t2")),
        )
        assertNull(boosts["t1"])
        assertEquals(0.5, boosts.getValue("t2"), 1e-9)
        assertTrue(CloudAutoPrepare.playlistNeighbourBoosts(emptyList(), listOf(listOf("a", "b"))).isEmpty())
    }

    // ─── Albums ─────────────────────────────────────────────────────────────────────────────

    @Test fun `songs on the album of one of the eight best-scoring songs get a lift`() {
        val songs = (1..8).map { song("f$it", favorite = true, album = "album$it") } +
            song("sib1", album = "album1") +
            song("g9", album = "album50", lastPlayed = now) +
            song("sib50", album = "album50") +
            song("loner", album = null)
        // g9 is the ninth best (a play just now = 2.0), so its album is not lifted.
        val result = rank(songs)
        // A seed counts for its own album too.
        for (n in 1..8) assertEquals(3.8, score("f$n", result), 1e-9)
        assertEquals(0.8, score("sib1", result), 1e-9)
        assertEquals(0.8, result.first { it.facts.songId == "sib1" }.albumBoost, 1e-9)
        assertEquals(2.0, score("g9", result), 1e-9)
        assertEquals(0.0, score("sib50", result), 1e-9)
        assertEquals(0.0, score("loner", result), 1e-9)
    }

    @Test fun `a song that cannot be prepared still seeds its album`() {
        val result = rank(song("done", favorite = true, album = "three", instrumental = true), song("sibling", album = "three"))
        assertEquals(listOf("sibling"), result.map { it.facts.songId })
        assertEquals(0.8, score("sibling", result), 1e-9)
    }

    @Test fun `songs by a blocked artist seed nothing`() {
        // The blocked song would be the best seed by far (and sorts first); its album must not lift the sibling.
        val songs = (1..8).map { song("f$it", favorite = true, album = "album$it") } +
            song("a-blocked", favorite = true, lastPlayed = now, album = "nine", blocked = true) +
            song("sibling", album = "nine")
        val result = rank(songs)
        assertFalse(result.any { it.facts.songId == "a-blocked" })
        assertEquals(0.0, score("sibling", result), 1e-9)
        assertEquals(3.8, score("f1", result), 1e-9)
    }

    // ─── Exclusions ─────────────────────────────────────────────────────────────────────────

    @Test fun `songs that must be left alone are not candidates`() {
        val heavySkips = AutoSignal(sessions = 8, completions = 0, earlySkips = 5)
        val fairSkips = AutoSignal(sessions = 8, completions = 0, earlySkips = 4)
        val result = rank(
            song("kept", favorite = true),
            song("has-instrumental", favorite = true, instrumental = true),
            song("pending-job", favorite = true, pending = true),
            song("too-short", favorite = true, duration = 60_000),
            song("just-long-enough", favorite = true, duration = 60_001),
            song("longest", favorite = true, duration = 480_000),
            song("too-long", favorite = true, duration = 480_001),
            song("skipped-a-lot", favorite = true, signal = heavySkips),
            song("skipped-some", favorite = true, signal = fairSkips),
            song("blocked", favorite = true, blocked = true),
        )
        assertEquals(setOf("kept", "just-long-enough", "longest", "skipped-some"), result.map { it.facts.songId }.toSet())
        assertEquals("It already has an instrumental.", CloudAutoPrepare.excludedBecause(song("x", instrumental = true)))
        assertEquals("You often skip it.", CloudAutoPrepare.excludedBecause(song("x", signal = heavySkips)))
        assertNull(CloudAutoPrepare.excludedBecause(song("x")))
        assertTrue(CloudAutoPrepare.isPreparable(60_001))
        assertFalse(CloudAutoPrepare.isPreparable(60_000))
        assertFalse(CloudAutoPrepare.isPreparable(480_001))
    }

    // ─── Order ──────────────────────────────────────────────────────────────────────────────

    @Test fun `equal scores come out in id order`() {
        assertEquals(listOf("a", "b", "c"), rank(listOf("b", "a", "c").map { song(it, favorite = true) }).map { it.facts.songId })
        assertTrue(rank(emptyList()).isEmpty())
    }

    @Test fun `the same inputs give the same order every time`() {
        val songs = (0 until 40).map { song("s$it", favorite = it % 3 == 0, dateAdded = now - it * day) }
        val first = rank(songs).map { it.facts.songId }
        val random = Random(7)
        repeat(5) { assertEquals(first, rank(songs.shuffled(random)).map { it.facts.songId }) }
    }

    // ─── Picking a batch ────────────────────────────────────────────────────────────────────

    private fun state(
        daily: Int = 12,
        autoBudget: Long = 1_000_000,
        autoCommitted: Long = 0,
        capRemaining: Long = 3_000_000,
        resting: Set<String> = emptySet(),
    ) = AutoSelectionState(
        dailyRemaining = daily, autoBudgetMicroUsd = autoBudget, autoCommittedMicroUsd = autoCommitted,
        capRemainingMicroUsd = capRemaining, pricePerSecondMicroUsd = 192, isCoolingDown = { it in resting },
    )

    private val library = listOf("a", "b", "c", "d", "e").map { song(it, favorite = true) }

    @Test fun `a batch is the best songs whose audio is on the phone, up to the batch size and the day's room`() {
        val ranked = rank(library)
        assertEquals(listOf("a", "b", "c"), CloudAutoPrepare.select(ranked, state()).picks.map { it.facts.songId })
        assertEquals(listOf("a", "b"), CloudAutoPrepare.select(ranked, state(daily = 2)).picks.map { it.facts.songId })
        assertTrue(CloudAutoPrepare.select(ranked, state(daily = 0)).picks.isEmpty())
        // Songs whose audio isn't there, or that rest after a failure, are passed over, not the end of the list.
        val partly = rank(library.map { if (it.songId == "a") it.copy(audioCached = false) else it })
        assertEquals(listOf("b", "c", "d"), CloudAutoPrepare.select(partly, state()).picks.map { it.facts.songId })
        assertEquals(listOf("a", "d", "e"), CloudAutoPrepare.select(ranked, state(resting = setOf("b", "c"))).picks.map { it.facts.songId })
    }

    @Test fun `a batch stops where the automatic share or the month's cap runs out`() {
        val ranked = rank(library)
        // A 3-minute song: 25 s warm (33 s scaled to 180 s, rounded up) and one 15 s cold start per batch on one worker,
        // at 192 µ$/s.
        val one = (15L + 25) * 192
        val two = (15L + 50) * 192
        val three = (15L + 75) * 192
        assertEquals(one, CloudAutoPrepare.select(ranked, state(autoBudget = one)).estimateMicroUsd)
        assertEquals(1, CloudAutoPrepare.select(ranked, state(autoBudget = two - 1)).picks.size)
        assertEquals(2, CloudAutoPrepare.select(ranked, state(autoBudget = two)).picks.size)
        assertEquals(two, CloudAutoPrepare.select(ranked, state(autoBudget = two)).estimateMicroUsd)
        assertEquals(3, CloudAutoPrepare.select(ranked, state(autoBudget = three)).picks.size)
        // What automatic jobs already committed counts against their share…
        assertEquals(1, CloudAutoPrepare.select(ranked, state(autoBudget = two, autoCommitted = two - one)).picks.size)
        assertTrue(CloudAutoPrepare.select(ranked, state(autoBudget = two, autoCommitted = two)).picks.isEmpty())
        // …and the shared cap is a ceiling of its own, even with an ample automatic share.
        assertEquals(2, CloudAutoPrepare.select(ranked, state(capRemaining = two)).picks.size)
        assertTrue(CloudAutoPrepare.select(ranked, state(capRemaining = one - 1)).picks.isEmpty())
        assertTrue(CloudAutoPrepare.select(ranked, state(autoBudget = 0)).picks.isEmpty())
        // Three workers at once (the built-in keys): each song of the batch wakes its own, a cold start each.
        val threeWorkers = state(autoBudget = 3 * one).copy(parallelWorkers = 3)
        assertEquals(3, CloudAutoPrepare.select(ranked, threeWorkers).picks.size)
        assertEquals(3 * one, CloudAutoPrepare.select(ranked, threeWorkers).estimateMicroUsd)
        assertEquals(2, CloudAutoPrepare.select(ranked, threeWorkers.copy(autoBudgetMicroUsd = 3 * one - 1)).picks.size)
    }

    @Test fun `a song too long for what is left is skipped, and a shorter one further down may still fit`() {
        // The first pick would be 8 minutes (66 s of GPU); only the short one after it fits the $0.012 left.
        val long = song("long", favorite = true, duration = 480_000)
        val short = song("short", duration = 60_001)
        val selection = CloudAutoPrepare.select(rank(long, short), state(autoBudget = 12_000))
        assertEquals(listOf("short"), selection.picks.map { it.facts.songId })
    }

    // ─── What the settings screen says ──────────────────────────────────────────────────────

    @Test fun `about N songs a month comes from the cost model`() {
        // The built-in endpoint runs three at once: a batch of three wakes three workers, so each 4-minute song pays
        // 33 s of GPU plus a whole 15 s cold start, at $0.000192 a second.
        assertEquals(108, CloudAutoPrepare.songsPerMonth(1_000_000, 3))
        assertEquals(27, CloudAutoPrepare.songsPerMonth(250_000, 3))
        assertEquals(325, CloudAutoPrepare.songsPerMonth(3_000_000, 3))
        assertEquals(0, CloudAutoPrepare.songsPerMonth(9_215, 3))
        assertEquals(1, CloudAutoPrepare.songsPerMonth(9_216, 3))
        assertEquals(0, CloudAutoPrepare.songsPerMonth(0, 3))
        assertEquals(0, CloudAutoPrepare.songsPerMonth(-5, 3))
        // One worker (own keys): the batch shares one cold start, a third of it each.
        assertEquals(137, CloudAutoPrepare.songsPerMonth(1_000_000, 1))
        assertEquals(0, CloudAutoPrepare.songsPerMonth(7_295, 1))
        assertEquals(1, CloudAutoPrepare.songsPerMonth(7_296, 1))
        assertEquals(33L, CloudCost.WARM_SECONDS_STANDARD)
        assertEquals(15L, CloudCost.COLD_START_SECONDS)
    }

    @Test fun `the defaults are an affordable dozen a day`() {
        assertEquals(1_000_000L, CloudAutoPrepareDefaults.BUDGET_MICRO_USD)
        assertEquals(12, CloudAutoPrepareDefaults.DAILY_CAP)
        assertEquals(1..30, CloudAutoPrepareDefaults.MIN_DAILY_CAP..CloudAutoPrepareDefaults.MAX_DAILY_CAP)
        val snapshot = CloudSettingsSnapshot()
        assertFalse(snapshot.autoPrepareEnabled, "off until the person turns it on")
        assertEquals(1_000_000L, snapshot.autoPrepareMonthlyBudgetMicroUsd)
        assertEquals(12, snapshot.autoPrepareDailyCap)
    }

    @Test fun `the slider and the stepper keep to their ranges`() {
        assertEquals(0L, CloudAutoPrepareDefaults.clampBudget(-1))
        assertEquals(250_000L, CloudAutoPrepareDefaults.clampBudget(250_000))
        assertEquals(3_000_000L, CloudAutoPrepareDefaults.clampBudget(3_000_001))
        assertEquals(1, CloudAutoPrepareDefaults.clampDailyCap(0))
        assertEquals(12, CloudAutoPrepareDefaults.clampDailyCap(12))
        assertEquals(30, CloudAutoPrepareDefaults.clampDailyCap(99))
        // The automatic share is never more than the month's cap.
        val capped = CloudSettingsSnapshot(autoPrepareMonthlyBudgetMicroUsd = 3_000_000, monthlyCapMicroUsd = 2_000_000)
        assertEquals(2_000_000L, capped.effectiveAutoBudgetMicroUsd)
        assertEquals(0L, CloudSettingsSnapshot(autoPrepareMonthlyBudgetMicroUsd = -4).effectiveAutoBudgetMicroUsd)
    }

    @Test fun `the line under the switch says how far it has got, or what it waits for`() {
        assertEquals("", CloudAutoPrepare.statusLine(false, 3, 120_000, 1_000_000, null))
        // Switched on while Cloud processing is off: the settings screen says why nothing happens.
        assertEquals("Cloud processing is off", CloudAutoPrepare.CLOUD_OFF_LINE)
        assertEquals("Waiting for Wi-Fi", CloudAutoPrepare.statusLine(true, 3, 120_000, 1_000_000, "Waiting for Wi-Fi"))
        assertEquals("3 songs today · $0.12 of $1.00 this month", CloudAutoPrepare.statusLine(true, 3, 120_000, 1_000_000, null))
        assertEquals("1 song today · $0.00 of $0.50 this month", CloudAutoPrepare.statusLine(true, 1, 0, 500_000, null))
        assertEquals("No songs yet today · $0.00 of $1.00 this month", CloudAutoPrepare.statusLine(true, 0, 0, 1_000_000, null))
    }

    @Test fun `each pick says why, in plain words`() {
        val facts = song("s", favorite = true, plays = 3, lastPlayed = now - 2 * day, dateAdded = now - 5 * day, neighbour = 0.5)
            .copy(title = "Title", artist = "Artist")
        val line = CloudAutoPrepare.whyLine(AutoRanked(facts, 7.25, albumBoost = 0.8), now)
        assertEquals(
            "Title – Artist: a favorite, played 3 times, played lately, added lately, next to songs you play, " +
                "on an album you play (score 7.3)",
            line,
        )
        assertEquals("s: worth a try (score 0.0)", CloudAutoPrepare.whyLine(AutoRanked(song("s"), 0.0), now))
        assertEquals("s: played 1 time (score 1.0)", CloudAutoPrepare.whyLine(AutoRanked(song("s", plays = 1), 1.0), now))
    }

    // ─── When the phone may be used ─────────────────────────────────────────────────────────

    private val ready = AutoConditions(
        unmetered = true, charging = true, batteryPercent = 80, thermalStatus = 0,
        freeBytes = AutomaticStudioPolicy.MIN_FREE_BYTES, playbackActive = false,
    )

    @Test fun `automatic preparing needs Wi-Fi, a charger, a healthy battery, a cool phone, storage and silence`() {
        assertNull(CloudAutoConditions.block(ready))
        assertEquals(AutoBlock.NOT_ON_WIFI, CloudAutoConditions.block(ready.copy(unmetered = false)))
        assertEquals(AutoBlock.NOT_CHARGING, CloudAutoConditions.block(ready.copy(charging = false)))
        assertEquals(AutoBlock.BATTERY_LOW, CloudAutoConditions.block(ready.copy(batteryPercent = 14)))
        assertNull(CloudAutoConditions.block(ready.copy(batteryPercent = 15)))
        assertNull(CloudAutoConditions.block(ready.copy(batteryPercent = -1)), "an unknown battery doesn't block")
        assertEquals(AutoBlock.TOO_HOT, CloudAutoConditions.block(ready.copy(thermalStatus = CloudAutoConditions.HOT_THERMAL_STATUS)))
        assertEquals(AutoBlock.STORAGE_LOW, CloudAutoConditions.block(ready.copy(freeBytes = AutomaticStudioPolicy.MIN_FREE_BYTES - 1)))
        assertEquals(AutoBlock.PLAYING, CloudAutoConditions.block(ready.copy(playbackActive = true)))
        // Several at once: the first the person can fix, in the order they would fix it.
        assertEquals(AutoBlock.NOT_ON_WIFI, CloudAutoConditions.block(ready.copy(unmetered = false, charging = false, playbackActive = true)))
        assertEquals("Waiting for Wi-Fi", AutoBlock.NOT_ON_WIFI.label)
    }

    @Test fun `a job's own steps need only Wi-Fi, a charger and a healthy battery`() {
        assertTrue(CloudAutoConditions.workAllowed(ready))
        assertFalse(CloudAutoConditions.workAllowed(ready.copy(unmetered = false)))
        assertFalse(CloudAutoConditions.workAllowed(ready.copy(charging = false)))
        assertFalse(CloudAutoConditions.workAllowed(ready.copy(batteryPercent = 10)))
        // Music, heat and storage are the worker's to check before it starts a run, not a reason to stop a job midway.
        assertTrue(CloudAutoConditions.workAllowed(ready.copy(playbackActive = true, thermalStatus = 3)))
    }
}

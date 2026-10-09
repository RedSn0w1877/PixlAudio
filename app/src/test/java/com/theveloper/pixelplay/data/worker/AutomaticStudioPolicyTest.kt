package com.theveloper.pixelplay.data.worker

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AutomaticStudioPolicyTest {
    private val healthy = AutomaticStudioPolicy.Conditions(true, true, true, 75, false, 0, 2_147_483_648)

    @Test fun `background work is allowed and each disabled feature is independently blocked`() {
        assertNull(AutomaticStudioPolicy.blockedReason(healthy.copy(appVisible = false), AutomaticStudioKind.LYRICS))
        val disabledLyrics = healthy.copy(lyricsEnabled = false)
        assertNotNull(AutomaticStudioPolicy.blockedReason(disabledLyrics, AutomaticStudioKind.LYRICS))
        assertNull(AutomaticStudioPolicy.blockedReason(disabledLyrics, AutomaticStudioKind.INSTRUMENTAL))
        val disabledStems = healthy.copy(instrumentalsEnabled = false)
        assertNotNull(AutomaticStudioPolicy.blockedReason(disabledStems, AutomaticStudioKind.INSTRUMENTAL))
        assertNull(AutomaticStudioPolicy.blockedReason(disabledStems, AutomaticStudioKind.LYRICS))
    }

    @Test fun `priority keeps never played songs below recent favorites`() {
        val now = 10_000_000L
        val recent = AutomaticStudioPolicy.priority("recent", null, false, 1, now - 2_000, now)
        val never = AutomaticStudioPolicy.priority("never", null, false, 0, 0, now)
        val favorite = AutomaticStudioPolicy.priority("favorite", null, true, 0, 0, now)
        assertTrue(recent > never)
        assertTrue(favorite > never)
        assertEquals(1_000_000L, AutomaticStudioPolicy.priority("current", "current", false, 0, 0, now))
    }

    @Test fun `low or unknown battery defers until charging`() {
        for (battery in listOf(-1, 0, 39)) {
            assertNotNull(AutomaticStudioPolicy.blockedReason(healthy.copy(batteryPercent = battery), AutomaticStudioKind.INSTRUMENTAL))
            assertNull(AutomaticStudioPolicy.blockedReason(healthy.copy(batteryPercent = battery, charging = true), AutomaticStudioKind.INSTRUMENTAL))
        }
        assertNull(AutomaticStudioPolicy.blockedReason(healthy.copy(batteryPercent = 40), AutomaticStudioKind.INSTRUMENTAL))
    }

    @Test fun `moderate thermal pressure or insufficient space stops unattended work`() {
        assertNotNull(AutomaticStudioPolicy.blockedReason(healthy.copy(thermalStatus = 2), AutomaticStudioKind.LYRICS))
        assertNull(AutomaticStudioPolicy.blockedReason(healthy.copy(thermalStatus = 1), AutomaticStudioKind.LYRICS))
        assertNotNull(AutomaticStudioPolicy.blockedReason(healthy.copy(freeBytes = AutomaticStudioPolicy.MIN_FREE_BYTES - 1), AutomaticStudioKind.INSTRUMENTAL))
        assertNull(AutomaticStudioPolicy.blockedReason(healthy.copy(freeBytes = AutomaticStudioPolicy.MIN_FREE_BYTES), AutomaticStudioKind.INSTRUMENTAL))
    }

    @Test fun `unknown duration and long recordings require manual processing`() {
        assertFalse(AutomaticStudioPolicy.canProcessDuration(0))
        assertFalse(AutomaticStudioPolicy.canProcessDuration(-1))
        assertTrue(AutomaticStudioPolicy.canProcessDuration(180_000))
        assertTrue(AutomaticStudioPolicy.canProcessDuration(AutomaticStudioPolicy.MAX_SONG_DURATION_MS))
        assertFalse(AutomaticStudioPolicy.canProcessDuration(AutomaticStudioPolicy.MAX_SONG_DURATION_MS + 1))
        assertTrue(AutomaticStudioPolicy.MAX_WORK_DURATION_MS < 10 * 60_000)
    }

    @Test fun `offline catalogs defer without blocking already local instrumental processing`() {
        val offline = healthy.copy(validatedNetwork = false)
        assertNotNull(AutomaticStudioPolicy.blockedReason(offline, AutomaticStudioKind.LYRICS))
        assertNull(AutomaticStudioPolicy.blockedReason(offline, AutomaticStudioKind.INSTRUMENTAL))
    }

    @Test fun `current and recent songs win and duplicate favorites cannot fill the candidate list`() {
        assertEquals(listOf("current", "recent", "favorite", "local"), AutomaticStudioPolicy.orderedIds(
            "current", listOf("recent", "current"), listOf("favorite", "recent"), listOf("local", "favorite", "")
        ))
        assertEquals(40, AutomaticStudioPolicy.orderedIds(null, (1..100).map(Int::toString), emptyList(), emptyList()).size)
    }

    @Test fun `instrumentals need local audio while catalog lyrics can prepare streaming songs`() {
        assertFalse(AutomaticStudioPolicy.canSchedule(AutomaticStudioKind.INSTRUMENTAL, false, false, 0, 1))
        assertTrue(AutomaticStudioPolicy.canSchedule(AutomaticStudioKind.INSTRUMENTAL, true, false, 0, 1))
        assertTrue(AutomaticStudioPolicy.canSchedule(AutomaticStudioKind.LYRICS, false, false, 0, 1))
        assertFalse(AutomaticStudioPolicy.canSchedule(AutomaticStudioKind.LYRICS, true, true, 0, 1))
        assertFalse(AutomaticStudioPolicy.canSchedule(AutomaticStudioKind.INSTRUMENTAL, true, true, 0, 1))
    }

    @Test fun `persistent cooldowns prevent repeated failed or missing lookups until their deadline`() {
        val firstProcess = AutomaticStudioCooldowns()
        firstProcess.record("LYRICS:song", 500)
        val restarted = AutomaticStudioCooldowns(firstProcess.snapshot())
        assertFalse(AutomaticStudioPolicy.canSchedule(AutomaticStudioKind.LYRICS, true, false, restarted.until("LYRICS:song"), 499))
        assertTrue(AutomaticStudioPolicy.canSchedule(AutomaticStudioKind.LYRICS, true, false, restarted.until("LYRICS:song"), 500))
        assertEquals(0L, restarted.until("INSTRUMENTAL:song"))
    }

    @Test fun `the re-check loop and the work query stay quiet while music plays, the app is open or the feature is off`() {
        assertTrue(AutomaticStudioPolicy.shouldPoll(enabled = true, playbackActive = false, appVisible = false))
        assertFalse(AutomaticStudioPolicy.shouldPoll(enabled = false, playbackActive = false, appVisible = false))
        assertFalse(AutomaticStudioPolicy.shouldPoll(enabled = true, playbackActive = true, appVisible = false))
        assertFalse(AutomaticStudioPolicy.shouldPoll(enabled = true, playbackActive = false, appVisible = true))

        // A scan that cannot schedule only reads WorkManager when an automatic job may still be running.
        assertFalse(AutomaticStudioPolicy.scanNeedsWorkQuery(enabled = true, playbackActive = true, appVisible = false, automaticMayBeRunning = false))
        assertFalse(AutomaticStudioPolicy.scanNeedsWorkQuery(enabled = false, playbackActive = false, appVisible = false, automaticMayBeRunning = false))
        assertFalse(AutomaticStudioPolicy.scanNeedsWorkQuery(enabled = true, playbackActive = false, appVisible = true, automaticMayBeRunning = false))
        assertTrue(AutomaticStudioPolicy.scanNeedsWorkQuery(enabled = true, playbackActive = true, appVisible = false, automaticMayBeRunning = true))
        assertTrue(AutomaticStudioPolicy.scanNeedsWorkQuery(enabled = false, playbackActive = false, appVisible = false, automaticMayBeRunning = true))
        assertTrue(AutomaticStudioPolicy.scanNeedsWorkQuery(enabled = true, playbackActive = false, appVisible = false, automaticMayBeRunning = false))
    }

    private class FakeBudgetStore : AutomaticStudioBudget.Store {
        var saved: Pair<Long, Int>? = null
        override fun load() = saved
        override fun save(windowStartedMs: Long, jobs: Int) { saved = windowStartedMs to jobs }
    }

    @Test fun `the job budget survives a new process instead of starting full again`() {
        val store = FakeBudgetStore()
        val firstProcess = AutomaticStudioBudget(store)
        firstProcess.recordJob(1_000)
        firstProcess.recordJob(2_000)
        assertEquals(2, firstProcess.jobsInWindow(3_000))

        val secondProcess = AutomaticStudioBudget(store)
        assertEquals(2, secondProcess.jobsInWindow(4_000))
        secondProcess.recordJob(5_000)
        assertEquals(3, AutomaticStudioBudget(store).jobsInWindow(6_000))
    }

    @Test fun `the budget window rolls over after six hours or when the clock goes backwards`() {
        val store = FakeBudgetStore()
        val budget = AutomaticStudioBudget(store)
        budget.recordJob(1_000)
        budget.recordJob(1_000)
        assertEquals(2, budget.jobsInWindow(1_000 + AutomaticStudioPolicy.BACKGROUND_WINDOW_MS - 1))
        assertEquals(0, budget.jobsInWindow(1_000 + AutomaticStudioPolicy.BACKGROUND_WINDOW_MS))

        budget.recordJob(10_000_000_000L)
        assertEquals(1, budget.jobsInWindow(10_000_000_001L))
        assertEquals(0, budget.jobsInWindow(5L)) // clock moved backwards
    }

    @Test fun `a job cut at its time budget waits hours before it is tried again`() {
        assertTrue(AutomaticStudioPolicy.TIMEOUT_COOLDOWN_MS >= 6 * 60 * 60_000L)
        assertTrue(AutomaticStudioPolicy.TIMEOUT_COOLDOWN_MS > AutomaticStudioPolicy.DEFERRED_COOLDOWN_MS)
    }

    @Test fun `an idle library walk is not repeated until something relevant changes`() {
        val memo = IdleWalkMemo(ttlMs = 10_000)
        assertFalse(memo.shouldSkipWalk(100)) // never walked yet
        memo.markNothingToDo(100)
        assertTrue(memo.shouldSkipWalk(101))
        assertTrue(memo.shouldSkipWalk(10_099))
        assertFalse(memo.shouldSkipWalk(10_100)) // the answer is re-checked after the ttl
        memo.markNothingToDo(20_000)
        assertTrue(memo.shouldSkipWalk(20_001))
        memo.invalidate() // song changed, setting toggled, job finished...
        assertFalse(memo.shouldSkipWalk(20_002))
    }

    @Test fun `ledger keeps its bound on load and update without evicting the latest attempt`() {
        val ledger = AutomaticStudioCooldowns(mapOf("old" to 1L, "middle" to 2L, "new" to 3L), capacity = 2)
        assertEquals(setOf("middle", "new"), ledger.snapshot().keys)
        ledger.record("middle", 4)
        ledger.record("latest", 5)
        assertEquals(setOf("middle", "latest"), ledger.snapshot().keys)
    }
}

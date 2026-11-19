package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.source.HostBudget
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The day's requests are counted as requests and survive a restart (docs/brain-spec.md, 2.1, 3.7). */
class RequestLedgerTest {
    private var now = 1_760_000_000_000L
    private val clock = BrainClock { now }
    private val states = FakeBrainStateDao()

    @Test
    fun `every token the bucket gives out is a request, and a restart does not give the day a new count`() = runTest {
        val ledger = RequestLedger(states, clock)
        val budget = HostBudget(clock = { now }, onSpend = { ledger.record(it) }, dayHasRoom = { ledger.hasRoom() })
        repeat(5) { i -> assertEquals(i, budget.withPermit { i }) }
        assertEquals(5, ledger.countSince(now - HostBudget.DAY_MS))

        // A new process: the bucket is full again, the ledger remembers.
        var settled = false
        val deadline = System.currentTimeMillis() + 5_000L
        while (!settled && System.currentTimeMillis() < deadline) {
            settled = RequestLedger(states, clock).countSince(now - HostBudget.DAY_MS) == 5
            if (!settled) Thread.sleep(10)
        }
        assertTrue(settled, "the five requests were saved")
    }

    @Test
    fun `the sixty-first request of a day is refused whoever asks, and a day later there is room again`() = runTest {
        val ledger = RequestLedger(states, clock)
        repeat(HostBudget.DAILY_LIMIT) { ledger.record(now) }
        assertFalse(ledger.hasRoom())
        val budget = HostBudget(clock = { now }, onSpend = { ledger.record(it) }, dayHasRoom = { ledger.hasRoom() })
        assertNull(budget.withPermit { "asked" }, "refused before a token is taken")
        now += HostBudget.DAY_MS + 1
        assertTrue(ledger.hasRoom())
    }
}

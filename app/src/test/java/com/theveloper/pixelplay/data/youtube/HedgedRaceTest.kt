package com.theveloper.pixelplay.data.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HedgedRaceTest {

    /** A strategy that answers [value] (null = fails) after [ms] of virtual time. */
    private class Strategy(val ms: Long, val value: String?)

    private class Log {
        val startedAt = mutableMapOf<Int, Long>()
        val cancelled = mutableListOf<Int>()
        val finished = mutableListOf<Int>()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.runRace(
        strategies: List<Strategy>,
        afterMs: Long = 1_500,
        timeoutMs: Long = 8_000,
        log: Log = Log()
    ): HedgedRace.Winner<String>? = HedgedRace.race(
        count = strategies.size,
        afterMs = afterMs,
        strategyTimeoutMs = timeoutMs,
        attempt = { index ->
            log.startedAt[index] = currentTime
            try {
                delay(strategies[index].ms)
                strategies[index].value
            } catch (e: CancellationException) {
                log.cancelled += index
                throw e
            }
        },
        onFinished = { index, _ -> log.finished += index }
    )

    @Test
    fun `a slow first client loses to a fast second once afterMs passes`() = runTest {
        val log = Log()
        val winner = runRace(listOf(Strategy(5_000, "slow-url"), Strategy(300, "fast-url")), log = log)
        assertEquals(HedgedRace.Winner(1, "fast-url"), winner)
        assertEquals(1_500L, log.startedAt[1])
        assertEquals(1_800L, currentTime)
        assertEquals(listOf(0), log.cancelled)
    }

    @Test
    fun `a fast first client wins alone`() = runTest {
        val log = Log()
        val winner = runRace(listOf(Strategy(400, "first"), Strategy(100, "second")), log = log)
        assertEquals(HedgedRace.Winner(0, "first"), winner)
        assertEquals(setOf(0), log.startedAt.keys)
        assertEquals(400L, currentTime)
    }

    @Test
    fun `a failing client starts the next one at once`() = runTest {
        val log = Log()
        val winner = runRace(listOf(Strategy(200, null), Strategy(300, "second")), log = log)
        assertEquals(HedgedRace.Winner(1, "second"), winner)
        assertEquals(200L, log.startedAt[1])
        assertEquals(500L, currentTime)
        assertEquals(listOf(0, 1), log.finished)
    }

    @Test
    fun `an older client can still win while a newer one runs`() = runTest {
        val log = Log()
        val winner = runRace(listOf(Strategy(2_000, "first"), Strategy(5_000, "second")), log = log)
        assertEquals(HedgedRace.Winner(0, "first"), winner)
        assertEquals(2_000L, currentTime)
        assertEquals(listOf(1), log.cancelled)
    }

    @Test
    fun `the per-client timeout applies`() = runTest {
        val log = Log()
        val winner = runRace(
            listOf(Strategy(60_000, "too-late"), Strategy(60_000, "also-too-late")),
            afterMs = 1_000,
            timeoutMs = 3_000,
            log = log
        )
        assertNull(winner)
        // The second starts at 1 s and times out at 4 s; the first timed out at 3 s.
        assertEquals(4_000L, currentTime)
        assertEquals(listOf(0, 1), log.finished)
    }

    @Test
    fun `all clients failing gives null after trying every one in order`() = runTest {
        val log = Log()
        val winner = runRace(listOf(Strategy(100, null), Strategy(100, null), Strategy(100, null)), log = log)
        assertNull(winner)
        assertEquals(mapOf(0 to 0L, 1 to 100L, 2 to 200L), log.startedAt)
        assertEquals(300L, currentTime)
    }

    @Test
    fun `a staircase of slow clients overlaps one every afterMs`() = runTest {
        val log = Log()
        val winner = runRace(
            listOf(Strategy(10_000, null), Strategy(10_000, null), Strategy(500, "third")),
            afterMs = 1_000,
            log = log
        )
        assertEquals(HedgedRace.Winner(2, "third"), winner)
        assertEquals(mapOf(0 to 0L, 1 to 1_000L, 2 to 2_000L), log.startedAt)
        assertEquals(2_500L, currentTime)
        assertEquals(listOf(0, 1), log.cancelled.sorted())
    }

    @Test
    fun `no strategies means no winner`() = runTest {
        assertNull(runRace(emptyList()))
    }
}

package com.theveloper.pixelplay.data.brain.source

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * One token bucket and one parallelism limit per host (docs/brain-spec.md, 2.1 and 12.1). Stage 1 has
 * a single bucket, for YouTube Music: radios are fetched on demand while music plays, about 2 per 10
 * autoplayed tracks, at most [DAILY_LIMIT] a day and [MAX_PARALLEL] at a time.
 *
 * The bucket lives in memory; the caller also counts the radios stored in the last 24 hours, so a
 * process restart cannot reset the daily limit.
 */
class HostBudget(
    private val capacity: Int = BURST,
    private val refillIntervalMs: Long = DAY_MS / DAILY_LIMIT,
    maxParallel: Int = MAX_PARALLEL,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val parallel = Semaphore(maxParallel)
    private var tokens = capacity
    private var lastRefillMs = clock()

    /** Takes one token when there is one. */
    @Synchronized
    fun tryAcquire(): Boolean {
        refill()
        if (tokens <= 0) return false
        tokens--
        return true
    }

    @Synchronized
    fun available(): Int {
        refill()
        return tokens
    }

    /** Runs [block] under the parallelism limit when a token is available; null when the budget is spent. */
    suspend fun <T> withPermit(block: suspend () -> T): T? {
        if (!tryAcquire()) return null
        return parallel.withPermit { block() }
    }

    private fun refill() {
        val now = clock()
        if (now < lastRefillMs) {
            // The clock went back: start counting again from now, keep what is left.
            lastRefillMs = now
            return
        }
        val earned = ((now - lastRefillMs) / refillIntervalMs).toInt()
        if (earned > 0) {
            tokens = minOf(capacity, tokens + earned)
            lastRefillMs += earned * refillIntervalMs
        }
    }

    companion object {
        const val DAILY_LIMIT = 60
        const val MAX_PARALLEL = 2

        /** Requests that may go out back to back: three anchors plus one continuation chain, with room. */
        const val BURST = 12
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

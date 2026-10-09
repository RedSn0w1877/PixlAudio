package com.theveloper.pixelplay.data.recommendation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Home asks the catalog whether a network refresh is due BEFORE ranking the library for seeds, so a
 * fresh cache, being offline or a retry backoff no longer costs a second whole-library plan.
 */
class HomeCatalogRefreshGateTest {
    private val hour = 60 * 60_000L
    private val now = 1_800_000_000_000L

    private fun due(
        online: Boolean = true,
        force: Boolean = false,
        updatedAgoMs: Long = 7 * hour,
        lastAttemptAgoMs: Long = 10 * hour,
    ) = HomeCatalogRepository.networkRefreshDue(
        online = online, force = force, nowMs = now,
        updatedAtMs = now - updatedAgoMs, lastAttemptMs = now - lastAttemptAgoMs
    )

    @Test fun `stale cache and online means the network is due`() {
        assertTrue(due())
    }

    @Test fun `a cache younger than six hours is not refreshed unless forced`() {
        assertFalse(due(updatedAgoMs = 5 * hour))
        assertTrue(due(updatedAgoMs = 5 * hour, force = true))
    }

    @Test fun `never refreshes offline, even when forced`() {
        assertFalse(due(online = false))
        assertFalse(due(online = false, force = true))
    }

    @Test fun `a recent attempt backs off for five minutes, even when forced`() {
        assertFalse(due(lastAttemptAgoMs = 2 * 60_000L))
        assertFalse(due(lastAttemptAgoMs = 2 * 60_000L, force = true))
        assertTrue(due(lastAttemptAgoMs = 6 * 60_000L))
    }

    @Test fun `an empty cache timestamp counts as stale`() {
        assertTrue(HomeCatalogRepository.networkRefreshDue(true, false, now, 0L, 0L))
    }
}

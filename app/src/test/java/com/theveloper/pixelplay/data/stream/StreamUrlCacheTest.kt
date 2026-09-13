package com.theveloper.pixelplay.data.stream

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamUrlCacheTest {
    @Test fun `foreground playback reuses the in-flight prefetch without another resolution`() = runTest {
        val cache = StreamUrlCache<String>(clockMs = { testScheduler.currentTime })
        val ready = CompletableDeferred<Unit>()
        var resolutions = 0
        val prefetch = async { cache.getOrResolve("track", 120_000) { resolutions++; ready.await(); "signed-url" } }
        runCurrent()
        val playback = async { cache.getOrResolve("track", 10_800_000) { resolutions++; "second-url" } }
        runCurrent()
        assertEquals(1, resolutions)
        assertFalse(playback.isCompleted)
        ready.complete(Unit)
        assertEquals("signed-url", prefetch.await())
        assertEquals("signed-url", playback.await())
        assertEquals(1, resolutions)
    }

    @Test fun `cancelled speculative lookup does not cancel foreground playback`() = runTest {
        val cache = StreamUrlCache<String>()
        val ready = CompletableDeferred<Unit>()
        val prefetch = async { cache.getOrResolve("track", 120_000) { ready.await(); "unused" } }
        runCurrent()
        val playback = async { cache.getOrResolve("track", 120_000) { "foreground-url" } }
        runCurrent()
        prefetch.cancelAndJoin()
        assertEquals("foreground-url", playback.await())
    }

    @Test fun `different tracks do not block each other even when hashes collide`() = runTest {
        // Java String hash collision that also hit the former 32 striped mutexes.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val cache = StreamUrlCache<String>()
        val pending = CompletableDeferred<Unit>()
        val slow = async { cache.getOrResolve("Aa", 120_000) { pending.await(); "slow" } }
        runCurrent()
        val foreground = async { cache.getOrResolve("BB", 120_000) { "ready" } }
        runCurrent()
        assertTrue(foreground.isCompleted)
        assertEquals("ready", foreground.await())
        slow.cancelAndJoin()
    }

    @Test fun `prefetched URLs expire after two minutes even when playback has a longer ttl`() = runTest {
        var now = 0L
        val cache = StreamUrlCache<String>(clockMs = { now })
        cache.getOrResolve("track", 120_000) { "prefetched" }
        now = 119_999
        assertEquals("prefetched", cache.getOrResolve("track", 10_800_000) { "too-soon" })
        now = 120_000
        assertEquals("fresh", cache.getOrResolve("track", 10_800_000) { "fresh" })
    }

    @Test fun `signed expiry is honored before the speculative ttl`() = runTest {
        var now = 0L
        val cache = StreamUrlCache<String>(clockMs = { now }, signedExpiryMs = { 30_000 })
        cache.getOrResolve("track", 120_000) { "old" }
        now = 30_000
        assertEquals("fresh", cache.getOrResolve("track", 120_000) { "fresh" })
    }

    @Test fun `failed and expired lookups are not retained`() = runTest {
        val cache = StreamUrlCache<String>(clockMs = { 50_000 }, signedExpiryMs = { 40_000 })
        assertNull(cache.getOrResolve("track", 120_000) { null })
        assertNull(cache.getOrResolve("track", 120_000) { " " })
        assertEquals("expired", cache.getOrResolve("track", 120_000) { "expired" })
        assertEquals("retry", cache.getOrResolve("track", 120_000) { "retry" })
    }

    @Test fun `upstream rejection and proxy stop invalidate cached URLs`() = runTest {
        val cache = StreamUrlCache<String>()
        cache.getOrResolve("one", 120_000) { "old-one" }
        cache.getOrResolve("two", 120_000) { "old-two" }
        cache.invalidate("one")
        assertEquals("new-one", cache.getOrResolve("one", 120_000) { "new-one" })
        assertEquals("old-two", cache.getOrResolve("two", 120_000) { "unexpected" })
        cache.clear()
        assertEquals("new-two", cache.getOrResolve("two", 120_000) { "new-two" })
    }
}

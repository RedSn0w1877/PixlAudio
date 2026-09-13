package com.theveloper.pixelplay.data.stream

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamUrlPrefetcherTest {
    @Test fun `repeated queue callbacks share one speculative lookup`() = runTest {
        val pending = CompletableDeferred<Unit>()
        var resolutions = 0
        val prefetcher = StreamUrlPrefetcher<String>(backgroundScope, { resolutions++; pending.await() })
        prefetcher.prefetch("track")
        runCurrent()
        prefetcher.prefetch("track")
        runCurrent()
        assertEquals(1, resolutions)
        pending.complete(Unit)
    }

    @Test fun `queue changes cancel obsolete lookups and pause cancels the latest`() = runTest {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val prefetcher = StreamUrlPrefetcher<String>(backgroundScope, { id ->
            started += id
            try { awaitCancellation() } finally { cancelled += id }
        })
        prefetcher.prefetch("old-next")
        runCurrent()
        prefetcher.prefetch("new-next")
        runCurrent()
        assertEquals(listOf("old-next", "new-next"), started)
        assertEquals(listOf("old-next"), cancelled)
        prefetcher.prefetch(null)
        runCurrent()
        assertEquals(started, cancelled)
    }

    @Test fun `a failed prefetch stays optional and can be retried`() = runTest {
        var attempts = 0
        var failures = 0
        val prefetcher = StreamUrlPrefetcher<String>(backgroundScope,
            resolve = { attempts++; if (attempts == 1) error("offline") },
            onFailure = { failures++ })
        prefetcher.prefetch("track")
        runCurrent()
        prefetcher.prefetch("track")
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, failures)
    }
}

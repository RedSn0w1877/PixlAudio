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

    @Test fun `a list is prepared in order, the next song first`() = runTest {
        val resolved = mutableListOf<String>()
        val prefetcher = StreamUrlPrefetcher<String>(backgroundScope, { id -> resolved += id })
        prefetcher.prefetch(listOf("next", "after-next"))
        runCurrent()
        assertEquals(listOf("next", "after-next"), resolved)
    }

    @Test fun `the same list again does not restart a running job`() = runTest {
        val pending = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val prefetcher = StreamUrlPrefetcher<String>(backgroundScope, { id -> started += id; pending.await() })
        prefetcher.prefetch(listOf("next", "after-next"))
        runCurrent()
        prefetcher.prefetch(listOf("next", "after-next"))
        runCurrent()
        assertEquals(listOf("next"), started)
        pending.complete(Unit)
        runCurrent()
        assertEquals(listOf("next", "after-next"), started)
    }

    @Test fun `a changed list cancels the old job and an empty list cancels everything`() = runTest {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val prefetcher = StreamUrlPrefetcher<String>(backgroundScope, { id ->
            started += id
            try { awaitCancellation() } finally { cancelled += id }
        })
        prefetcher.prefetch(listOf("a", "b"))
        runCurrent()
        prefetcher.prefetch(listOf("c", "d"))
        runCurrent()
        assertEquals(listOf("a", "c"), started)
        assertEquals(listOf("a"), cancelled)
        prefetcher.prefetch(emptyList())
        runCurrent()
        assertEquals(listOf("a", "c"), cancelled)
    }

    @Test fun `one failing song does not stop the next one`() = runTest {
        val resolved = mutableListOf<String>()
        var failures = 0
        val prefetcher = StreamUrlPrefetcher<String>(backgroundScope,
            resolve = { id ->
                if (id == "broken") error("no match")
                resolved += id
            },
            onFailure = { failures++ })
        prefetcher.prefetch(listOf("broken", "fine"))
        runCurrent()
        assertEquals(listOf("fine"), resolved)
        assertEquals(1, failures)
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

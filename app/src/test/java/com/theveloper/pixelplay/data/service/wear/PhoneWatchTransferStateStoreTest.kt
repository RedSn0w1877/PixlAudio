package com.theveloper.pixelplay.data.service.wear

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class PhoneWatchTransferStateStoreTest {

    @Test
    fun `songs marked present from several transfers at once are all kept`() {
        val store = PhoneWatchTransferStateStore()
        val threads = 8
        val perThread = 300
        val start = CountDownLatch(1)

        val workers = (0 until threads).map { t ->
            thread {
                start.await()
                for (i in 0 until perThread) store.markSongPresentOnWatch("node", "song-$t-$i")
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(threads * perThread, store.watchSongIds.value.size)
    }

    @Test
    fun `marking a song present is idempotent`() {
        val store = PhoneWatchTransferStateStore()

        store.markSongPresentOnWatch("node", "a")
        store.markSongPresentOnWatch("node", "a")
        store.markSongPresentOnWatch("other", "a")

        assertEquals(setOf("a"), store.watchSongIds.value)
    }
}

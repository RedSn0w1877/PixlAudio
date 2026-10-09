package com.theveloper.pixelplay.presentation.viewmodel

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Starting a big queue used to attach the rest of it in 200-item batches, and every batch is a
 * timeline change that makes the media session republish the whole queue. These tests pin the
 * new behaviour: at most two `addMediaItems` calls, and exactly the same final queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackQueueAttachTest {

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun items(prefix: String, count: Int): List<MediaItem> =
        List(count) { item("$prefix$it") }

    /** A player whose queue is a plain list, holding only the tapped song to start with. */
    private class FakeQueue(startId: String, var state: Int = Player.STATE_READY) {
        val queue = mutableListOf(MediaItem.Builder().setMediaId(startId).build())
        var addCalls = 0
        val player: Player = mockk(relaxed = true) {
            every { playbackState } answers { state }
            every { currentMediaItem } answers { queue.firstOrNull() }
            every { mediaItemCount } answers { queue.size }
            every { getMediaItemAt(any()) } answers { queue[firstArg<Int>()] }
            every { addMediaItems(any<Int>(), any<List<MediaItem>>()) } answers {
                addCalls++
                queue.addAll(firstArg<Int>(), secondArg<List<MediaItem>>())
            }
        }
    }

    /** The previous implementation, kept here as the reference the new one must match. */
    private fun legacyQueue(before: List<MediaItem>, current: MediaItem, after: List<MediaItem>): List<String> {
        val queue = mutableListOf(current)
        var inserted = 0
        while (inserted < before.size) {
            val end = (inserted + 200).coerceAtMost(before.size)
            queue.addAll(inserted, before.subList(inserted, end))
            inserted = end
        }
        inserted = 0
        while (inserted < after.size) {
            val end = (inserted + 200).coerceAtMost(after.size)
            queue.addAll(before.size + 1 + inserted, after.subList(inserted, end))
            inserted = end
        }
        return queue.map { it.mediaId }
    }

    @Test
    fun `a 5000 song queue is attached with at most two addMediaItems calls`() = runTest {
        val before = items("b", 2400)
        val after = items("a", 2599)
        val fake = FakeQueue("current")

        val attached = attachQueueSegmentsIfCurrent(fake.player, "current", before, after)

        assertTrue(attached)
        assertTrue(fake.addCalls <= 2, "addMediaItems called ${fake.addCalls} times")
        assertEquals(5000, fake.queue.size)
    }

    @Test
    fun `final queue is identical to the old 200 item batching`() = runTest {
        for ((beforeSize, afterSize) in listOf(0 to 0, 0 to 7, 5 to 0, 199 to 201, 200 to 200, 401 to 1234)) {
            val before = items("b", beforeSize)
            val after = items("a", afterSize)
            val fake = FakeQueue("current")

            attachQueueSegmentsIfCurrent(fake.player, "current", before, after)

            assertEquals(
                legacyQueue(before, item("current"), after),
                fake.queue.map { it.mediaId },
                "before=$beforeSize after=$afterSize"
            )
        }
    }

    @Test
    fun `empty sides are skipped`() = runTest {
        val fake = FakeQueue("current")

        attachQueueSegmentsIfCurrent(fake.player, "current", emptyList(), items("a", 3))

        assertEquals(1, fake.addCalls)
        assertEquals(listOf("current", "a0", "a1", "a2"), fake.queue.map { it.mediaId })
    }

    @Test
    fun `nothing is attached when the player already moved on`() = runTest {
        val wrongSong = FakeQueue("someone-else")
        assertFalse(attachQueueSegmentsIfCurrent(wrongSong.player, "current", items("b", 3), items("a", 3)))
        assertEquals(0, wrongSong.addCalls)

        val alreadyHasQueue = FakeQueue("current")
        alreadyHasQueue.queue.add(item("extra"))
        assertFalse(attachQueueSegmentsIfCurrent(alreadyHasQueue.player, "current", items("b", 3), items("a", 3)))
        assertEquals(0, alreadyHasQueue.addCalls)
    }

    @Test
    fun `a stream that never becomes ready still gets its queue after the settle window`() = runTest {
        val fake = FakeQueue("current", state = Player.STATE_BUFFERING)

        val attached = attachQueueSegmentsIfCurrent(fake.player, "current", items("b", 300), items("a", 300))

        assertTrue(attached)
        assertEquals(601, fake.queue.size)
        assertTrue(fake.addCalls <= 2)
    }

    @Test
    fun `small queues do not wait for the player`() = runTest {
        val fake = FakeQueue("current", state = Player.STATE_BUFFERING)

        attachQueueSegmentsIfCurrent(fake.player, "current", items("b", 10), items("a", 10))

        // No virtual time had to pass: the settle wait is skipped for queues of 200 or fewer.
        assertEquals(0L, testScheduler.currentTime)
        verify(exactly = 2) { fake.player.addMediaItems(any<Int>(), any<List<MediaItem>>()) }
    }
}

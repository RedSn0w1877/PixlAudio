package com.theveloper.pixelplay.data.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListeningCacheGateTest {

    private val nothing = ListeningCacheGate.Decision(cancelPending = false, startFor = null)

    @Test
    fun `a playing song starts one wait, a rebuffer keeps it`() {
        val gate = ListeningCacheGate()
        // Tapped: still buffering, nothing to count yet.
        assertEquals(nothing, gate.update("a", isPlaying = false))
        assertEquals(ListeningCacheGate.Decision(false, "a"), gate.update("a", isPlaying = true))
        // A mid-song rebuffer and the resume after it: same song, same wait.
        assertEquals(nothing, gate.update("a", isPlaying = false))
        assertEquals(nothing, gate.update("a", isPlaying = true))
    }

    @Test
    fun `a skip drops the skimmed song's wait even while the next song buffers`() {
        val gate = ListeningCacheGate()
        gate.update("a", isPlaying = true)
        // Skipped 2 s in; the next song takes a while to start. The old check returned early
        // here, so "a" was downloaded in full once its 5 s ran out.
        assertEquals(ListeningCacheGate.Decision(cancelPending = true, startFor = null), gate.update("b", isPlaying = false))
        assertEquals(ListeningCacheGate.Decision(false, "b"), gate.update("b", isPlaying = true))
    }

    @Test
    fun `a pause drops a pending wait and the resume starts it again`() {
        val gate = ListeningCacheGate()
        gate.update("a", isPlaying = true)
        assertEquals(ListeningCacheGate.Decision(cancelPending = true, startFor = null), gate.update(null, isPlaying = false))
        assertEquals(ListeningCacheGate.Decision(false, "a"), gate.update("a", isPlaying = true))
    }

    @Test
    fun `a song whose wait ran out is asked for once per play`() {
        val gate = ListeningCacheGate()
        gate.update("a", isPlaying = true)
        gate.waitFinished("a")
        // Pausing and resuming the same song asks nothing again, and there is nothing to cancel.
        assertEquals(nothing, gate.update(null, isPlaying = false))
        assertEquals(nothing, gate.update("a", isPlaying = true))
        // The next song gets its own wait.
        assertEquals(ListeningCacheGate.Decision(false, "b"), gate.update("b", isPlaying = true))
    }

    @Test
    fun `a local song drops the wait and starts none`() {
        val gate = ListeningCacheGate()
        gate.update("a", isPlaying = true)
        assertEquals(ListeningCacheGate.Decision(cancelPending = true, startFor = null), gate.update(null, isPlaying = true))
    }

    @Test
    fun `a stale finish does not end the current wait`() {
        val gate = ListeningCacheGate()
        gate.update("a", isPlaying = true)
        gate.update("b", isPlaying = true)
        gate.waitFinished("a")
        // "b" is still waiting, so a skip away from it must still cancel.
        assertEquals(ListeningCacheGate.Decision(cancelPending = true, startFor = "c"), gate.update("c", isPlaying = true))
    }
}

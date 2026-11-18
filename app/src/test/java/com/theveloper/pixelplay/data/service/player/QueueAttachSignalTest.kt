package com.theveloper.pixelplay.data.service.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The signal that keeps Autoplay away from the one-item queue of a tapped song while the rest of its
 * album or playlist is attached, and the dispatch gate that drives it.
 */
class QueueAttachSignalTest {

    private var nanos = 1_000_000_000L
    private val signal = QueueAttachSignal { nanos }

    @Test
    fun `attaching from begin to end, and the listener hears the end once`() {
        var ended = 0
        signal.setListener { ended++ }
        assertFalse(signal.isAttaching)
        signal.onBegin()
        assertTrue(signal.isAttaching)
        assertEquals(0, ended)
        signal.onEnd()
        assertFalse(signal.isAttaching)
        assertEquals(1, ended)
        // An end without a begin (a second report of the same end) says nothing.
        signal.onEnd()
        assertEquals(1, ended)
    }

    @Test
    fun `an attach that never reports its end cannot switch autoplay off for good`() {
        signal.onBegin()
        nanos += 9_000_000_000L
        assertTrue(signal.isAttaching)
        nanos += 2_000_000_000L
        assertFalse(signal.isAttaching)
    }

    @Test
    fun `a newer attach is not ended by the one it replaced`() {
        // What PlaybackStateHolder does: the signal ends only when the gate's CURRENT attach ended.
        val gate = QueueAttachGate()
        var ended = 0
        signal.setListener { ended++ }
        fun begin(): Int {
            signal.onBegin()
            return gate.begin()
        }
        fun abandon(token: Int) {
            gate.abandon(token)
            if (!gate.isAttaching) signal.onEnd()
        }
        fun end(token: Int) {
            gate.end(token)
            if (!gate.isAttaching) signal.onEnd()
        }
        val first = begin()
        val second = begin() // another song tapped before the first queue arrived
        abandon(first) // the first attach's coroutine is cancelled and reports
        assertTrue(signal.isAttaching)
        assertEquals(0, ended)
        end(second)
        assertFalse(signal.isAttaching)
        assertEquals(1, ended)
    }
}

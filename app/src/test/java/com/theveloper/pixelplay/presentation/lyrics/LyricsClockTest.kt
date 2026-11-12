package com.theveloper.pixelplay.presentation.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsClockTest {

    private val frame = 8_333_333L

    @Test
    fun resumeAfterIdle_isNotASeek_whenRebased() {
        var pos = 10_000L
        val clock = LyricsClock(positionProvider = { pos })
        clock.tick(0L)
        // Paused and idle for 30 s of wall-clock time, then play resumes from the same spot.
        clock.rebase()
        clock.isPlaying = true
        assertFalse(clock.tick(30_000_000_000L))
        assertFalse(clock.consumeSeek())
        pos += 8
        assertFalse(clock.tick(30_000_000_000L + frame))
        assertEquals(10_008L, clock.currentMs)
    }

    @Test
    fun resumeAfterIdle_withoutRebase_readsAsASeek() {
        val clock = LyricsClock(positionProvider = { 10_000L })
        clock.tick(0L)
        clock.isPlaying = true
        assertTrue(clock.tick(30_000_000_000L))
    }

    @Test
    fun aCorrectionWhilePlaying_easesIn_andNeverStepsBack() {
        var pos = 10_000L
        val clock = LyricsClock(positionProvider = { pos })
        clock.tick(0L)
        clock.isPlaying = true
        var t = 0L
        var last = clock.currentMs
        // The session's update lands: the player is 250 ms behind what the controller had extrapolated.
        repeat(60) {
            t += frame
            pos += 8
            if (it == 10) pos -= 250
            clock.tick(t)
            assertTrue("time stepped back at frame $it", clock.currentMs >= last)
            assertTrue("jumped at frame $it", clock.currentMs - last <= 40)
            last = clock.currentMs
        }
        // Caught up with the player within half a second.
        assertTrue(kotlin.math.abs(clock.currentMs - pos) <= 15)
        assertEquals(0, clock.seekCount)
    }

    @Test
    fun peek_readsPositionPlusOffset_withoutPublishing() {
        var pos = 5_000L
        val clock = LyricsClock(positionProvider = { pos }, offsetMsProvider = { 250L })
        clock.tick(0L)
        pos = 9_000L
        assertEquals(9_250L, clock.peekMs())
        assertEquals(5_250L, clock.currentMs)
    }
}

package com.theveloper.pixelplay.data.tais.lyrics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CtcAlignmentCoreTest {
    private fun emission(vararg tokens: Int) = tokens.map { token ->
        FloatArray(3) { if (it == token) -0.01f else -12f }
    }.toTypedArray()

    @Test fun `long intro and outro stay blank`() {
        val path = CtcAlignmentCore.align(emission(0, 0, 0, 0, 1, 1, 0, 2, 0, 0), intArrayOf(0, 1, 0, 2, 0))!!
        assertEquals(4, path.indexOfFirst { it == 1 })
        assertEquals(7, path.indexOfFirst { it == 3 })
        assertTrue(path.take(4).all { it == 0 })
        assertTrue(path.takeLast(2).all { it == 4 })
    }
    @Test fun `interlude does not pull next lyric forward`() {
        val path = CtcAlignmentCore.align(emission(1, 0, 0, 0, 0, 2), intArrayOf(0, 1, 0, 2, 0))!!
        assertEquals(5, path.indexOfFirst { it == 3 })
    }
    @Test fun `vocals may begin on the first frame`() {
        assertArrayEquals(intArrayOf(1), CtcAlignmentCore.align(emission(1), intArrayOf(0, 1, 0)))
    }
    @Test fun `repeated symbols require a separating blank`() {
        assertNull(CtcAlignmentCore.align(emission(1, 1), intArrayOf(0, 1, 0, 1, 0)))
        val path = CtcAlignmentCore.align(emission(1, 0, 1), intArrayOf(0, 1, 0, 1, 0))!!
        assertArrayEquals(intArrayOf(1, 2, 3), path)
    }
    @Test fun `overlap trimming covers every audio frame exactly once`() {
        for (samples in listOf(400, 719, 720, 256001, 320000, 2858256)) {
            val windows = CtcAlignmentCore.windows(samples)
            val kept = windows.flatMap { (it.firstFrame until it.endFrame).toList() }
            assertEquals((0 until (samples - 400) / 320 + 1).toList(), kept)
            for (w in windows) {
                assertTrue(w.inputStartSample >= 0 && w.inputEndSample <= samples)
                assertEquals(0, w.inputStartSample % 320)
                val produced = (w.inputEndSample - w.inputStartSample - 400) / 320 + 1
                assertTrue(w.localFirstFrame + w.keptFrames <= produced)
                assertTrue(w.inputEndSample - w.inputStartSample <= 320080)
            }
        }
        assertTrue(CtcAlignmentCore.windows(399).isEmpty())
    }
    @Test fun `large path is rejected before allocation`() {
        assertThrows(IllegalArgumentException::class.java) {
            CtcAlignmentCore.align(Array(10000) { floatArrayOf(0f, -1f) }, IntArray(10001))
        }
    }
    @Test fun `long alignment cooperates with cancellation`() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            CtcAlignmentCore.align(emission(*IntArray(100)), intArrayOf(0, 1, 0)) {
                throw java.util.concurrent.CancellationException()
            }
        }
    }
    @Test fun `weak acoustic matches are not accepted just because timings increase`() {
        assertFalse(CtcAlignmentCore.acceptsWordEvidence(listOf(0.1f, 0.2f, 0.15f, 0.3f)))
        assertFalse(CtcAlignmentCore.acceptsWordEvidence(listOf(0.95f, 0.95f, 0.05f, 0.05f)))
        assertFalse(CtcAlignmentCore.acceptsWordEvidence(listOf(Float.NaN)))
        assertTrue(CtcAlignmentCore.acceptsWordEvidence(listOf(0.8f, 0.7f, 0.85f, 0.9f)))
    }
}

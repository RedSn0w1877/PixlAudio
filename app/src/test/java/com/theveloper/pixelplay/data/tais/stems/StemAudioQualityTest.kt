package com.theveloper.pixelplay.data.tais.stems

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StemAudioQualityTest {
    @Test fun `instrumental energy survives where model predicts no voice`() {
        assertEquals(1f, StemAudioQuality.linkedInstrumentalMask(4f, 0f, 1000f))
    }

    @Test fun `strong modeled voice is removed above crossover`() {
        assertEquals(0f, StemAudioQuality.linkedInstrumentalMask(4f, 4f, 1000f))
    }

    @Test fun `bass crossover is continuous instead of a hard vocal leak boundary`() {
        assertEquals(1f, StemAudioQuality.linkedInstrumentalMask(1f, 1f, 40f))
        val lower = StemAudioQuality.linkedInstrumentalMask(1f, 1f, 99.9f)
        val upper = StemAudioQuality.linkedInstrumentalMask(1f, 1f, 100.1f)
        assertTrue(lower > upper)
        assertTrue(lower - upper < 0.01f)
    }

    @Test fun `linear gain preserves stereo balance and headroom`() {
        val gain = StemAudioQuality.peakSafeGain(floatArrayOf(0.9f, -0.2f), floatArrayOf(0.45f, 0.4f), 2f)
        assertTrue(0.9f * gain <= 0.980001f)
        assertEquals(2f, (0.9f * gain) / (0.45f * gain), 0.00001f)
    }

    @Test fun `non finite model output fails instead of publishing corrupt audio`() {
        assertThrows(IllegalArgumentException::class.java) {
            StemAudioQuality.linkedInstrumentalMask(1f, Float.NaN, 1000f)
        }
    }
}

package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.AudioFeatures
import com.theveloper.pixelplay.brain.TrackDna
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Stage 5: when a track is measured again, and what flow reads from the DNA row and the measurement together. */
class AudioMeasurePolicyTest {

    private val day = AudioMeasurePolicy.RETRY_AFTER_MS

    @Test
    fun `a track is measured once per estimator version and a failure waits`() {
        assertFalse(AudioMeasurePolicy.wants(AudioFeatures.VERSION, 0, 0L, 10 * day))
        assertTrue(AudioMeasurePolicy.wants(AudioFeatures.VERSION - 1, 0, 0L, 1L))
        assertFalse(AudioMeasurePolicy.wants(AudioFeatures.VERSION, 1, 0L, day - 1))
        assertTrue(AudioMeasurePolicy.wants(AudioFeatures.VERSION, 1, 0L, day))
        assertFalse(AudioMeasurePolicy.wants(AudioFeatures.VERSION, 3, 0L, 29 * day))
        assertTrue(AudioMeasurePolicy.wants(AudioFeatures.VERSION, 3, 0L, 30 * day))
    }

    @Test
    fun `a trusted measurement replaces Deezer's tempo, Deezer's gain stays`() {
        val strong = AudioFeatures.Result(1280, 900, -75, 40_000)
        val f = AudioMeasurePolicy.facts(1200, TrackDna.TEMPO_DEEZER, -90, 140, strong)!!
        assertEquals(1280, f.tempoBpmX10)
        assertEquals(-90, f.gainDbX10)
        assertEquals(140, f.energy)
        val weak = AudioFeatures.Result(1280, 300, -75, 40_000)
        assertEquals(1200, AudioMeasurePolicy.facts(1200, TrackDna.TEMPO_DEEZER, -90, 140, weak)!!.tempoBpmX10)
        val noDna = AudioMeasurePolicy.facts(0, TrackDna.TEMPO_UNKNOWN, TrackDna.GAIN_UNKNOWN, -1, strong)!!
        assertEquals(-75, noDna.gainDbX10)
        assertNull(AudioMeasurePolicy.facts(0, TrackDna.TEMPO_UNKNOWN, TrackDna.GAIN_UNKNOWN, -1, null))
    }
}

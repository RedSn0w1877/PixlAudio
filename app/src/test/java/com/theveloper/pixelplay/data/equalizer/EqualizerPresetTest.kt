package com.theveloper.pixelplay.data.equalizer

import org.junit.Assert.assertEquals
import org.junit.Test

class EqualizerPresetTest {
    @Test
    fun restoredNamedPresetUsesItsSavedCurveAndNormalizesOldBandCounts() {
        val saved = EqualizerPreset("Evening", "Evening", listOf(16, -20, 2, 1, 0), true)
        val resolved = EqualizerPreset.resolve("Evening", emptyList(), listOf(saved))
        assertEquals("Evening", resolved.name)
        assertEquals(listOf(15, -15, 2, 1, 0, 0, 0, 0, 0, 0), resolved.bandLevels)
    }

    @Test
    fun unsavedCustomAndBuiltInPresetsKeepTheirOwnBands() {
        val bands = List(10) { it }
        assertEquals(bands, EqualizerPreset.resolve("custom", bands, emptyList()).bandLevels)
        assertEquals(EqualizerPreset.ROCK, EqualizerPreset.resolve("rock", bands, emptyList()))
    }
}

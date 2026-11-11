package com.theveloper.pixelplay.data.preferences

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioQualityTest {

    @Test
    fun `stored names map over, old ones included`() {
        assertEquals(AudioQuality.PRO, AudioQuality.fromStoredName(null))
        assertEquals(AudioQuality.MEDIUM, AudioQuality.fromStoredName("STANDARD"))
        assertEquals(AudioQuality.PRO, AudioQuality.fromStoredName("ULTRASOUND"))
        assertEquals(AudioQuality.LOW, AudioQuality.fromStoredName("LOW"))
        assertEquals(AudioQuality.STUDIO_PRO, AudioQuality.fromStoredName("STUDIO_PRO"))
        assertEquals(AudioQuality.PRO, AudioQuality.fromStoredName("nonsense"))
        assertEquals(AudioQuality.PRO, AudioQuality.DEFAULT)
    }

    @Test
    fun `Pro plays FLAC first up to 48 kHz, Studio Pro any, the capped tiers never`() {
        assertTrue(AudioQuality.PRO.allowsFlacFirst(44_100))
        assertTrue(AudioQuality.PRO.allowsFlacFirst(48_000))
        assertTrue(AudioQuality.PRO.allowsFlacFirst(null))
        assertFalse(AudioQuality.PRO.allowsFlacFirst(96_000))
        assertTrue(AudioQuality.STUDIO_PRO.allowsFlacFirst(192_000))
        for (capped in listOf(AudioQuality.LOW, AudioQuality.MEDIUM, AudioQuality.HIGH)) {
            assertFalse(capped.allowsFlacFirst(44_100))
            assertFalse(capped.allowsFlacFirst(null))
        }
    }

    @Test
    fun `YouTube caps and labels`() {
        assertEquals(listOf(96, 160, 256, null, null), AudioQuality.entries.map { it.maxBitrateKbps })
        assertEquals(listOf("Low", "Medium", "High", "Pro", "Studio Pro"), AudioQuality.entries.map { it.label })
        assertEquals("High", AudioQuality.PRO.youTubeLabel)
        assertEquals("High", AudioQuality.STUDIO_PRO.youTubeLabel)
        assertEquals("Medium", AudioQuality.MEDIUM.youTubeLabel)
    }
}

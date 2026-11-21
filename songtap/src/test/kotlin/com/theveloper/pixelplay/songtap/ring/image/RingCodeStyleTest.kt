package com.theveloper.pixelplay.songtap.ring.image

import com.theveloper.pixelplay.songtap.SplitMix64
import com.theveloper.pixelplay.songtap.ring.RingCodeLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class RingCodeStyleTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `contrast ratio follows WCAG`() {
        assertEquals(21.0, RingCodeStyle.contrastRatio(black, white), 0.001)
        assertEquals(21.0, RingCodeStyle.contrastRatio(white, black), 0.001)
        assertEquals(1.0, RingCodeStyle.contrastRatio(0xFF336699.toInt(), 0xFF336699.toInt()), 1e-9)
        // WCAG's own example: #777777 on white is 4.48:1
        assertEquals(4.48, RingCodeStyle.contrastRatio(0xFF777777.toInt(), white), 0.01)
    }

    @Test
    fun `tone is CIE L star`() {
        assertEquals(0.0, RingCodeStyle.toneOf(black), 0.01)
        assertEquals(100.0, RingCodeStyle.toneOf(white), 0.01)
        assertEquals(53.59, RingCodeStyle.toneOf(0xFF808080.toInt()), 0.05)
    }

    @Test
    fun `a dark device colour is kept as it is`() {
        val device = 0xFF1F3A8A.toInt()
        val p = RingCodeStyle.palette(device)
        assertFalse(p.clamped)
        assertEquals(device, p.dot)
        assertTrue(p.contrast >= RingCodeStyle.MIN_CONTRAST)
    }

    @Test
    fun `a pale device colour is darkened until it passes seven to one`() {
        for (device in listOf(0xFFFFFF00.toInt(), 0xFFFFE08A.toInt(), 0xFF9FE8FF.toInt(), white, 0xFFC8F0C8.toInt())) {
            val p = RingCodeStyle.palette(device)
            assertTrue(p.clamped, "pale colour ${device.toUInt().toString(16)} is clamped")
            assertTrue(p.contrast >= RingCodeStyle.MIN_CONTRAST, "contrast ${p.contrast}")
            assertTrue(p.dotTone >= 15.0, "tone ${p.dotTone}")
        }
    }

    @Test
    fun `every device colour gets a tone 95 plate and dots at least 7 to 1`() {
        val rng = SplitMix64(5)
        var clamped = 0
        var worstContrast = 99.0
        for (i in 0 until 6000) {
            val device = (0xFF shl 24) or rng.nextInt(1 shl 24)
            val p = RingCodeStyle.palette(device)
            if (p.clamped) clamped++
            worstContrast = minOf(worstContrast, p.contrast)
            assertTrue(p.contrast >= RingCodeStyle.MIN_CONTRAST, "device ${device.toUInt().toString(16)} -> contrast ${p.contrast}")
            assertTrue(abs(p.plateTone - RingCodeStyle.PLATE_TONE) <= 1.0, "plate tone ${p.plateTone}")
            assertEquals(RingCodeLayout.MARKER_ARGB, p.marker)
            assertEquals(0xFF, (p.dot ushr 24) and 0xFF)
            assertEquals(0xFF, (p.plate ushr 24) and 0xFF)
        }
        println("palette: 6000 random device colours, $clamped darkened, worst contrast ${"%.2f".format(worstContrast)}")
    }

    @Test
    fun `the plate keeps the hue of the device colour`() {
        val device = 0xFF2E7D32.toInt() // green
        val plate = RingCodeStyle.palette(device).plate
        val g = (plate shr 8) and 0xFF
        val r = (plate shr 16) and 0xFF
        val b = plate and 0xFF
        assertTrue(g >= r && g >= b, "green plate leans green: $r $g $b")
    }

    @Test
    fun `a transparent device colour is read as opaque`() {
        val a = RingCodeStyle.palette(0x001F3A8A)
        val b = RingCodeStyle.palette(0xFF1F3A8A.toInt())
        assertEquals(b.dot, a.dot)
        assertEquals(b.plate, a.plate)
    }

    @Test
    fun `the dot radii keep big at twice small and the start dot is orange`() {
        assertEquals(RingCodeStyle.BIG_TO_SMALL_RADIUS, RingCodeStyle.bigRadius().toDouble() / RingCodeStyle.smallRadius(), 1e-9)
        val m = RingCodeLayout.MARKER_ARGB
        val r = (m shr 16) and 0xFF
        val g = (m shr 8) and 0xFF
        val b = m and 0xFF
        assertTrue(r > 180 && g in 40..120 && b < 60, "orange: $r $g $b")
        assertTrue(RingCodeStyle.contrastRatio(m, RingCodeStyle.palette(white).plate) >= 3.0)
    }
}

package com.theveloper.pixelplay.data.preferences

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AccentColorTest {

    @Test
    fun `normalize accepts any case and an optional hash`() {
        assertEquals("#FF453A", AccentColor.normalize("#ff453a"))
        assertEquals("#FF453A", AccentColor.normalize("FF453A"))
        assertEquals("#FF453A", AccentColor.normalize("  #Ff453A \n"))
    }

    @Test
    fun `normalize turns anything that is not a colour into the default`() {
        for (junk in listOf(null, "", "#", "#12345", "#1234567", "#GG0000", "##FF453A", "red", "#FF 453")) {
            assertEquals(AccentColor.DEFAULT, AccentColor.normalize(junk), "for $junk")
            assertNull(AccentColor.seedOrNull(junk), "for $junk")
        }
    }

    @Test
    fun `seeds are opaque and hex keeps six upper case digits`() {
        assertEquals(0xFFFF453A.toInt(), AccentColor.seedOrNull("#FF453A")!!)
        assertEquals(0xFF000000.toInt(), AccentColor.seedOrNull("#000000")!!)
        assertEquals("#FF453A", AccentColor.toHex(0xFFFF453A.toInt()))
        assertEquals("#00C7BE", AccentColor.toHex(0x0000C7BE))
        assertEquals("#000000", AccentColor.toHex(0xFF000000.toInt()))
    }

    @Test
    fun `hand-built hex matches the formatted reference and round trips`() {
        val random = java.util.Random(0x5EED)
        val samples = listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, 0x00ABCDEF, 0x7F102030) +
            List(2_000) { random.nextInt() }
        for (argb in samples) {
            val expected = String.format(java.util.Locale.ROOT, "#%06X", argb and 0xFFFFFF)
            assertEquals(expected, AccentColor.toHex(argb), "for 0x${Integer.toHexString(argb)}")
            assertEquals(argb or 0xFF000000.toInt(), AccentColor.seedOrNull(AccentColor.toHex(argb))!!)
        }
    }

    @Test
    fun `presets are the iOS seeds, unique, and round trip`() {
        val expected = mapOf(
            AccentPreset.BLUE to "#0A84FF", AccentPreset.INDIGO to "#5E5CE6", AccentPreset.PURPLE to "#BF5AF2",
            AccentPreset.PINK to "#FF2D55", AccentPreset.RED to "#FF453A", AccentPreset.ORANGE to "#FF9F0A",
            AccentPreset.YELLOW to "#FFD60A", AccentPreset.GREEN to "#34C759", AccentPreset.MINT to "#00C7BE",
            AccentPreset.GRAPHITE to "#8E8E93"
        )
        val coloured = AccentPreset.entries.filter { it != AccentPreset.DYNAMIC }
        assertEquals(expected.keys.toList(), coloured)
        assertEquals(10, coloured.map { it.hex }.toSet().size)
        for (preset in coloured) {
            assertEquals(expected.getValue(preset), preset.hex)
            assertEquals(preset.hex, AccentColor.normalize(preset.hex))
            assertEquals(preset, AccentColor.presetFor(preset.hex))
            // Lower case (e.g. from an iOS backup written by hand) still names the preset.
            assertEquals(preset, AccentColor.presetFor(preset.hex.lowercase()))
            assertFalse(AccentColor.isCustom(preset.hex))
        }
        assertEquals("", AccentPreset.DYNAMIC.hex)
        assertEquals(AccentPreset.DYNAMIC, AccentColor.presetFor(""))
        assertEquals(AccentPreset.DYNAMIC, AccentColor.presetFor("not a colour"))
        assertNull(AccentColor.presetFor("#6C4FF5"))
        assertTrue(AccentColor.isCustom("#6C4FF5"))
    }

    @Test
    fun `hsv round trips every preset within one step per channel`() {
        val colours = AccentPreset.entries.mapNotNull { AccentColor.seedOrNull(it.hex) } +
            listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF6C4FF5.toInt(), 0xFF123456.toInt())
        for (argb in colours) {
            val hsv = AccentColor.argbToHsv(argb)
            assertTrue(hsv[0] in 0f..360f && hsv[0] < 360f, "hue of ${AccentColor.toHex(argb)}")
            assertTrue(hsv[1] in 0f..1f && hsv[2] in 0f..1f)
            val back = AccentColor.hsvToArgb(hsv[0], hsv[1], hsv[2])
            for (shift in listOf(16, 8, 0)) {
                val delta = abs(((argb shr shift) and 0xFF) - ((back shr shift) and 0xFF))
                assertTrue(delta <= 1, "${AccentColor.toHex(argb)} -> ${AccentColor.toHex(back)}")
            }
            assertEquals(0xFF, (back ushr 24) and 0xFF)
        }
    }

    @Test
    fun `hsv to argb matches the primaries and clamps out of range input`() {
        assertEquals(0xFFFF0000.toInt(), AccentColor.hsvToArgb(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), AccentColor.hsvToArgb(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), AccentColor.hsvToArgb(240f, 1f, 1f))
        assertEquals(0xFFFF0000.toInt(), AccentColor.hsvToArgb(360f, 1f, 1f))
        assertEquals(0xFFFFFFFF.toInt(), AccentColor.hsvToArgb(42f, 0f, 2f))
        assertEquals(0xFF000000.toInt(), AccentColor.hsvToArgb(-30f, 1.5f, -1f))
    }

    @Test
    fun `check marks are white on dark swatches and black on light ones, as on iOS`() {
        for (preset in listOf(AccentPreset.BLUE, AccentPreset.RED, AccentPreset.PINK, AccentPreset.PURPLE, AccentPreset.GRAPHITE)) {
            assertFalse(AccentColor.prefersDarkContent(AccentColor.seedOrNull(preset.hex)!!), preset.name)
        }
        for (preset in listOf(AccentPreset.ORANGE, AccentPreset.YELLOW, AccentPreset.GREEN, AccentPreset.MINT)) {
            assertTrue(AccentColor.prefersDarkContent(AccentColor.seedOrNull(preset.hex)!!), preset.name)
        }
    }
}

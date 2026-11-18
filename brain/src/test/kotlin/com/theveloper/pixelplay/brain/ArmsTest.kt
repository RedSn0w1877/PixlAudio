package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArmsTest {
    @Test
    fun `arm codes are pinned and round-trip through their names`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            listOf(Arm.FAV, Arm.DEEP, Arm.BACK, Arm.RADIO, Arm.SIM, Arm.ARTIST, Arm.NEW, Arm.EDGE, Arm.ASK, Arm.NONE))
        for (code in 0..9) assertEquals(code, Arm.codeOf(Arm.nameOf(code)))
        assertEquals("RADIO", Arm.nameOf(Arm.RADIO))
        assertEquals(Arm.NONE, Arm.codeOf(null))
        assertEquals(Arm.NONE, Arm.codeOf("nonsense"))
        assertEquals("NONE", Arm.nameOf(42))
        assertTrue(Arm.isNovel(Arm.RADIO))
        assertFalse(Arm.isNovel(Arm.FAV))
        assertFalse(Arm.isNovel(Arm.DEEP))
    }

    @Test
    fun `surface codes are pinned and round-trip through their names`() {
        assertEquals(5, Surface.AUTOPLAY)
        assertEquals("autoplay", Surface.nameOf(Surface.AUTOPLAY))
        for (code in 0..14) assertEquals(code, Surface.codeOf(Surface.nameOf(code)))
        assertEquals(Surface.UNKNOWN, Surface.codeOf(null))
        assertEquals(Surface.UNKNOWN, Surface.codeOf("nope"))
    }
}

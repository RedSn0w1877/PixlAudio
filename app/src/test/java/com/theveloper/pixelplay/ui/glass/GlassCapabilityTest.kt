package com.theveloper.pixelplay.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassCapabilityTest {

    @Test
    fun api30_hasNoGlass() {
        assertEquals(GlassCapability.None, GlassCapability.forSdk(30))
        assertFalse(GlassCapability.None.hasBlur)
        assertFalse(GlassCapability.None.hasLens)
    }

    @Test
    fun api31and32_areFrosted() {
        assertEquals(GlassCapability.Frosted, GlassCapability.forSdk(31))
        assertEquals(GlassCapability.Frosted, GlassCapability.forSdk(32))
        assertTrue(GlassCapability.Frosted.hasBlur)
        assertFalse(GlassCapability.Frosted.hasLens)
    }

    @Test
    fun api33andUp_areFull() {
        assertEquals(GlassCapability.Full, GlassCapability.forSdk(33))
        assertEquals(GlassCapability.Full, GlassCapability.forSdk(36))
        assertTrue(GlassCapability.Full.hasBlur)
        assertTrue(GlassCapability.Full.hasLens)
    }

    @Test
    fun belowMinSdk_isNone() {
        assertEquals(GlassCapability.None, GlassCapability.forSdk(0))
        assertEquals(GlassCapability.None, GlassCapability.forSdk(29))
    }
}

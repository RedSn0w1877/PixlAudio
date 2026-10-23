package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.C
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WakeModeTest {

    @Test
    fun `a spotify stream holds the wifi lock`() {
        assertEquals(C.WAKE_MODE_NETWORK, wakeModeForScheme("spotify"))
        assertEquals(C.WAKE_MODE_NETWORK, wakeModeForScheme("SPOTIFY"))
    }

    @Test
    fun `plain http streams hold the wifi lock`() {
        assertEquals(C.WAKE_MODE_NETWORK, wakeModeForScheme("https"))
        assertEquals(C.WAKE_MODE_NETWORK, wakeModeForScheme("http"))
    }

    @Test
    fun `local media only needs the cpu`() {
        assertEquals(C.WAKE_MODE_LOCAL, wakeModeForScheme("file"))
        assertEquals(C.WAKE_MODE_LOCAL, wakeModeForScheme("content"))
        assertEquals(C.WAKE_MODE_LOCAL, wakeModeForScheme(null))
    }
}

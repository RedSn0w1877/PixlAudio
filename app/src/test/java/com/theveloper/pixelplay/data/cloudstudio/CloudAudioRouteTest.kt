package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.cloudstudio.LiveCloudAudioPreparer.Companion.aacObjectType
import com.theveloper.pixelplay.data.cloudstudio.LiveCloudAudioPreparer.Companion.passthroughFrames
import com.theveloper.pixelplay.data.cloudstudio.LiveCloudAudioPreparer.Companion.route
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which songs go up as they are (AAC-LC) and which are decoded to FLAC, and the AAC upload's frame count. */
class CloudAudioRouteTest {
    private val aac = "audio/mp4a-latm"

    @Test fun `only a single-track AAC-LC song at 32 kHz or more goes up as it is`() {
        assertEquals(LiveCloudAudioPreparer.Route.COPY, route(1, 0, aac, 2, 44_100))
        assertEquals(LiveCloudAudioPreparer.Route.COPY, route(1, 0, aac, 2, 32_000))
        // HE-AAC (itag 139), HE-AAC v2, low rates, muxed video (itag 18), two audio tracks, other codecs: decoded.
        assertEquals(LiveCloudAudioPreparer.Route.DECODE, route(1, 0, aac, 5, 44_100))
        assertEquals(LiveCloudAudioPreparer.Route.DECODE, route(1, 0, aac, 29, 44_100))
        assertEquals(LiveCloudAudioPreparer.Route.DECODE, route(1, 0, aac, 2, 22_050))
        assertEquals(LiveCloudAudioPreparer.Route.DECODE, route(1, 1, aac, 2, 44_100))
        assertEquals(LiveCloudAudioPreparer.Route.DECODE, route(2, 0, aac, 2, 44_100))
        assertEquals(LiveCloudAudioPreparer.Route.DECODE, route(1, 0, aac, null, 44_100))
        assertEquals(LiveCloudAudioPreparer.Route.DECODE, route(1, 0, "audio/mpeg", null, 44_100))
        assertEquals(LiveCloudAudioPreparer.Route.DECODE, route(1, 0, "audio/flac", null, 96_000))
        assertEquals(LiveCloudAudioPreparer.Route.DECODE, route(1, 0, aac, 2, null))
    }

    @Test fun `the object type is read from the AudioSpecificConfig`() {
        // AAC-LC 44.1 kHz stereo: 00010 0100 0010 000 → 0x12 0x10.
        assertEquals(2, aacObjectType(byteArrayOf(0x12, 0x10)))
        // Explicit HE-AAC (SBR) signalling: object type 5 first.
        assertEquals(5, aacObjectType(byteArrayOf(0x2B, 0x92.toByte(), 0x08, 0x00)))
        // Escape value 31 + 6 bits (e.g. 42 = USAC: 31, then 10).
        assertEquals(42, aacObjectType(byteArrayOf(0xF9.toByte(), 0x40)))
        assertNull(aacObjectType(byteArrayOf()))
        assertNull(aacObjectType(byteArrayOf(0xF8.toByte())))
    }

    @Test fun `the AAC upload's frame count leaves out priming and padding exactly once`() {
        val units = 10_336L // ≈ 240 s at 44.1 kHz
        val raw = units * 1024
        // A decoder that keeps the priming: the edit list's delay and padding come off (as ffmpeg trims them).
        assertEquals(raw - 2112 - 960, passthroughFrames(raw, units, 2112, 960))
        // A decoder that already trimmed them: taken as it is.
        assertEquals(raw - 2112 - 960, passthroughFrames(raw - 2112 - 960, units, 2112, 960))
        // No edit list: nothing removed on either side.
        assertEquals(raw, passthroughFrames(raw, units, 0, 0))
        assertEquals(raw - 7, passthroughFrames(raw - 7, 0, 2112, 0))
    }
}

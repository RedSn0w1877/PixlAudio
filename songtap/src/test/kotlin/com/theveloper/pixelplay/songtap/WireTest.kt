package com.theveloper.pixelplay.songtap

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WireTest {

    @Test
    fun `info round trips and clamps`() {
        assertEquals(150L, SongTapWire.decodeInfo(SongTapWire.encodeInfo(150L)))
        assertEquals(0L, SongTapWire.decodeInfo(SongTapWire.encodeInfo(0L)))
        assertEquals(-1L, SongTapWire.decodeInfo(SongTapWire.encodeInfo(-1L)))
        assertEquals(-1L, SongTapWire.decodeInfo(SongTapWire.encodeInfo(SongTapWire.MAX_REPORTED_AGE_MS + 1)))
        assertEquals(5, SongTapWire.encodeInfo(10L).size)
    }

    @Test
    fun `info rejects the wrong size and version`() {
        assertNull(SongTapWire.decodeInfo(ByteArray(4)))
        assertNull(SongTapWire.decodeInfo(ByteArray(6)))
        assertNull(SongTapWire.decodeInfo(byteArrayOf(2, 0, 0, 0, 0)))
    }

    @Test
    fun `control messages round trip`() {
        val challenge = ByteArray(16) { it.toByte() }
        val hello = SongTapWire.decodeControl(SongTapWire.encodeHello(challenge)!!)
        assertTrue(hello is SongTapWire.ControlMessage.Hello)
        assertArrayEquals(challenge, (hello as SongTapWire.ControlMessage.Hello).challenge)
        assertEquals(SongTapWire.ControlMessage.Ack, SongTapWire.decodeControl(SongTapWire.encodeAck()))
        assertEquals(SongTapWire.ControlMessage.Decline, SongTapWire.decodeControl(SongTapWire.encodeDecline()))
        assertEquals(SongTapWire.ControlMessage.Tap(250L), SongTapWire.decodeControl(SongTapWire.encodeTap(250L)))
        assertEquals(SongTapWire.ControlMessage.Tap(-1L), SongTapWire.decodeControl(SongTapWire.encodeTap(-7L)))
    }

    @Test
    fun `hello needs exactly 16 bytes of challenge`() {
        assertNull(SongTapWire.encodeHello(ByteArray(15)))
        assertNull(SongTapWire.encodeHello(ByteArray(17)))
        assertNull(SongTapWire.decodeControl(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `anything else is not a control message`() {
        assertNull(SongTapWire.decodeControl(ByteArray(0)))
        assertNull(SongTapWire.decodeControl(byteArrayOf(0x7F)))
        assertNull(SongTapWire.decodeControl(byteArrayOf(2, 0)))
        assertNull(SongTapWire.decodeControl(byteArrayOf(4, 0, 0)))
    }

    @Test
    fun `go is one byte and nothing else`() {
        assertTrue(SongTapWire.isGo(SongTapWire.encodeGo()))
        assertTrue(!SongTapWire.isGo(byteArrayOf(2)))
        assertTrue(!SongTapWire.isGo(byteArrayOf(1, 1)))
    }

    @Test
    fun `the two services map to the two roles and differ only in the last digit`() {
        assertEquals(Role.SEND, SongTapWire.roleOfService(SongTapWire.SERVICE_SEND))
        assertEquals(Role.RECEIVE, SongTapWire.roleOfService(SongTapWire.SERVICE_RECEIVE))
        assertNull(SongTapWire.roleOfService(SongTapWire.CHAR_INFO))
        assertEquals(SongTapWire.SERVICE_SEND, SongTapWire.serviceOf(Role.SEND))
        assertEquals(SongTapWire.SERVICE_SEND.dropLast(1), SongTapWire.SERVICE_RECEIVE.dropLast(1))
    }

    @Test
    fun `every uuid is a lower case 128 bit uuid and they are all different`() {
        val all = listOf(
            SongTapWire.SERVICE_SEND, SongTapWire.SERVICE_RECEIVE, SongTapWire.CHAR_INFO,
            SongTapWire.CHAR_OFFER, SongTapWire.CHAR_CONTROL, SongTapWire.CHAR_EVENT,
        )
        val shape = listOf(8, 4, 4, 4, 12)
        for (u in all) {
            val parts = u.split('-')
            assertEquals(shape, parts.map { it.length }, u)
            assertTrue(u.all { it == '-' || it in '0'..'9' || it in 'a'..'f' }, u)
        }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `the allocation free frame equals the allocating one at every millisecond`() {
        val buffer = BeamFrameBuffer()
        for (role in Role.entries) for (reduced in listOf(false, true)) {
            var t = -100L
            while (t <= SongTapTimeline.totalMs(role, reduced) + 100L) {
                val expected = SongTapTimeline.frame(role, t, reduced)
                val got = SongTapTimeline.frameInto(role, t, reduced, buffer).toFrame()
                assertEquals(expected, got, "$role $reduced $t")
                t += 7L
            }
        }
        assertNotNull(buffer)
    }

    @Test
    fun `a signed offer frame for a 400 byte payload does not fit one attribute`() {
        // The transport must drop the art before it signs; the core says where the line is.
        val over = SongTapSigning.encodeFrame(SignedOffer(ByteArray(400) { 1 }, 1L, ByteArray(65), ByteArray(64)))
        assertNull(over)
        val fits = SongTapSigning.encodeFrame(SignedOffer(ByteArray(SongTapSigning.MAX_FRAME_BYTES - 142) { 1 }, 1L, ByteArray(65), ByteArray(64)))
        assertNotNull(fits)
    }
}

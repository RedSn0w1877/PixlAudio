package com.theveloper.pixelplay.songtap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PeerTapTest {

    private fun judge(dbm: Int = -40, followed: Boolean = true, yes: Boolean = true, now: Long = 10_000L, last: Long = -1L) =
        SongTapPeerTap.judge(dbm, followed, yes, now, last)

    @Test
    fun `a peer tap is accepted when this phone is near, follows that phone, the sender said yes and nothing was taken lately`() {
        assertEquals(PeerTapVerdict.ACCEPT, judge())
        assertEquals(PeerTapVerdict.ACCEPT, judge(dbm = SongTapTuning.NEAR_ENTER_DBM))
        assertEquals(PeerTapVerdict.ACCEPT, judge(dbm = -33))
        // Resting 20 to 30 cm apart (-60) is inside the gate: the peer felt a tap, this phone is close enough to believe it.
        assertEquals(PeerTapVerdict.ACCEPT, judge(dbm = -60))
    }

    @Test
    fun `ignored when this phone's own normalised signal is below the tap near gate`() {
        assertEquals(PeerTapVerdict.TOO_WEAK, judge(dbm = SongTapTuning.NEAR_ENTER_DBM - 1))
        assertEquals(PeerTapVerdict.TOO_WEAK, judge(dbm = -90))
    }

    @Test
    fun `ignored when the peer is not the phone being followed`() {
        assertEquals(PeerTapVerdict.NOT_FOLLOWED, judge(followed = false))
        // Not followed wins over everything else: a stranger's message tells nothing about this phone.
        assertEquals(PeerTapVerdict.NOT_FOLLOWED, judge(dbm = -95, followed = false, yes = false, last = 9_999L))
    }

    @Test
    fun `ignored when the sender has not said yes`() {
        assertEquals(PeerTapVerdict.NO_YES, judge(yes = false))
        assertEquals(PeerTapVerdict.NO_YES, judge(dbm = -95, yes = false))
    }

    @Test
    fun `rate limited to one accepted tap per 1200 ms, counted from this phone's own last fire too`() {
        assertEquals(PeerTapVerdict.TOO_SOON, judge(now = 10_000L, last = 9_999L))
        assertEquals(PeerTapVerdict.TOO_SOON, judge(now = 10_000L, last = 8_801L))
        assertEquals(PeerTapVerdict.ACCEPT, judge(now = 10_000L, last = 8_800L))
        assertEquals(PeerTapVerdict.ACCEPT, judge(now = 10_000L, last = -1L))
        assertEquals(1_200L, SongTapPeerTap.MIN_GAP_MS)
    }

    @Test
    fun `the verdicts have stable codes`() {
        assertEquals(listOf("accept", "too_weak", "not_followed", "no_yes", "too_soon"), PeerTapVerdict.entries.map { it.code })
        assertFalse(PeerTapVerdict.entries.map { it.code }.any { it.isEmpty() })
        assertTrue(PeerTapVerdict.entries.size == 5)
    }

    @Test
    fun `the tap event is the single byte 04 on Event and the control tap keeps its five bytes`() {
        assertEquals(4, SongTapWire.EVENT_TAP)
        assertEquals(listOf(0x04), SongTapWire.encodeTapEvent().map { it.toInt() })
        assertTrue(SongTapWire.isTapEvent(byteArrayOf(4)))
        assertFalse(SongTapWire.isTapEvent(byteArrayOf(3)))
        assertFalse(SongTapWire.isTapEvent(byteArrayOf(4, 0)))
        assertFalse(SongTapWire.isTapEvent(byteArrayOf()))
        assertEquals(5, SongTapWire.encodeTap(100).size)
        assertEquals(0x04, SongTapWire.encodeTap(100)[0].toInt())
        // The other events are not taken for a tap.
        assertFalse(SongTapWire.isTapEvent(SongTapWire.encodeOfferReady()))
        assertFalse(SongTapWire.isTapEvent(SongTapWire.encodeOfferWithdrawn()))
        assertFalse(SongTapWire.isOfferReady(SongTapWire.encodeTapEvent()))
        assertFalse(SongTapWire.isOfferWithdrawn(SongTapWire.encodeTapEvent()))
    }
}

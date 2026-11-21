package com.theveloper.pixelplay.songtap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiscTest {

    @Test
    fun `rate limits`() {
        var l = RateLimits()
        assertEquals(LimitVerdict.ALLOWED, l.admit(0, "a", 1))
        l = l.accepted(0, "a")
        assertEquals(LimitVerdict.TOO_SOON, l.admit(2_999, "b", 1))
        assertEquals(LimitVerdict.DUPLICATE, l.admit(10_000, "a", 1))
        assertEquals(LimitVerdict.ALLOWED, l.admit(3_000, "b", 1))
        assertEquals(LimitVerdict.ALLOWED, l.admit(60_000, "a", 1))
    }

    @Test
    fun `ten a minute and thirty an hour`() {
        var l = RateLimits()
        for (i in 0 until 10) l = l.accepted(i * 5_000L, "v$i")
        assertEquals(LimitVerdict.MINUTE_LIMIT, l.admit(55_000, "x", 1))
        assertEquals(LimitVerdict.ALLOWED, l.admit(60_001 + 45_000, "x", 1))
        var h = RateLimits()
        for (i in 0 until 30) h = h.accepted(i * 100_000L, "h$i")
        assertEquals(LimitVerdict.HOUR_LIMIT, h.admit(3_000_000L + 10_000L, "x", 1))
        assertEquals(LimitVerdict.ALLOWED, h.admit(3_700_000L, "x", 1))
    }

    @Test
    fun `an offer that ended in no song still counts towards the gap and the minute`() {
        var l = RateLimits()
        for (i in 0 until 10) l = l.attempted(i * 5_000L)
        assertEquals(LimitVerdict.TOO_SOON, RateLimits().attempted(0).admit(2_999, "x", 1))
        assertEquals(LimitVerdict.MINUTE_LIMIT, l.admit(55_000, "x", 1))
        // The song is not remembered: the same one may be tried again once the gap has passed.
        assertEquals(LimitVerdict.ALLOWED, RateLimits().attempted(0).admit(3_000, "a", 1))
    }

    @Test
    fun `two undos mute for an hour then it lifts`() {
        var l = RateLimits().undone(0, 5)
        assertEquals(LimitVerdict.ALLOWED, l.admit(100, "a", 5))
        l = l.undone(1_000, 5)
        assertEquals(LimitVerdict.MUTED, l.admit(1_001, "a", 5))
        assertEquals(LimitVerdict.ALLOWED, l.admit(1_001, "a", 6))
        assertEquals(LimitVerdict.ALLOWED, l.admit(1_000 + 3_600_001, "a", 5))
    }

    @Test
    fun `a sender that is not known is never muted`() {
        val l = RateLimits().undone(0, 0).undone(1_000, 0).undone(2_000, 0)
        assertEquals(LimitVerdict.ALLOWED, l.admit(5_000, "a", 0))
    }

    @Test
    fun `timeline ends where it should`() {
        val send = SongTapTimeline.frame(Role.SEND, 0, false)
        assertEquals(BeamFrame(0, 1000, 0, 1000, 0, 0, 0, 0), send)
        val gone = SongTapTimeline.frame(Role.SEND, 800, false)
        assertEquals(0, gone.alpha)
        assertEquals(SongTapTimeline.OFF_TOP, gone.translateY)
        val back = SongTapTimeline.frame(Role.SEND, SongTapTimeline.SEND_TOTAL_MS, false)
        assertEquals(1000, back.scale); assertEquals(1000, back.alpha); assertEquals(0, back.translateY)
        val rest = SongTapTimeline.frame(Role.RECEIVE, 3_000, false)
        assertEquals(BeamFrame(0, 1000, 0, 1000, 0, 1000, 1000, 1000), rest)
        val away = SongTapTimeline.frame(Role.RECEIVE, SongTapTimeline.RECEIVE_TOTAL_MS, false)
        assertEquals(0, away.alpha)
    }

    @Test
    fun `receiver card overshoots a little and never leaves the permille range`() {
        var peak = 0
        for (role in Role.entries) for (reduced in listOf(false, true)) for (t in -100L..8_000L step 10L) {
            val f = SongTapTimeline.frame(role, t, reduced)
            for (v in listOf(f.alpha, f.cardAlpha, f.textAlpha, f.undoAlpha, f.shadow)) assertTrue(v in 0..1000, "$role $t ${f.describe()}")
            assertTrue(f.glow in 0..500, "$role $t")
            assertTrue(f.scale in 300..1200, "$role $t ${f.describe()}")
            assertTrue(f.translateY in SongTapTimeline.OFF_TOP..0, "$role $t ${f.describe()}")
            if (role == Role.RECEIVE && !reduced) peak = maxOf(peak, f.scale)
        }
        assertTrue(peak in 1010..1150, "peak=$peak")
    }

    @Test
    fun `reduced motion is a short cross fade with no flight`() {
        for (t in 0L..400L step 10L) {
            val f = SongTapTimeline.frame(Role.RECEIVE, t, true)
            assertEquals(1000, f.scale); assertEquals(0, f.translateY); assertEquals(0, f.glow)
        }
        assertEquals(1000, SongTapTimeline.frame(Role.RECEIVE, 150, true).alpha)
        assertEquals(SongTapTimeline.REDUCED_FADE_MS, SongTapTimeline.totalMs(Role.SEND, true))
        assertEquals(listOf("0:success"), SongTapHaptics.cues(Role.SEND, true).map { it.describe() })
    }

    @Test
    fun `the beam itself is 400 to 500 ms`() {
        assertTrue(SongTapTimeline.FLY_MS in 400..500)
        assertTrue(SongTapTimeline.RECEIVE_ENTER_MS in 400..500)
        assertEquals(7_300L, SongTapTimeline.RECEIVE_TOTAL_MS)
    }

    @Test
    fun `easing is monotone where it should be`() {
        var last = -1
        for (x in 0..1000) { val y = SongTapTimeline.easeOutCubic(x); assertTrue(y >= last); last = y }
        assertEquals(0, SongTapTimeline.easeOutBack(0)); assertEquals(1000, SongTapTimeline.easeOutBack(1000))
        assertEquals(1000, SongTapTimeline.smooth(1000)); assertEquals(0, SongTapTimeline.easeInCubic(0))
    }

    @Test
    fun `handshake keeps only the impulse helpers and no start delay`() {
        assertEquals(0L, SongTapHandshake.GO_DELAY_MS)
        assertEquals(100L, SongTapHandshake.impulseAgeMs(1000L, 900L))
        assertEquals(-1L, SongTapHandshake.impulseAgeMs(1000L, 1001L))
        assertEquals(10_000L - 100L - 20L, SongTapHandshake.peerImpulseLocalMs(10_000L, 100L, 40L))
        assertEquals(-1L, SongTapHandshake.peerImpulseLocalMs(10_000L, -1L, 40L))
    }

    private fun pattern(n: Int, seed: Int) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    @Test
    fun `signing bytes are payload challenge and timestamp`() {
        val payload = byteArrayOf(1, 2, 3)
        val challenge = pattern(16, 1)
        val bytes = SongTapSigning.signingBytes(payload, challenge, 0x0102030405060708L)!!
        assertEquals(3 + 16 + 8, bytes.size)
        assertTrue(bytes.copyOfRange(0, 3).contentEquals(payload))
        assertTrue(bytes.copyOfRange(3, 19).contentEquals(challenge))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6, 7, 8), bytes.copyOfRange(19, 27).toList())
        assertNull(SongTapSigning.signingBytes(payload, pattern(15, 1), 0L))
    }

    @Test
    fun `a frame round trips and hostile frames are refused`() {
        val payload = SongTapPayloads.encode(
            SongTapPayload(1, 5L, "dQw4w9WgXcQ", null, null, "t", "a", 0, null)
        )!!
        val offer = SignedOffer(payload, 42L, pattern(65, 3), pattern(64, 5))
        val frame = SongTapSigning.encodeFrame(offer)!!
        assertTrue(frame.size <= SongTapSigning.MAX_FRAME_BYTES)
        val back = SongTapSigning.decodeFrame(frame)!!
        assertTrue(back.payload.contentEquals(payload) && back.publicKey.contentEquals(offer.publicKey) && back.signature.contentEquals(offer.signature))
        assertEquals(42L, back.timestampMs)
        assertNull(SongTapSigning.decodeFrame(frame.copyOf(frame.size - 1)))
        assertNull(SongTapSigning.decodeFrame(frame + byteArrayOf(0)))
        assertNull(SongTapSigning.decodeFrame(byteArrayOf(2) + frame.copyOfRange(1, frame.size)))
        assertNull(SongTapSigning.decodeFrame(ByteArray(0)))
        assertNull(SongTapSigning.decodeFrame(ByteArray(513)))
        assertNull(SongTapSigning.encodeFrame(SignedOffer(payload, 0L, pattern(64, 1), pattern(64, 1))))
    }

    @Test
    fun `check follows the injected verifier and refuses stale or foreign payloads`() {
        val payload = SongTapPayloads.encode(SongTapPayload(1, 5L, "dQw4w9WgXcQ", null, null, "t", "a", 0, null))!!
        val challenge = pattern(16, 9)
        val offer = SignedOffer(payload, 100_000L, pattern(65, 3), pattern(64, 5))
        val yes = object : SongTapSignatureVerifier { override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray) = true }
        var seen: ByteArray? = null
        val spy = object : SongTapSignatureVerifier {
            override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean { seen = message; return false }
        }
        assertEquals(OfferCheck.OK, SongTapSigning.check(offer, challenge, 100_500L, yes))
        assertEquals(OfferCheck.BAD_SIGNATURE, SongTapSigning.check(offer, challenge, 100_500L, spy))
        assertTrue(seen!!.contentEquals(SongTapSigning.signingBytes(payload, challenge, 100_000L)!!))
        assertEquals(OfferCheck.STALE, SongTapSigning.check(offer, challenge, 100_000L + 30_001L, yes))
        assertEquals(OfferCheck.STALE, SongTapSigning.check(offer, challenge, 100_000L - 30_001L, yes))
        assertEquals(OfferCheck.BAD_PAYLOAD, SongTapSigning.check(SignedOffer(byteArrayOf(1, 2), 100_000L, offer.publicKey, offer.signature), challenge, 100_000L, yes))
        assertEquals(OfferCheck.BAD_FRAME, SongTapSigning.check(offer, pattern(3, 1), 100_000L, yes))
        assertNotNull(SongTapSigning.senderKey(offer.publicKey))
        assertFalse(SongTapSigning.senderKey(pattern(65, 3)) == SongTapSigning.senderKey(pattern(65, 4)))
    }
}

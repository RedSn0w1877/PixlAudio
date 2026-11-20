package com.theveloper.pixelplay.data.songtap

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.songtap.OfferCheck
import com.theveloper.pixelplay.songtap.SignedOffer
import com.theveloper.pixelplay.songtap.SongTapPayload
import com.theveloper.pixelplay.songtap.SongTapPayloads
import com.theveloper.pixelplay.songtap.SongTapSigning
import org.junit.jupiter.api.Test

class SongTapCryptoTest {

    private val payload = SongTapPayloads.encode(SongTapPayload(1, 100_000L, "dQw4w9WgXcQ", null, null, "t", "a", 0, null))!!
    private val challenge = ByteArray(16) { it.toByte() }

    private fun signed(signer: SongTapSigner, ts: Long = 100_000L): SignedOffer {
        val message = SongTapSigning.signingBytes(payload, challenge, ts)!!
        return SignedOffer(payload, ts, signer.publicKey, signer.sign(message))
    }

    @Test
    fun `a signature checks out and has the sizes the frame expects`() {
        val signer = SongTapSigner.create()
        val offer = signed(signer)
        assertThat(offer.publicKey.size).isEqualTo(65)
        assertThat(offer.publicKey[0].toInt()).isEqualTo(4)
        assertThat(offer.signature.size).isEqualTo(64)
        assertThat(SongTapSigning.check(offer, challenge, 100_500L, SongTapVerifier)).isEqualTo(OfferCheck.OK)
        assertThat(SongTapSigning.encodeFrame(offer)).isNotNull()
    }

    @Test
    fun `a changed payload, challenge or timestamp is refused`() {
        val signer = SongTapSigner.create()
        val offer = signed(signer)
        val otherChallenge = ByteArray(16) { (it + 1).toByte() }
        assertThat(SongTapSigning.check(offer, otherChallenge, 100_500L, SongTapVerifier)).isEqualTo(OfferCheck.BAD_SIGNATURE)
        assertThat(SongTapSigning.check(SignedOffer(offer.payload, 100_001L, offer.publicKey, offer.signature), challenge, 100_500L, SongTapVerifier))
            .isEqualTo(OfferCheck.BAD_SIGNATURE)
        val tampered = SongTapPayloads.encode(SongTapPayload(1, 100_000L, "dQw4w9WgXcR", null, null, "t", "a", 0, null))!!
        assertThat(SongTapSigning.check(SignedOffer(tampered, 100_000L, offer.publicKey, offer.signature), challenge, 100_500L, SongTapVerifier))
            .isEqualTo(OfferCheck.BAD_SIGNATURE)
    }

    @Test
    fun `another phone's key does not verify and malformed keys never throw`() {
        val a = signed(SongTapSigner.create())
        val b = SongTapSigner.create()
        assertThat(SongTapSigning.check(SignedOffer(a.payload, a.timestampMs, b.publicKey, a.signature), challenge, 100_500L, SongTapVerifier))
            .isEqualTo(OfferCheck.BAD_SIGNATURE)
        assertThat(SongTapVerifier.verify(ByteArray(65), ByteArray(3), ByteArray(64))).isFalse()
        assertThat(SongTapVerifier.verify(ByteArray(10), ByteArray(3), ByteArray(64))).isFalse()
        assertThat(SongTapVerifier.verify(a.publicKey, ByteArray(3), ByteArray(10))).isFalse()
    }

    @Test
    fun `every session has its own key`() {
        assertThat(SongTapSigner.create().publicKey).isNotEqualTo(SongTapSigner.create().publicKey)
    }
}

package com.theveloper.pixelplay.songtap

/**
 * What a signed offer is and how it is checked. The cryptography itself (ECDSA P-256) is injected by each
 * app through [SongTapSignatureVerifier]; the core only fixes the bytes that are signed and the frame
 * that carries them, so both platforms sign and verify the same thing.
 *
 * What this proves: the payload was not changed on the way and was made for this challenge, so it cannot
 * be replayed to another receiver or later. What it does not prove: that the sender is honest. There is
 * no server and no account, so anyone can make a key; the defences against a hostile sender are the
 * strict payload rules, the host allow-list for art, the rate limits and the first-beam consent card.
 */
interface SongTapSignatureVerifier {
    /** [publicKey] is an uncompressed P-256 point (65 bytes), [signature] is r then s (64 bytes). */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

class SignedOffer(
    val payload: ByteArray,
    val timestampMs: Long,
    val publicKey: ByteArray,
    val signature: ByteArray,
)

enum class OfferCheck(val code: String) {
    OK("ok"),
    BAD_FRAME("bad_frame"),
    BAD_PAYLOAD("bad_payload"),
    STALE("stale"),
    BAD_SIGNATURE("bad_signature"),
}

object SongTapSigning {

    const val CHALLENGE_BYTES = 16
    const val PUBLIC_KEY_BYTES = 65
    const val SIGNATURE_BYTES = 64
    const val FRAME_VERSION = 1
    const val MAX_FRAME_BYTES = 512

    /** How far a signed offer's timestamp may be from the receiver's clock. */
    const val OFFER_WINDOW_MS = 30_000L

    /**
     * How far a QR code's payload timestamp may be from the receiver's clock. A code has no challenge to
     * answer (nothing travels back), so freshness is all that limits a photographed code; the sender makes
     * a new code before this runs out.
     */
    const val QR_WINDOW_MS = 300_000L

    /** True when [tsMs] is within [windowMs] of [nowMs], either side. */
    fun isFresh(tsMs: Long, nowMs: Long, windowMs: Long): Boolean {
        val skew = nowMs - tsMs
        return skew <= windowMs && -skew <= windowMs
    }

    /** `payload || challenge || timestamp`, the timestamp as 8 bytes big endian. Null when the challenge is not 16 bytes. */
    fun signingBytes(payload: ByteArray, challenge: ByteArray, timestampMs: Long): ByteArray? {
        if (challenge.size != CHALLENGE_BYTES) return null
        val out = ByteArray(payload.size + CHALLENGE_BYTES + 8)
        payload.copyInto(out, 0)
        challenge.copyInto(out, payload.size)
        putLong(out, payload.size + CHALLENGE_BYTES, timestampMs)
        return out
    }

    /** Frame: version, payload length (2 bytes), payload, timestamp (8), key length (1), key, signature length (1), signature. */
    fun encodeFrame(offer: SignedOffer): ByteArray? {
        if (offer.publicKey.size != PUBLIC_KEY_BYTES || offer.signature.size != SIGNATURE_BYTES) return null
        if (offer.payload.isEmpty() || offer.payload.size > SongTapPayloads.MAX_BYTES) return null
        val size = 1 + 2 + offer.payload.size + 8 + 1 + PUBLIC_KEY_BYTES + 1 + SIGNATURE_BYTES
        if (size > MAX_FRAME_BYTES) return null
        val out = ByteArray(size)
        var i = 0
        out[i++] = FRAME_VERSION.toByte()
        out[i++] = (offer.payload.size shr 8).toByte()
        out[i++] = offer.payload.size.toByte()
        offer.payload.copyInto(out, i); i += offer.payload.size
        putLong(out, i, offer.timestampMs); i += 8
        out[i++] = PUBLIC_KEY_BYTES.toByte()
        offer.publicKey.copyInto(out, i); i += PUBLIC_KEY_BYTES
        out[i++] = SIGNATURE_BYTES.toByte()
        offer.signature.copyInto(out, i)
        return out
    }

    fun decodeFrame(frame: ByteArray): SignedOffer? {
        if (frame.size < 4 || frame.size > MAX_FRAME_BYTES) return null
        var i = 0
        if (frame[i++].toInt() != FRAME_VERSION) return null
        val payloadLen = ((frame[i].toInt() and 0xFF) shl 8) or (frame[i + 1].toInt() and 0xFF)
        i += 2
        if (payloadLen == 0 || payloadLen > SongTapPayloads.MAX_BYTES) return null
        if (frame.size != 1 + 2 + payloadLen + 8 + 1 + PUBLIC_KEY_BYTES + 1 + SIGNATURE_BYTES) return null
        val payload = frame.copyOfRange(i, i + payloadLen); i += payloadLen
        val ts = getLong(frame, i); i += 8
        if (frame[i++].toInt() != PUBLIC_KEY_BYTES) return null
        val key = frame.copyOfRange(i, i + PUBLIC_KEY_BYTES); i += PUBLIC_KEY_BYTES
        if (frame[i++].toInt() != SIGNATURE_BYTES) return null
        val sig = frame.copyOfRange(i, i + SIGNATURE_BYTES)
        return SignedOffer(payload, ts, key, sig)
    }

    /**
     * Checks [offer] against the [challenge] this receiver handed out: the payload is a valid payload, the
     * timestamp is close to [nowMs], and the signature covers payload, challenge and timestamp. Single use of
     * the challenge is the caller's job.
     */
    fun check(offer: SignedOffer, challenge: ByteArray, nowMs: Long, verifier: SongTapSignatureVerifier): OfferCheck {
        val decoded = SongTapPayloads.decode(offer.payload)
        if (decoded !is PayloadDecode.Ok) return OfferCheck.BAD_PAYLOAD
        if (!isFresh(offer.timestampMs, nowMs, OFFER_WINDOW_MS)) return OfferCheck.STALE
        val message = signingBytes(offer.payload, challenge, offer.timestampMs) ?: return OfferCheck.BAD_FRAME
        return if (verifier.verify(offer.publicKey, message, offer.signature)) OfferCheck.OK else OfferCheck.BAD_SIGNATURE
    }

    /** A short stable number for a sender's key, used to mute one sender: the first 4 bytes of the key's tail. */
    fun senderKey(publicKey: ByteArray): Int {
        if (publicKey.size < 4) return 0
        val n = publicKey.size
        return ((publicKey[n - 4].toInt() and 0xFF) shl 24) or ((publicKey[n - 3].toInt() and 0xFF) shl 16) or
            ((publicKey[n - 2].toInt() and 0xFF) shl 8) or (publicKey[n - 1].toInt() and 0xFF)
    }

    private fun putLong(out: ByteArray, at: Int, v: Long) {
        for (k in 0 until 8) out[at + k] = (v shr (56 - 8 * k)).toByte()
    }

    private fun getLong(b: ByteArray, at: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (b[at + k].toLong() and 0xFFL)
        return v
    }
}

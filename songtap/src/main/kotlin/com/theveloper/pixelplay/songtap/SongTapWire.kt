package com.theveloper.pixelplay.songtap

/**
 * What goes over Bluetooth, fixed once so a Pixel and an iPhone speak the same thing.
 *
 * Each phone, while Song Tap is armed on screen, advertises exactly one 128-bit service UUID and scans for
 * both. The UUID says which side it is on: [SERVICE_SEND] (this phone offers the song that is playing) or
 * [SERVICE_RECEIVE] (this phone waits for one). A phone that is sending also runs a GATT server with the four
 * characteristics below; the receiving phone connects to it as the client and never serves anything, so a
 * beam always travels over one connection in one direction. Nothing sits in the advertisement but the UUID
 * (an iPhone can advertise nothing else), no device name, no identifier.
 *
 * | characteristic | properties | value |
 * |---|---|---|
 * | [CHAR_INFO] | read | [encodeInfo]: version and how long ago the sender's last jerk was |
 * | [CHAR_OFFER] | read | the signed frame ([SongTapSigning.encodeFrame]); an application error until an offer exists |
 * | [CHAR_CONTROL] | write | a [ControlMessage] from the receiver: Hello (challenge), Tap, Ack, Decline |
 * | [CHAR_NAME] | read | [encodeName]: the sender's full name (spec 5) |
 * | [CHAR_EVENT] | notify | [EVENT_OFFER_READY] when the sender has (a new) offer to read, [EVENT_OFFER_WITHDRAWN] when it has none any more; [EVENT_GO] is reserved and no longer sent (spec 5) |
 *
 * The flow (spec 5, "prefetch"): the receiver connects while the sender is inside the STRICT near gate, writes Hello
 * with a fresh 16 byte challenge, subscribes to [CHAR_EVENT], reads Name, and as soon as the sender has an offer for it
 * (the sender's listener said yes; the sender says so with [EVENT_OFFER_READY]) reads Offer: the sender signs payload,
 * challenge and time with its per-session key on that read, and the receiver checks the signature and holds the result.
 * Nothing slow is left for the tap: when a phone's detector fires, the data is in hand, so both phones start the beam
 * at once (the receiver writes Tap, and Ack after it has saved; there is no Go and no start delay).
 *
 * Advertisement (spec 5): each phone also advertises a short name, see [SongTapNames]. (Spec 4 had no name: nothing but the UUID.)
 */
object SongTapWire {

    const val VERSION = 1

    const val SERVICE_SEND = "b7f3a1c0-5d2e-4c9a-8e61-3f4d7a2b9c01"
    const val SERVICE_RECEIVE = "b7f3a1c0-5d2e-4c9a-8e61-3f4d7a2b9c02"
    const val CHAR_INFO = "b7f3a1c0-5d2e-4c9a-8e61-3f4d7a2b9c11"
    const val CHAR_OFFER = "b7f3a1c0-5d2e-4c9a-8e61-3f4d7a2b9c12"
    const val CHAR_CONTROL = "b7f3a1c0-5d2e-4c9a-8e61-3f4d7a2b9c13"
    const val CHAR_EVENT = "b7f3a1c0-5d2e-4c9a-8e61-3f4d7a2b9c14"
    const val CHAR_NAME = "b7f3a1c0-5d2e-4c9a-8e61-3f4d7a2b9c15"

    /** The company id of the manufacturer-specific block that carries the short name in an Android phone's scan response (0xFFFF: no company, as the spec reserves it for tests and internal use). */
    const val ADVERT_COMPANY_ID = 0xFFFF
    const val ADVERT_VERSION = 1

    /** The ATT application error a sender answers an Offer read with while it has nothing (yet) to give. */
    const val ERROR_NOT_READY = 0x80

    /** The MTU a receiver asks for, so that one frame (at most [SongTapSigning.MAX_FRAME_BYTES]) fits one read. */
    const val WANTED_MTU = 517

    const val OP_HELLO = 0x01
    const val OP_ACK = 0x02
    const val OP_DECLINE = 0x03
    const val OP_TAP = 0x04

    /** Reserved: spec 4 sent it to start the animations. No phone sends it any more and every phone ignores it. */
    const val EVENT_GO = 0x01

    /** The sender has an offer (a new one) for the connected receiver to read. */
    const val EVENT_OFFER_READY = 0x02

    /** The sender no longer offers anything (its listener cancelled, the time ran out, the beam is over): a receiver that holds an offer fetched ahead lets it go. */
    const val EVENT_OFFER_WITHDRAWN = 0x03

    /** "No jerk to report" in an age field. */
    const val NO_AGE = 0xFFFF_FFFFL

    /** A jerk older than this is not worth reporting. */
    const val MAX_REPORTED_AGE_MS = 5_000L

    fun roleOfService(uuid: String): Role? = when (uuid) {
        SERVICE_SEND -> Role.SEND
        SERVICE_RECEIVE -> Role.RECEIVE
        else -> null
    }

    fun serviceOf(role: Role): String = if (role == Role.SEND) SERVICE_SEND else SERVICE_RECEIVE

    // ── Info ─────────────────────────────────────────────────────────────────────────

    /** Five bytes: the version, then the age of the last jerk in ms as 4 bytes big endian ([NO_AGE] for none). */
    fun encodeInfo(ageMs: Long): ByteArray {
        val age = if (ageMs < 0L || ageMs > MAX_REPORTED_AGE_MS) NO_AGE else ageMs
        val out = ByteArray(5)
        out[0] = VERSION.toByte()
        putU32(out, 1, age)
        return out
    }

    /** The age of the peer's last jerk in ms, -1 when it reports none; null when the bytes are not an Info value. */
    fun decodeInfo(bytes: ByteArray): Long? {
        if (bytes.size != 5 || bytes[0].toInt() != VERSION) return null
        val age = getU32(bytes, 1)
        return if (age == NO_AGE || age > MAX_REPORTED_AGE_MS) -1L else age
    }

    // ── Control ──────────────────────────────────────────────────────────────────────

    sealed interface ControlMessage {
        class Hello(val challenge: ByteArray) : ControlMessage
        data object Ack : ControlMessage
        data object Decline : ControlMessage

        /** The receiver's detector fired; [ageMs] is how long ago its last jerk was, -1 for none. */
        data class Tap(val ageMs: Long) : ControlMessage
    }

    fun encodeHello(challenge: ByteArray): ByteArray? {
        if (challenge.size != SongTapSigning.CHALLENGE_BYTES) return null
        val out = ByteArray(1 + SongTapSigning.CHALLENGE_BYTES)
        out[0] = OP_HELLO.toByte()
        challenge.copyInto(out, 1)
        return out
    }

    fun encodeAck(): ByteArray = byteArrayOf(OP_ACK.toByte())

    fun encodeDecline(): ByteArray = byteArrayOf(OP_DECLINE.toByte())

    fun encodeTap(ageMs: Long): ByteArray {
        val age = if (ageMs < 0L || ageMs > MAX_REPORTED_AGE_MS) NO_AGE else ageMs
        val out = ByteArray(5)
        out[0] = OP_TAP.toByte()
        putU32(out, 1, age)
        return out
    }

    /** Null for anything that is not exactly one well-formed message. */
    fun decodeControl(bytes: ByteArray): ControlMessage? {
        if (bytes.isEmpty()) return null
        return when (bytes[0].toInt() and 0xFF) {
            OP_HELLO -> if (bytes.size == 1 + SongTapSigning.CHALLENGE_BYTES) ControlMessage.Hello(bytes.copyOfRange(1, bytes.size)) else null
            OP_ACK -> if (bytes.size == 1) ControlMessage.Ack else null
            OP_DECLINE -> if (bytes.size == 1) ControlMessage.Decline else null
            OP_TAP -> if (bytes.size == 5) {
                val age = getU32(bytes, 1)
                ControlMessage.Tap(if (age == NO_AGE || age > MAX_REPORTED_AGE_MS) -1L else age)
            } else null
            else -> null
        }
    }

    fun isGo(bytes: ByteArray): Boolean = bytes.size == 1 && (bytes[0].toInt() and 0xFF) == EVENT_GO

    fun encodeGo(): ByteArray = byteArrayOf(EVENT_GO.toByte())

    fun isOfferReady(bytes: ByteArray): Boolean = bytes.size == 1 && (bytes[0].toInt() and 0xFF) == EVENT_OFFER_READY

    fun encodeOfferReady(): ByteArray = byteArrayOf(EVENT_OFFER_READY.toByte())

    fun isOfferWithdrawn(bytes: ByteArray): Boolean = bytes.size == 1 && (bytes[0].toInt() and 0xFF) == EVENT_OFFER_WITHDRAWN

    fun encodeOfferWithdrawn(): ByteArray = byteArrayOf(EVENT_OFFER_WITHDRAWN.toByte())

    // ── Names ────────────────────────────────────────────────────────────────────────

    /** [CHAR_NAME]: the version byte, then the clean name in UTF-8 (at most [SongTapNames.MAX_NAME_BYTES] bytes). */
    fun encodeName(name: String): ByteArray {
        val body = SongTapText.encodeUtf8(SongTapText.toCodePoints(SongTapNames.orFallback(name)))
        val out = ByteArray(1 + body.size)
        out[0] = VERSION.toByte()
        body.copyInto(out, 1)
        return out
    }

    /** The clean name in a Name value; null for a wrong version, bad UTF-8, too long or nothing usable. */
    fun decodeName(bytes: ByteArray): String? {
        if (bytes.size < 2 || bytes.size > 1 + SongTapNames.MAX_NAME_BYTES || bytes[0].toInt() != VERSION) return null
        val cps = SongTapText.decodeUtf8(bytes, 1, bytes.size) ?: return null
        return SongTapNames.fromAdvert(SongTapText.fromCodePoints(cps))
    }

    /** The data of the manufacturer block ([ADVERT_COMPANY_ID]) of an Android phone's scan response: [ADVERT_VERSION], then the short name in UTF-8. */
    fun encodeAdvertName(name: String): ByteArray {
        val body = SongTapText.encodeUtf8(SongTapText.toCodePoints(SongTapNames.advertName(SongTapNames.orFallback(name))))
        val out = ByteArray(1 + body.size)
        out[0] = ADVERT_VERSION.toByte()
        body.copyInto(out, 1)
        return out
    }

    /** The clean short name in a manufacturer block's data; null for a wrong version, bad UTF-8, too long or nothing usable. */
    fun decodeAdvertName(bytes: ByteArray): String? {
        if (bytes.size < 2 || bytes.size > 1 + SongTapNames.ADVERT_NAME_MAX_BYTES || bytes[0].toInt() != ADVERT_VERSION) return null
        val cps = SongTapText.decodeUtf8(bytes, 1, bytes.size) ?: return null
        return SongTapNames.fromAdvert(SongTapText.fromCodePoints(cps))
    }

    private fun putU32(out: ByteArray, at: Int, v: Long) {
        for (k in 0 until 4) out[at + k] = (v shr (24 - 8 * k)).toByte()
    }

    private fun getU32(b: ByteArray, at: Int): Long {
        var v = 0L
        for (k in 0 until 4) v = (v shl 8) or (b[at + k].toLong() and 0xFFL)
        return v
    }
}

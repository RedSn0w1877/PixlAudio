package com.theveloper.pixelplay.songtap.ring

/**
 * What a ring code says: the YouTube Music video id of the song and a 22-bit session tag. Nothing else:
 * the receiver looks the title and the art up itself.
 *
 * Bits, most significant first, 88 in all = 11 bytes = the data of the Reed-Solomon word:
 * ```
 *   66 bits  the 11 characters of the video id, 6 bits each (A-Z 0..25, a-z 26..51, 0-9 52..61, '-' 62, '_' 63)
 *    6 bits  the minute bucket: (sender's clock in whole minutes) mod 64
 *    8 bits  random (injected by the caller)
 *    8 bits  a CRC-8 (x^8 + x^2 + x + 1, start value 0xFF) over the 80 bits above
 * ```
 * The task text asked for 6 + 16 bits of tag; eight of the 16 random bits are the checksum instead. A
 * Reed-Solomon decoder that is given a word with more errors than it can mend almost never "repairs" it
 * into a wrong codeword (the decoding spheres cover about one word in 10^11 of the space of 25-byte words),
 * but a decode that leans on many erasures can: with 12 or more bytes flagged as unknown, the few bytes that
 * remain decide the answer. The checksum rejects 255 of 256 of those. The scanner also needs three equal
 * decodes in a row. 8 random bits still tell two codes made in the same minute apart (256 values); the tag
 * is not a secret.
 *
 * Freshness: a code is accepted when its bucket is the receiver's current minute, one of the two before
 * it, or the one after it ([FRESH_PAST_MINUTES], [FRESH_FUTURE_MINUTES]): between 2 and 3 minutes of life
 * from the moment it is made (a code made at the end of a minute has just over 2 minutes), and the receiver's
 * clock may run up to a minute behind the sender's. A clock more than a minute apart makes codes fail as
 * [Freshness.EXPIRED] or [Freshness.FUTURE]; the Song Tap screen then says to check the date and time.
 */
class RingCodePayload(val videoId: String, val minuteBucket: Int, val random: Int) {
    override fun equals(other: Any?): Boolean =
        other is RingCodePayload && other.videoId == videoId && other.minuteBucket == minuteBucket && other.random == random

    override fun hashCode(): Int = (videoId.length * 31 + minuteBucket) * 31 + random

    /** The 22-bit tag as it is packed. */
    fun tag(): Int = RingCodePayloads.tagOf(videoId, minuteBucket, random)

    override fun toString(): String = "RingCodePayload($videoId, bucket=$minuteBucket, random=$random)"
}

enum class RingReject(val code: String) {
    BAD_LENGTH("length"),
    BAD_CHECKSUM("checksum"),
    BAD_VIDEO_ID("video_id"),
    BAD_TAG("tag"),
}

sealed class RingUnpack {
    class Ok(val payload: RingCodePayload) : RingUnpack()
    class Rejected(val reason: RingReject) : RingUnpack()
}

enum class Freshness(val code: String) { FRESH("fresh"), EXPIRED("expired"), FUTURE("future") }

object RingCodePayloads {
    const val VIDEO_ID_LENGTH = 11
    const val DATA_BYTES = 11
    const val MINUTE_BUCKETS = 64
    const val RANDOM_BITS = 8
    const val CHECK_BITS = 8
    const val MINUTE_MS = 60_000L

    /** A code stays valid while the receiver's minute is at most this many buckets after the code's. */
    const val FRESH_PAST_MINUTES = 2

    /** ...and at most this many before it (the receiver's clock runs behind the sender's). */
    const val FRESH_FUTURE_MINUTES = 1

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun symbolOf(c: Char): Int = ALPHABET.indexOf(c)

    fun isValidVideoId(id: String): Boolean {
        if (id.length != VIDEO_ID_LENGTH) return false
        for (c in id) if (symbolOf(c) < 0) return false
        return true
    }

    /** The minute bucket of a clock reading in epoch milliseconds. */
    fun bucketOf(nowMs: Long): Int {
        var minutes = nowMs / MINUTE_MS
        if (nowMs % MINUTE_MS < 0L) minutes -= 1L
        return mod(minutes, MINUTE_BUCKETS.toLong()).toInt()
    }

    private fun mod(a: Long, m: Long): Long {
        val r = a % m
        return if (r < 0L) r + m else r
    }

    fun tagOf(videoId: String, bucket: Int, random: Int): Int {
        var reg = 0xFF
        for (c in videoId) reg = crcStep(reg, symbolOf(c).toLong(), 6)
        reg = crcStep(reg, ((bucket shl RANDOM_BITS) or random).toLong(), 6 + RANDOM_BITS)
        return (bucket shl 16) or (random shl 8) or reg
    }

    private fun crcStep(start: Int, value: Long, bits: Int): Int {
        var reg = start
        for (i in bits - 1 downTo 0) {
            val inBit = ((value ushr i) and 1L).toInt()
            val top = (reg ushr 7) and 1
            reg = (reg shl 1) and 0xFF
            if ((top xor inBit) != 0) reg = reg xor 0x07
        }
        return reg
    }

    /** The 11 data bytes for a code, or null when the id is not 11 characters of the alphabet or a field is out of range. */
    fun pack(videoId: String, minuteBucket: Int, random: Int): ByteArray? {
        if (!isValidVideoId(videoId)) return null
        if (minuteBucket !in 0 until MINUTE_BUCKETS) return null
        if (random !in 0 until (1 shl RANDOM_BITS)) return null
        val tag = tagOf(videoId, minuteBucket, random)
        // 88 bits: id (66) then tag (22).
        val out = ByteArray(DATA_BYTES)
        var bitPos = 0
        fun put(value: Long, bits: Int) {
            for (i in bits - 1 downTo 0) {
                if (((value ushr i) and 1L) != 0L) {
                    out[bitPos ushr 3] = (out[bitPos ushr 3].toInt() or (0x80 ushr (bitPos and 7))).toByte()
                }
                bitPos++
            }
        }
        for (c in videoId) put(symbolOf(c).toLong(), 6)
        put(tag.toLong(), 22)
        return out
    }

    fun pack(payload: RingCodePayload): ByteArray? = pack(payload.videoId, payload.minuteBucket, payload.random)

    fun unpack(data: ByteArray): RingUnpack {
        if (data.size != DATA_BYTES) return RingUnpack.Rejected(RingReject.BAD_LENGTH)
        fun bit(pos: Int): Int = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
        val sb = StringBuilder(VIDEO_ID_LENGTH)
        var pos = 0
        for (i in 0 until VIDEO_ID_LENGTH) {
            var sym = 0
            for (k in 0 until 6) sym = (sym shl 1) or bit(pos++)
            sb.append(ALPHABET[sym])
        }
        var bucket = 0
        for (k in 0 until 6) bucket = (bucket shl 1) or bit(pos++)
        var random = 0
        for (k in 0 until RANDOM_BITS) random = (random shl 1) or bit(pos++)
        var check = 0
        for (k in 0 until CHECK_BITS) check = (check shl 1) or bit(pos++)
        val id = sb.toString()
        val expected = tagOf(id, bucket, random) and 0xFF
        if (check != expected) return RingUnpack.Rejected(RingReject.BAD_CHECKSUM)
        return RingUnpack.Ok(RingCodePayload(id, bucket, random))
    }

    /** The whole 25-byte word for a code. */
    fun encodeCodeword(videoId: String, minuteBucket: Int, random: Int): ByteArray? {
        val data = pack(videoId, minuteBucket, random) ?: return null
        return ReedSolomon.encode(data)
    }

    /** The 200 slot bits (0/1) for a code: the picture's information. */
    fun encodeSlots(videoId: String, minuteBucket: Int, random: Int): ByteArray? {
        val word = encodeCodeword(videoId, minuteBucket, random) ?: return null
        return RingCodeLayout.codewordToSlots(word)
    }

    /** A corrected codeword's payload (its first 11 bytes). */
    fun fromCodeword(word: ByteArray): RingUnpack {
        if (word.size != ReedSolomon.N) return RingUnpack.Rejected(RingReject.BAD_LENGTH)
        return unpack(word.copyOfRange(0, DATA_BYTES))
    }

    /**
     * Is a code with this bucket still good on a clock reading of [nowMs]? `age` is the receiver's minute
     * minus the code's, modulo 64, read as a number between -32 and 31.
     */
    fun freshness(bucket: Int, nowMs: Long): Freshness {
        val age = mod((bucketOf(nowMs) - bucket + 32).toLong(), MINUTE_BUCKETS.toLong()).toInt() - 32
        return when {
            age > FRESH_PAST_MINUTES -> Freshness.EXPIRED
            age < -FRESH_FUTURE_MINUTES -> Freshness.FUTURE
            else -> Freshness.FRESH
        }
    }
}

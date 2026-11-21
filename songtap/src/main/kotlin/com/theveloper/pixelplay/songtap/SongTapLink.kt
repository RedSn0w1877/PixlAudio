package com.theveloper.pixelplay.songtap

/**
 * The text form of a beam, for a QR code: `pixlsong1:` and the payload's bytes in base64url without
 * padding. Strict on the way back: only the 64 url-safe characters, no padding, and a length and trailing
 * bits that only [encode] produces, so one payload has exactly one link.
 */
object SongTapLink {

    const val PREFIX = "pixlsong1:"

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(payloadBytes: ByteArray): String {
        val sb = StringBuilder(PREFIX.length + (payloadBytes.size * 4 + 2) / 3)
        sb.append(PREFIX)
        var i = 0
        while (i + 2 < payloadBytes.size) {
            val n = ((payloadBytes[i].toInt() and 0xFF) shl 16) or ((payloadBytes[i + 1].toInt() and 0xFF) shl 8) or (payloadBytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63]).append(ALPHABET[(n shr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (payloadBytes.size - i) {
            1 -> {
                val n = (payloadBytes[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
            }
            2 -> {
                val n = ((payloadBytes[i].toInt() and 0xFF) shl 16) or ((payloadBytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63]).append(ALPHABET[(n shr 6) and 63])
            }
        }
        return sb.toString()
    }

    /** The payload bytes inside [link], or null when it is not exactly a link [encode] could have written. */
    fun decode(link: String): ByteArray? {
        if (!link.startsWith(PREFIX)) return null
        val body = link.substring(PREFIX.length)
        if (body.isEmpty() || body.length % 4 == 1) return null
        val out = ByteArray(body.length * 3 / 4)
        var acc = 0
        var bits = 0
        var n = 0
        for (c in body) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = ((acc shr bits) and 0xFF).toByte()
                acc = acc and ((1 shl bits) - 1)
            }
        }
        // Leftover bits must be zero: that is what makes the encoding unique.
        if (acc != 0) return null
        return out.copyOf(n)
    }
}

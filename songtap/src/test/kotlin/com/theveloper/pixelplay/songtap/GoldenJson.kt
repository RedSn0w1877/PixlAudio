package com.theveloper.pixelplay.songtap

/**
 * The text form of the golden vectors: JSON with keys in the order they are added, no whitespace, Int64
 * values as decimal strings, bytes as lower-case hex strings, and every character outside printable ASCII
 * escaped as `\uXXXX` (UTF-16 units), so the bytes of a golden file are the same whatever wrote them.
 */
class GoldenJson {
    private val out = StringBuilder(256)
    private var needComma = false

    override fun toString(): String = out.toString()

    private fun sep() {
        if (needComma) out.append(',')
        needComma = true
    }

    private fun key(name: String) {
        sep()
        quote(name)
        out.append(':')
    }

    fun obj(): GoldenJson { sep(); out.append('{'); needComma = false; return this }
    fun obj(name: String): GoldenJson { key(name); out.append('{'); needComma = false; return this }
    fun end(): GoldenJson { out.append('}'); needComma = true; return this }
    fun arr(name: String): GoldenJson { key(name); out.append('['); needComma = false; return this }
    fun endArr(): GoldenJson { out.append(']'); needComma = true; return this }

    fun int(name: String, v: Int): GoldenJson { key(name); out.append(v); return this }
    fun long(name: String, v: Long): GoldenJson { key(name); out.append('"').append(v).append('"'); return this }
    fun bool(name: String, v: Boolean): GoldenJson { key(name); out.append(if (v) "true" else "false"); return this }
    fun str(name: String, v: String): GoldenJson { key(name); quote(v); return this }
    fun strOrNull(name: String, v: String?): GoldenJson { key(name); if (v == null) out.append("null") else quote(v); return this }
    fun hex(name: String, v: ByteArray?): GoldenJson { key(name); if (v == null) out.append("null") else quote(hexOf(v)); return this }
    fun raw(name: String, json: String): GoldenJson { key(name); out.append(json); return this }

    fun intItem(v: Int): GoldenJson { sep(); out.append(v); return this }
    fun strItem(v: String): GoldenJson { sep(); quote(v); return this }

    private fun quote(s: String) {
        out.append('"')
        for (ch in s) {
            val c = ch.code
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                c in 0x20..0x7E -> out.append(ch)
                else -> {
                    out.append("\\u")
                    out.append(HEX[(c ushr 12) and 15]).append(HEX[(c ushr 8) and 15])
                    out.append(HEX[(c ushr 4) and 15]).append(HEX[c and 15])
                }
            }
        }
        out.append('"')
    }

    companion object {
        private const val HEX = "0123456789abcdef"

        fun hexOf(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) sb.append(HEX[(x.toInt() shr 4) and 15]).append(HEX[x.toInt() and 15])
            return sb.toString()
        }

        fun bytesOf(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) out[i] = ((HEX.indexOf(hex[2 * i]) shl 4) or HEX.indexOf(hex[2 * i + 1])).toByte()
            return out
        }
    }
}

/** The one random stream the generator uses: SplitMix64, so a seed gives the same numbers anywhere. */
class SplitMix64(private var state: Long) {
    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()
}

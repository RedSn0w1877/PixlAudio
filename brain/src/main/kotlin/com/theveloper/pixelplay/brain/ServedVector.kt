package com.theveloper.pixelplay.brain

/**
 * The feature vector a pick was served with (docs/brain-spec.md, 5.9 "Timing": `x` is computed when the track starts and held with
 * the queue entry), kept with the play so the learner trains, and the gate predicts, on exactly what the ranker saw. Stage 3 trained
 * on a REDUCED vector recomputed from the log (no graph, islands, tags, repeat activation, popularity or freshness: deviation 160 on
 * iOS, 160-ish on Android); stage 4 stores the served one.
 *
 * Quantised so a row stays small and so a live update and a replay read the same numbers: each of the 51 entries is rounded to
 * `1/4096` and kept as a little-endian signed 16-bit integer (102 bytes; a value past +-8 is clipped, and the learner refuses
 * anything past [Ranker.X_MAX] in any case). Text form: lower-case hex, 204 characters. A value that does not read back as exactly
 * [BYTES] bytes is "no served vector": the replay then falls back to the reduced vector, as it does for every older row.
 */
object ServedVector {
    const val SCALE = 4096.0
    const val BYTES = Ranker.D * 2
    private const val LIMIT = 32767L

    /** The 102 bytes of [x] (length [Ranker.D]). A value that is not finite is stored as 0. */
    fun quantize(x: DoubleArray): ByteArray {
        val out = ByteArray(BYTES)
        for (i in 0 until Ranker.D) {
            val v = x[i]
            var q = if (v.isNaN() || v.isInfinite()) 0L else BrainMath.floorToLong(v * SCALE + 0.5)
            if (q > LIMIT) q = LIMIT
            if (q < -LIMIT) q = -LIMIT
            val u = (q and 0xFFFFL).toInt()
            out[2 * i] = (u and 0xFF).toByte()
            out[2 * i + 1] = ((u ushr 8) and 0xFF).toByte()
        }
        return out
    }

    /** The vector [bytes] holds, or null when it is not exactly [BYTES] long. */
    fun dequantize(bytes: ByteArray?): DoubleArray? {
        if (bytes == null || bytes.size != BYTES) return null
        val out = DoubleArray(Ranker.D)
        for (i in 0 until Ranker.D) {
            val u = (bytes[2 * i].toInt() and 0xFF) or ((bytes[2 * i + 1].toInt() and 0xFF) shl 8)
            val signed = if (u >= 0x8000) u - 0x10000 else u
            out[i] = signed.toDouble() / SCALE
        }
        return out
    }

    private const val HEX = "0123456789abcdef"

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 15])
        }
        return sb.toString()
    }

    /** The bytes of lower-case hex [text]; null for a text of the wrong length or with any other character. */
    fun fromHex(text: String?): ByteArray? {
        if (text == null || text.length != BYTES * 2) return null
        val out = ByteArray(BYTES)
        for (i in 0 until BYTES) {
            val hi = digit(text[2 * i])
            val lo = digit(text[2 * i + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }
}

package com.theveloper.pixelplay.songtap

/**
 * Text handling for a beamed song: strict UTF-8, the characters a beam may never carry, and the one
 * `sanitize` function the sender uses to make text fit and the receiver uses to prove it already does.
 *
 * Everything works on Unicode code points held in an IntArray, never on a platform string type, so the
 * Swift port (which iterates unicode scalars) behaves identically.
 */
object SongTapText {

    /** Characters that become one ordinary space (and runs of spaces collapse to one). */
    private fun isSpaceLike(cp: Int): Boolean = when (cp) {
        0x20, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> cp in 0x2000..0x200A
    }

    /** Characters that are removed: controls, zero width, bidirectional marks, tags, noncharacters. */
    fun isStripped(cp: Int): Boolean = when {
        cp < 0x20 -> true
        cp in 0x7F..0x9F -> true
        cp == 0xAD || cp == 0x061C || cp == 0x180E -> true
        cp in 0x200B..0x200F -> true
        cp in 0x202A..0x202E -> true
        cp in 0x2060..0x206F -> true
        cp == 0xFEFF -> true
        cp in 0xFFF9..0xFFFC -> true
        cp in 0xFDD0..0xFDEF -> true
        cp == 0xFFFE || cp == 0xFFFF -> true
        cp in 0xE0000..0xE007F -> true
        cp in 0xD800..0xDFFF -> true
        cp > 0x10FFFF -> true
        else -> false
    }

    /** UTF-8 length of one code point. */
    fun utf8Length(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    fun utf8Length(cps: IntArray): Int {
        var n = 0
        for (cp in cps) n += utf8Length(cp)
        return n
    }

    fun encodeUtf8(cps: IntArray): ByteArray {
        val out = ByteArray(utf8Length(cps))
        var i = 0
        for (cp in cps) {
            when {
                cp < 0x80 -> out[i++] = cp.toByte()
                cp < 0x800 -> {
                    out[i++] = (0xC0 or (cp shr 6)).toByte()
                    out[i++] = (0x80 or (cp and 0x3F)).toByte()
                }
                cp < 0x10000 -> {
                    out[i++] = (0xE0 or (cp shr 12)).toByte()
                    out[i++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    out[i++] = (0x80 or (cp and 0x3F)).toByte()
                }
                else -> {
                    out[i++] = (0xF0 or (cp shr 18)).toByte()
                    out[i++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    out[i++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    out[i++] = (0x80 or (cp and 0x3F)).toByte()
                }
            }
        }
        return out
    }

    /**
     * Strict UTF-8: no overlong forms, no surrogates, nothing above U+10FFFF, no truncated sequence.
     * Null when [bytes] from [from] until [to] is not valid.
     */
    fun decodeUtf8(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): IntArray? {
        val out = IntArray(to - from)
        var n = 0
        var i = from
        while (i < to) {
            val b0 = bytes[i].toInt() and 0xFF
            val cp: Int
            val len: Int
            when {
                b0 < 0x80 -> { cp = b0; len = 1 }
                b0 in 0xC2..0xDF -> { cp = b0 and 0x1F; len = 2 }
                b0 in 0xE0..0xEF -> { cp = b0 and 0x0F; len = 3 }
                b0 in 0xF0..0xF4 -> { cp = b0 and 0x07; len = 4 }
                else -> return null
            }
            if (i + len > to) return null
            var value = cp
            for (k in 1 until len) {
                val b = bytes[i + k].toInt() and 0xFF
                if (b and 0xC0 != 0x80) return null
                value = (value shl 6) or (b and 0x3F)
            }
            if (len == 3 && value < 0x800) return null
            if (len == 4 && (value < 0x10000 || value > 0x10FFFF)) return null
            if (value in 0xD800..0xDFFF) return null
            out[n++] = value
            i += len
        }
        return out.copyOf(n)
    }

    /** The code points of a Kotlin string; a lone surrogate becomes U+FFFD (then `sanitize` keeps it). */
    fun toCodePoints(s: String): IntArray {
        val out = IntArray(s.length)
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            if (c in 0xD800..0xDBFF && i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF) {
                out[n++] = 0x10000 + ((c - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
                i += 2
            } else if (c in 0xD800..0xDFFF) {
                out[n++] = 0xFFFD
                i++
            } else {
                out[n++] = c
                i++
            }
        }
        return out.copyOf(n)
    }

    fun fromCodePoints(cps: IntArray): String {
        val sb = StringBuilder(cps.size)
        for (cp in cps) {
            if (cp >= 0x10000) {
                val v = cp - 0x10000
                sb.append((0xD800 + (v shr 10)).toChar())
                sb.append((0xDC00 + (v and 0x3FF)).toChar())
            } else {
                sb.append(cp.toChar())
            }
        }
        return sb.toString()
    }

    /**
     * Makes [raw] fit a beam: stripped characters removed, space-like characters turned into one space,
     * leading and trailing spaces dropped, then cut at a code point boundary so the UTF-8 form is at most
     * [maxBytes]. Idempotent: `sanitize(sanitize(x)) == sanitize(x)`.
     */
    fun sanitize(raw: IntArray, maxBytes: Int): IntArray {
        val out = IntArray(raw.size)
        var n = 0
        var bytes = 0
        var pendingSpace = false
        for (cp in raw) {
            if (isSpaceLike(cp)) {
                if (n > 0) pendingSpace = true
                continue
            }
            if (isStripped(cp)) continue
            val add = utf8Length(cp) + (if (pendingSpace) 1 else 0)
            if (bytes + add > maxBytes) break
            if (pendingSpace) { out[n++] = 0x20; bytes++; pendingSpace = false }
            out[n++] = cp
            bytes += utf8Length(cp)
        }
        return out.copyOf(n)
    }

    fun sanitize(raw: String, maxBytes: Int): String = fromCodePoints(sanitize(toCodePoints(raw), maxBytes))

    /** True when [cps] is non-empty, fits [maxBytes] and is already what `sanitize` would return. */
    fun isClean(cps: IntArray, maxBytes: Int): Boolean {
        if (cps.isEmpty() || utf8Length(cps) > maxBytes) return false
        val again = sanitize(cps, maxBytes)
        if (again.size != cps.size) return false
        for (i in cps.indices) if (again[i] != cps[i]) return false
        return true
    }
}

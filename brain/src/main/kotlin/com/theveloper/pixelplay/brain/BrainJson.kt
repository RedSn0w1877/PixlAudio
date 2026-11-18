package com.theveloper.pixelplay.brain

/**
 * The canonical text form the brain's records and golden vectors use (docs/brain-spec.md, 10.4):
 * JSON with keys in a fixed order, no whitespace, Int64 values as decimal strings, doubles as
 * 16-hex-digit bit patterns, and every character outside printable ASCII escaped as `\uXXXX`
 * (UTF-16 units), so the bytes are the same whatever wrote them.
 *
 * A writer and a small reader; no reflection, no platform JSON library.
 */
object BrainJson {

    private const val HEX = "0123456789abcdef"

    /** Builds one canonical JSON value. Keys are written in the order they are added. */
    class Writer {
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

        fun beginObject(): Writer { sep(); out.append('{'); needComma = false; return this }
        fun beginObject(name: String): Writer { key(name); out.append('{'); needComma = false; return this }
        fun endObject(): Writer { out.append('}'); needComma = true; return this }
        fun beginArray(): Writer { sep(); out.append('['); needComma = false; return this }
        fun beginArray(name: String): Writer { key(name); out.append('['); needComma = false; return this }
        fun endArray(): Writer { out.append(']'); needComma = true; return this }

        fun int(name: String, value: Int): Writer { key(name); out.append(value); return this }
        fun bool(name: String, value: Boolean): Writer { key(name); out.append(if (value) "true" else "false"); return this }
        fun nullValue(name: String): Writer { key(name); out.append("null"); return this }

        /** An Int64 as a decimal string. */
        fun long(name: String, value: Long): Writer { key(name); out.append('"').append(value).append('"'); return this }

        /** A double as its 16-hex-digit bit pattern. */
        fun double(name: String, value: Double): Writer { key(name); out.append('"').append(hex64(value.toRawBits())).append('"'); return this }

        fun string(name: String, value: String): Writer { key(name); quote(value); return this }

        /** A value that is already canonical JSON (an encoded event, a nested object). */
        fun raw(name: String, json: String): Writer { key(name); out.append(json); return this }
        fun rawItem(json: String): Writer { sep(); out.append(json); return this }

        fun intItem(value: Int): Writer { sep(); out.append(value); return this }
        fun longItem(value: Long): Writer { sep(); out.append('"').append(value).append('"'); return this }
        fun doubleItem(value: Double): Writer { sep(); out.append('"').append(hex64(value.toRawBits())).append('"'); return this }
        fun stringItem(value: String): Writer { sep(); quote(value); return this }

        fun longs(name: String, values: LongArray): Writer {
            beginArray(name)
            for (v in values) longItem(v)
            return endArray()
        }

        fun ints(name: String, values: IntArray): Writer {
            beginArray(name)
            for (v in values) intItem(v)
            return endArray()
        }

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
    }

    fun hex64(bits: Long): String {
        val chars = CharArray(16)
        for (i in 0 until 16) chars[i] = HEX[((bits ushr ((15 - i) * 4)) and 15L).toInt()]
        return String(chars)
    }

    fun parseHex64(text: String): Long {
        var v = 0L
        for (ch in text) {
            val d = when (ch) {
                in '0'..'9' -> ch.code - 48
                in 'a'..'f' -> ch.code - 87
                in 'A'..'F' -> ch.code - 55
                else -> throw IllegalArgumentException("bad hex")
            }
            v = (v shl 4) or d.toLong()
        }
        return v
    }

    fun doubleOfHex(text: String): Double = Double.fromBits(parseHex64(text))

    // ── Reader ───────────────────────────────────────────────────────────────

    /**
     * Parses one JSON value into plain Kotlin values: `Map<String, Any?>` (insertion-ordered),
     * `List<Any?>`, `String`, `Long` (a bare integer), `Boolean`, or null. Bare numbers with a
     * fraction or exponent are not part of the canonical form and are rejected.
     */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipSpace()
        val v = p.value()
        p.skipSpace()
        if (p.pos != text.length) throw IllegalArgumentException("trailing text at ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipSpace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun value(): Any? {
            if (pos >= s.length) throw IllegalArgumentException("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("bad value at $pos")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("bad literal at $pos")
            pos += word.length
            return v
        }

        private fun num(): Long {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            return s.substring(start, pos).toLong()
        }

        private fun str(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (pos >= s.length) throw IllegalArgumentException("unterminated string")
                val c = s[pos++]
                if (c == '"') break
                if (c != '\\') { out.append(c); continue }
                when (val e = s[pos++]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> {
                        out.append(parseHex64(s.substring(pos, pos + 4)).toInt().toChar())
                        pos += 4
                    }
                    else -> throw IllegalArgumentException("bad escape \\$e")
                }
            }
            return out.toString()
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (s[pos] == '}') { pos++; return out }
            while (true) {
                skipSpace()
                val k = str()
                skipSpace()
                if (s[pos++] != ':') throw IllegalArgumentException("expected : at $pos")
                skipSpace()
                out[k] = value()
                skipSpace()
                val c = s[pos++]
                if (c == '}') break
                if (c != ',') throw IllegalArgumentException("expected , at $pos")
            }
            return out
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipSpace()
            if (s[pos] == ']') { pos++; return out }
            while (true) {
                skipSpace()
                out.add(value())
                skipSpace()
                val c = s[pos++]
                if (c == ']') break
                if (c != ',') throw IllegalArgumentException("expected , at $pos")
            }
            return out
        }
    }
}

package com.theveloper.pixelplay.eval

/**
 * A small, lenient JSON reader for the ListenBrainz responses (they carry fractions, which the brain's canonical BrainJson rejects on
 * purpose). Objects are `Map<String, Any?>`, arrays `List<Any?>`, numbers `Long` or `Double`.
 */
object Json {
    fun parse(text: String): Any? {
        val p = P(text)
        p.ws()
        return p.value()
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] <= ' ') i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c in '0'..'9') num() else error("bad json at $i")
            }
        }
        fun num(): Any {
            val st = i
            var frac = false
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9' || c == '-' || c == '+') i++
                else if (c == '.' || c == 'e' || c == 'E') { frac = true; i++ }
                else break
            }
            val t = s.substring(st, i)
            return if (frac) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                if (c == '"') break
                if (c != '\\') { sb.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            }
            return sb.toString()
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); i++ // ':'
                m[k] = value(); ws()
                if (s[i++] == '}') return m
            }
        }
        fun arr(): List<Any?> {
            i++
            val a = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return a }
            while (true) {
                a.add(value()); ws()
                if (s[i++] == ']') return a
            }
        }
    }

    /** Writes a JSON string literal. */
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>
fun Any?.arr(): List<Any?>? = this as? List<Any?>
fun Any?.str(): String? = this as? String
fun Any?.num(): Double? = when (this) { is Long -> toDouble(); is Double -> this; else -> null }

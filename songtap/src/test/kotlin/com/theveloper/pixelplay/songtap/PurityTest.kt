package com.theveloper.pixelplay.songtap

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Song Tap's core may not reach for anything whose result differs between the JVM, ART and Swift: no
 * platform types, no clock, no randomness, no I/O, no cryptography (each app injects its own), no
 * floating point, no locale-dependent text calls. The module has no Android dependency by construction;
 * this covers the JVM calls it could still reach.
 */
class PurityTest {

    private val banned = listOf(
        "android." to "no platform types in the core",
        "androidx." to "no platform types in the core",
        "java.io." to "no I/O in the core",
        "java.nio" to "no I/O in the core",
        "java.net." to "no I/O in the core",
        "java.security" to "the crypto is injected by each app",
        "javax." to "the crypto is injected by each app",
        "kotlin.math." to "integer arithmetic only",
        "Math." to "integer arithmetic only",
        "StrictMath" to "integer arithmetic only",
        "Double" to "no floating point in the core",
        "Float" to "no floating point in the core",
        ".toDouble(" to "no floating point in the core",
        ".toFloat(" to "no floating point in the core",
        "System." to "time and randomness enter as arguments",
        "java.time" to "time enters as nowMs",
        "java.util.Date" to "time enters as nowMs",
        "java.util.Calendar" to "time enters as nowMs",
        "java.util.Random" to "randomness enters as arguments",
        "kotlin.random" to "randomness enters as arguments",
        "kotlin.concurrent" to "no threads in the core",
        "Thread" to "no threads in the core",
        "Regex(" to "no regular expressions in the core",
        ".toRegex(" to "no regular expressions in the core",
        "java.util.regex" to "no regular expressions in the core",
        "Locale" to "no locale APIs",
        ".lowercase(" to "no locale-dependent case mapping",
        ".uppercase(" to "no locale-dependent case mapping",
        ".toLowerCase(" to "no locale-dependent case mapping",
        ".toUpperCase(" to "no locale-dependent case mapping",
        ".hashCode()" to "no platform hashes",
        "Normalizer" to "no platform text normalisation",
        "Charsets" to "UTF-8 is implemented in SongTapText",
        ".toByteArray(" to "UTF-8 is implemented in SongTapText",
        "String(" to "UTF-8 is implemented in SongTapText",
    )

    private val quote = '"'
    private val tick = '\''
    private val backslash = '\\'

    /** Removes comments, string literals and character literals, so prose and table contents cannot trip the scan. */
    private fun codeOnly(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else ' '
            when {
                c == '/' && next == '/' -> while (i < source.length && source[i] != '\n') i++
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                    i += 2
                }
                c == quote || c == tick -> {
                    i++
                    while (i < source.length && source[i] != c) {
                        if (source[i] == backslash) i++
                        i++
                    }
                    i++
                    out.append(c).append(c)
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun containsCall(code: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val at = code.indexOf(needle, from)
            if (at < 0) return false
            val before = if (at > 0) code[at - 1] else ' '
            if (!needle[0].isLetter() || !(before.isLetterOrDigit() || before == '_')) return true
            from = at + 1
        }
    }

    @Test
    fun `the scan sees a banned call and ignores comments and literals`() {
        assertTrue(containsCall(codeOnly("val x = Math.floor(1.0)"), "Math."))
        assertTrue(containsCall(codeOnly("val x: Double = 1.0"), "Double"))
        assertTrue(containsCall(codeOnly("x.hashCode()"), ".hashCode()"))
        assertTrue(containsCall(codeOnly("import java.security.MessageDigest"), "java.security"))
        assertFalse(containsCall(codeOnly("val x = 1 // Math.floor is banned"), "Math."))
        assertFalse(containsCall(codeOnly("val s = " + quote + "Double" + quote), "Double"))
        assertFalse(containsCall(codeOnly("val c = " + tick + quote + tick + "; val ok = 1"), "Double"))
        assertFalse(containsCall(codeOnly("val x = MyMath.f()"), "Math."))
    }

    /**
     * The ring code's image side (`ring/image`: style, renderer, scanner) measures pixels and needs floating
     * point and `kotlin.math`; it is checked by round trips, not by golden vectors. Everything else in the
     * core, the ring code's layout, Reed-Solomon, payload and timeline included, stays integer only.
     */
    private val floatingPointOk = setOf(
        "kotlin.math." , "Math.", "StrictMath", "Double", "Float", ".toDouble(", ".toFloat(",
    )

    private fun isImageSide(file: File): Boolean = file.invariantSeparatorsPath.contains("/ring/image/")

    @Test
    fun `no banned import or call in the core sources`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "run from the :songtap project directory (was ${File("").absolutePath})")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 8, "expected the whole core, found ${files.size} files")
        val violations = ArrayList<String>()
        for (file in files) {
            val code = codeOnly(file.readText())
            for ((needle, why) in banned) {
                if (isImageSide(file) && needle in floatingPointOk) continue
                if (containsCall(code, needle)) violations.add("${file.name}: `$needle` ($why)")
            }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `the image side is the only place floating point may live`() {
        val root = File("src/main/kotlin")
        val ringStrict = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/ring/") && !isImageSide(it) }.toList()
        assertTrue(ringStrict.size >= 5, "the strict ring files: ${ringStrict.map { it.name }}")
        for (file in ringStrict) {
            val code = codeOnly(file.readText())
            for (needle in floatingPointOk) assertFalse(containsCall(code, needle), "${file.name} uses `$needle`")
        }
    }
}

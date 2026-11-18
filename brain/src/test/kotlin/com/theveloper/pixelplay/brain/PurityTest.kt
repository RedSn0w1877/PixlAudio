package com.theveloper.pixelplay.brain

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The brain's sources may not use anything whose result differs between the JVM, ART and Swift
 * (docs/brain-spec.md, 10.3 and 10.4). The module has no Android dependency by construction; this
 * test covers the JVM calls it could still reach.
 *
 * Allowed from `java.lang.Math`: `floor` and `sqrt` only (both are exact), and only inside BrainMath.
 */
class PurityTest {

    private val banned = listOf(
        "kotlin.math." to "use BrainMath (10.2)",
        "Math.log" to "use BrainMath.ln",
        "Math.exp" to "use BrainMath.exp",
        "Math.pow" to "use BrainMath.powNeg",
        "Math.sin" to "not in the contract",
        "Math.cos" to "not in the contract",
        "Math.tan" to "not in the contract",
        "Math.cbrt" to "not in the contract",
        "Math.round" to "use BrainMath.floorToLong(x + 0.5)",
        "Math.rint" to "use BrainMath.floorToLong(x + 0.5)",
        "Math.fma" to "no fused multiply-add",
        "StrictMath" to "use BrainMath",
        ".roundToInt(" to "use BrainMath.floorToLong(x + 0.5)",
        ".roundToLong(" to "use BrainMath.floorToLong(x + 0.5)",
        ".pow(" to "use BrainMath.powNeg",
        "java.util.Random" to "use BrainHash.Rng",
        "kotlin.random" to "use BrainHash.Rng",
        ".hashCode()" to "use BrainHash.fnv1a64",
        "Regex(" to "no regular expressions in the core",
        ".toRegex(" to "no regular expressions in the core",
        "java.util.regex" to "no regular expressions in the core",
        "Locale" to "no locale APIs",
        ".lowercase(" to "use BrainText.fold",
        ".uppercase(" to "use BrainText.fold",
        ".toLowerCase(" to "use BrainText.fold",
        ".toUpperCase(" to "use BrainText.fold",
        "System.currentTimeMillis" to "time enters as nowMs",
        "System.nanoTime" to "time enters as nowMs",
        "java.time" to "time enters as nowMs and tzOffsetMin",
        "java.util.Date" to "time enters as nowMs",
        "java.util.Calendar" to "time enters as nowMs",
        "java.io." to "no I/O in the core",
        "java.nio.file" to "no I/O in the core",
        "java.net." to "no I/O in the core",
        "android." to "no platform types in the core",
        "androidx." to "no platform types in the core",
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
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                    i += 2
                }
                c == quote || c == tick -> {
                    // A string or a character literal: skip to the matching close, minding escapes.
                    i++
                    while (i < source.length && source[i] != c) {
                        if (source[i] == backslash) i++
                        i++
                    }
                    i++
                    out.append(c).append(c)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * True when [needle] occurs in [code] as its own name: a needle that starts with a letter does not
     * match inside a longer identifier (`BrainMath.exp` is not `Math.exp`).
     */
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
    fun `the scan sees a banned call and ignores our own names, comments and literals`() {
        assertTrue(containsCall(codeOnly("val x = Math.exp(1.0)"), "Math.exp"))
        assertTrue(containsCall(codeOnly("val x = java.lang.Math.pow(2.0, 3.0)"), "Math.pow"))
        assertFalse(containsCall(codeOnly("val x = BrainMath.exp(1.0) // Math.exp is banned"), "Math.exp"))
        val literal = "val c = " + tick + quote + tick + "; val s = " + quote + "Math.log" + quote
        assertFalse(containsCall(codeOnly(literal), "Math.log"))
        assertTrue(containsCall(codeOnly("val c = " + tick + quote + tick + "; val y = Math.log(2.0)"), "Math.log"))
        assertTrue(containsCall(codeOnly("x.hashCode()"), ".hashCode()"))
        assertTrue(containsCall(codeOnly("import kotlin.math.ln"), "kotlin.math."))
    }

    @Test
    fun `no banned import or call in the brain sources`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "run from the :brain project directory (was ${File("").absolutePath})")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 15, "expected the whole core, found ${files.size} files")
        val violations = ArrayList<String>()
        for (file in files) {
            val code = codeOnly(file.readText())
            for ((needle, why) in banned) {
                if (containsCall(code, needle)) violations.add("${file.name}: `$needle` ($why)")
            }
            // Math.floor and Math.sqrt are the two platform calls the contract allows, in one place.
            if (file.name != "BrainMath.kt" && (containsCall(code, "Math.floor") || containsCall(code, "Math.sqrt"))) {
                violations.add("${file.name}: java.lang.Math (go through BrainMath)")
            }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }
}

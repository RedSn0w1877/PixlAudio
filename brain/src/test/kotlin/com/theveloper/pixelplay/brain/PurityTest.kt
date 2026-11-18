package com.theveloper.pixelplay.brain

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The brain's sources may not use anything whose result differs between the JVM, ART and Swift
 * (docs/brain-spec.md, 10.3 and 10.4). The module has no Android dependency by construction; this
 * test covers the JVM calls it could still reach.
 */
class PurityTest {

    private val banned = listOf(
        "kotlin.math." to "use BrainMath (10.2); only floor and sqrt are allowed, by full name in BrainMath",
        "Math.log" to "use BrainMath.ln",
        "Math.exp" to "use BrainMath.exp",
        "Math.pow" to "use BrainMath.powNeg",
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

    /** Removes comments and string literals so prose and table contents cannot trip the scan. */
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
                c == '"' -> {
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\') i++
                        i++
                    }
                    i++
                    out.append("\"\"")
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    @Test
    fun `no banned import or call in the brain sources`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "run from the :brain project directory (was ${File("").absolutePath})")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty())
        val violations = ArrayList<String>()
        for (file in files) {
            val code = codeOnly(file.readText())
            for ((needle, why) in banned) {
                if (code.contains(needle)) violations.add("${file.name}: `$needle` ($why)")
            }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }
}

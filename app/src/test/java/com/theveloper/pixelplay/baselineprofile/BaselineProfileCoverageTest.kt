package com.theveloper.pixelplay.baselineprofile

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PixlAudio ships as GitHub APKs, so it gets no Play cloud profiles: the bundled baseline profile is the
 * only ahead-of-time compilation input. The hand-written rules in app/src/main/baseline-prof.txt used to
 * miss the queue sheet, the lyrics page, the cast sheet and SmartImage, which therefore ran interpreted
 * after every install. This pins that each of them has a matching rule.
 */
class BaselineProfileCoverageTest {

    private fun profileLines(): List<String> {
        val file = listOf(File("src/main/baseline-prof.txt"), File("app/src/main/baseline-prof.txt")).first { it.isFile }
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    // A rule (an "HSPL...->" method rule or an "L...;" class rule) matches a class descriptor by the text
    // in front of its first double asterisk. (Written as a line comment: slash-asterisk would nest here.)
    private fun matches(rule: String, descriptor: String): Boolean {
        val body = rule.removePrefix("HSP").removePrefix("SP").removePrefix("P").removePrefix("H").removePrefix("S")
        if (!body.startsWith("L")) return false
        val prefix = body.substringBefore("**").removeSuffix(";").removeSuffix("->")
        return prefix.isNotEmpty() && descriptor.removeSuffix(";").startsWith(prefix)
    }

    @Test
    fun `player sheet, queue, lyrics, cast and image classes are covered by a rule`() {
        val rules = profileLines()
        val base = "Lcom/theveloper/pixelplay/presentation/components"
        val mustBeCovered = listOf(
            "$base/QueueBottomSheetKt;",
            "$base/QueueGlassControlsKt;",
            "$base/LyricsSheetKt;",
            "$base/LyricsFloatingToolbarKt;",
            "$base/CastBottomSheetKt;",
            "$base/SmartImageKt;",
            "$base/ExpressiveScrollBarKt;",
            "$base/subcomps/PlayerSeekBarKt;",
            // Already covered before; guards against someone narrowing the wildcards.
            "$base/UnifiedPlayerSheetV2Kt;",
            "$base/player/FullPlayerContentKt;",
            "Lcom/google/android/material/color/utilities/DynamicScheme;",
        )
        val uncovered = mustBeCovered.filter { descriptor -> rules.none { matches(it, descriptor) } }
        assertTrue(uncovered.isEmpty(), "no baseline-profile rule matches: $uncovered")
    }

    @Test
    fun `the rule matcher itself understands the profile syntax`() {
        assertTrue(matches("HSPLcom/a/B**->**(**)**", "Lcom/a/BKt;"))
        assertTrue(matches("Lcom/a/**;", "Lcom/a/b/CKt;"))
        assertTrue(!matches("HSPLcom/a/B**->**(**)**", "Lcom/a/CKt;"))
        assertTrue(!matches("Lcom/a/B**;", "Lcom/x/BKt;"))
    }
}

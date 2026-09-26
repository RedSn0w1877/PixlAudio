package com.theveloper.pixelplay.presentation.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word-synced lines are normally drawn as separately measured pieces. Scripts whose letters join
 * or cluster across a piece boundary must instead be drawn clipped from the whole shaped line.
 */
class LyricsScriptShapingTest {

    @Test
    fun latinCjkAndHebrew_useSeparatePieces() {
        assertFalse(LyricsRenderStyle.needsShapedPieces("Never gonna give you up"))
        assertFalse(LyricsRenderStyle.needsShapedPieces("Ça plane pour moi, ñandú"))
        assertFalse(LyricsRenderStyle.needsShapedPieces("夜に駆ける 君の手を"))
        assertFalse(LyricsRenderStyle.needsShapedPieces("사랑해 오늘도"))
        assertFalse(LyricsRenderStyle.needsShapedPieces("Привет, мир"))
        // Hebrew is right-to-left but its letters do not join.
        assertFalse(LyricsRenderStyle.needsShapedPieces("שלום עולם"))
    }

    @Test
    fun joiningAndClusteringScripts_drawShaped() {
        assertTrue(LyricsRenderStyle.needsShapedPieces("حبيبي يا نور العين"))
        assertTrue(LyricsRenderStyle.needsShapedPieces("दिल से रे"))
        assertTrue(LyricsRenderStyle.needsShapedPieces("ভালোবাসি"))
        assertTrue(LyricsRenderStyle.needsShapedPieces("ក្ដី"))
        // One shaped word anywhere in a mixed line is enough.
        assertTrue(LyricsRenderStyle.needsShapedPieces("Baby, حبيبي, tonight"))
        // Arabic presentation forms.
        assertTrue(LyricsRenderStyle.needsShapedPieces("ﻻ"))
    }

    @Test
    fun rtlDetection_followsFirstStrongCharacter() {
        assertTrue(LyricsRenderStyle.isRtlText("  «حبيبي» baby"))
        assertTrue(LyricsRenderStyle.isRtlText("שלום"))
        assertFalse(LyricsRenderStyle.isRtlText("baby حبيبي"))
        assertFalse(LyricsRenderStyle.isRtlText("123 ..."))
    }
}

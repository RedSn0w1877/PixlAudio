package com.theveloper.pixelplay.presentation.lyrics

import androidx.compose.ui.text.style.TextAlign
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Golden table of where a line sits, per voice. Width 1000 px, margins 24 / 44 px (the
 * render style's 24 dp / 44 dp at density 1).
 */
class LyricLineAlignmentTest {

    private val w = 1000
    private val ps = 24f
    private val pe = 44f

    private data class Placement(val left: Float, val right: Float, val align: TextAlign, val pivot: Float)

    private fun place(hasDuet: Boolean, alignment: KaraokeAlignment, duetEnd: Boolean, rtl: Boolean = false) = Placement(
        LyricLineAlignment.startPadding(hasDuet, alignment, duetEnd, w, ps, pe),
        LyricLineAlignment.endPadding(hasDuet, alignment, duetEnd, w, ps, pe),
        LyricLineAlignment.textAlign(hasDuet, alignment, duetEnd),
        LyricLineAlignment.pivotX(hasDuet, alignment, duetEnd, rtl),
    )

    @Test
    fun duet_singerOneIsFlushLeft_singerTwoIsFlushRight_atTheSameMargin() {
        val one = place(hasDuet = true, alignment = KaraokeAlignment.START, duetEnd = false)
        val two = place(hasDuet = true, alignment = KaraokeAlignment.START, duetEnd = true)
        assertThat(one).isEqualTo(Placement(24f, 150f, TextAlign.Left, 0f))
        assertThat(two).isEqualTo(Placement(150f, 24f, TextAlign.Right, 1f))
        // Mirror images: the outer margin of each singer equals the normal start margin.
        assertThat(one.left).isEqualTo(two.right)
        assertThat(one.right).isEqualTo(two.left)
    }

    @Test
    fun duet_ignoresTheAlignmentPreference() {
        for (pref in KaraokeAlignment.entries) {
            assertThat(place(true, pref, duetEnd = false)).isEqualTo(Placement(24f, 150f, TextAlign.Left, 0f))
            assertThat(place(true, pref, duetEnd = true)).isEqualTo(Placement(150f, 24f, TextAlign.Right, 1f))
        }
    }

    @Test
    fun duet_rightToLeftTextCannotFlipASingerToTheOtherSide() {
        assertThat(place(true, KaraokeAlignment.START, duetEnd = false, rtl = true).pivot).isEqualTo(0f)
        assertThat(place(true, KaraokeAlignment.START, duetEnd = true, rtl = true).pivot).isEqualTo(1f)
        assertThat(place(true, KaraokeAlignment.START, duetEnd = false, rtl = true).align).isEqualTo(TextAlign.Left)
    }

    @Test
    fun duet_narrowScreenKeepsTheMarginsAsTheFloor() {
        // 15% of 200 px is 30 px: below the 44 px end margin, so the margin wins.
        assertThat(LyricLineAlignment.endPadding(true, KaraokeAlignment.START, false, 200, ps, pe)).isEqualTo(44f)
        assertThat(LyricLineAlignment.startPadding(true, KaraokeAlignment.START, true, 100, ps, pe)).isEqualTo(24f)
    }

    @Test
    fun noDuet_followsThePreference() {
        assertThat(place(false, KaraokeAlignment.START, false)).isEqualTo(Placement(24f, 44f, TextAlign.Start, 0f))
        assertThat(place(false, KaraokeAlignment.CENTER, false)).isEqualTo(Placement(34f, 34f, TextAlign.Center, 0.5f))
        assertThat(place(false, KaraokeAlignment.END, false)).isEqualTo(Placement(44f, 24f, TextAlign.End, 1f))
        assertThat(place(false, KaraokeAlignment.START, false, rtl = true).pivot).isEqualTo(1f)
        assertThat(place(false, KaraokeAlignment.END, false, rtl = true).pivot).isEqualTo(0f)
    }

    @Test
    fun noDuet_aStrayDuetEndFlagChangesNothing() {
        for (pref in KaraokeAlignment.entries) {
            assertThat(place(false, pref, duetEnd = true)).isEqualTo(place(false, pref, duetEnd = false))
        }
    }
}

package com.theveloper.pixelplay.presentation.lyrics.sing

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.data.model.TimedLine
import com.theveloper.pixelplay.data.model.TimedSyllable
import com.theveloper.pixelplay.data.model.Voice
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyrics
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyricsBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VocalGapFinderTest {

    /** A word with an explicit end, like document lyrics and cloud-aligned lyrics carry. */
    private fun word(startMs: Long, durationMs: Long, text: String) = TimedSyllable(startMs, durationMs, text)

    private fun doc(vararg lines: TimedLine, voices: List<Voice> = listOf(Voice())): PreparedLyrics =
        PreparedLyricsBuilder.build(LyricsDoc(voices = voices, lines = lines.toList())) ?: throw AssertionError("expected a model")

    private fun line(startMs: Long, endMs: Long, voiceId: String = "lead", vararg syllables: TimedSyllable) =
        TimedLine(startMs, endMs, syllables.joinToString("") { it.text }, voiceId, syllables.toList())

    private fun synced(vararg lines: SyncedLine): PreparedLyrics =
        PreparedLyricsBuilder.build(Lyrics(synced = lines.toList())) ?: throw AssertionError("expected a model")

    // "Hello " sung [1000, 1500), "world" sung [2000, 2500): silence in between and after.
    private val helloWorld = doc(
        line(1_000, 2_500, "lead", word(1_000, 500, "Hello "), word(2_000, 500, "world")),
    )

    @Test
    fun `inside a gap the fade starts now and lasts the gap`() {
        val plan = VocalGapFinder.plan(helloWorld, nowLyricsMs = 1_600)

        assertEquals(VocalGapFinder.Plan(startAtLyricsMs = 1_600, fadeMs = 400, inGap = true), plan)
    }

    @Test
    fun `a gap that opens within the wait is waited for`() {
        // Mid "Hello": the gap opens at 1500, 400 ms away.
        val plan = VocalGapFinder.plan(helloWorld, nowLyricsMs = 1_100)

        assertEquals(VocalGapFinder.Plan(startAtLyricsMs = 1_500, fadeMs = 500, inGap = true), plan)
    }

    @Test
    fun `a gap that opens exactly at the wait limit is still taken`() {
        val plan = VocalGapFinder.plan(helloWorld, nowLyricsMs = 900)

        // The word starts at 1000 and ends at 1500: 600 ms away.
        assertEquals(1_500L, plan.startAtLyricsMs)
        assertTrue(plan.inGap)
    }

    @Test
    fun `a gap that opens after the wait limit is not taken`() {
        val plan = VocalGapFinder.plan(helloWorld, nowLyricsMs = 899)

        assertEquals(VocalGapFinder.Plan(startAtLyricsMs = 959, fadeMs = 700, inGap = false), plan)
    }

    @Test
    fun `a gap shorter than the minimum is skipped`() {
        val lyrics = doc(line(1_000, 2_200, "lead", word(1_000, 500, "one "), word(1_700, 500, "two")))

        // The 200 ms gap at 1500 is too short and "two" ends at 2200, which is too far from 1100.
        val plan = VocalGapFinder.plan(lyrics, nowLyricsMs = 1_100)

        assertFalse(plan.inGap)
        assertEquals(1_160L, plan.startAtLyricsMs)
        assertEquals(700, plan.fadeMs)
    }

    @Test
    fun `a long silence fades over the longest fade`() {
        val plan = VocalGapFinder.plan(helloWorld, nowLyricsMs = 3_000)

        assertEquals(VocalGapFinder.Plan(startAtLyricsMs = 3_000, fadeMs = 700, inGap = true), plan)
    }

    @Test
    fun `back to back words leave no gap and fall back to a prompt longest fade`() {
        val lyrics = doc(
            line(1_000, 2_500, "lead", word(1_000, 500, "a "), word(1_500, 500, "b "), word(2_000, 500, "c")),
        )

        assertEquals(
            VocalGapFinder.Plan(startAtLyricsMs = 1_160, fadeMs = 700, inGap = false),
            VocalGapFinder.plan(lyrics, nowLyricsMs = 1_100),
        )
    }

    @Test
    fun `inferred word ends never open a gap`() {
        // Word starts only: "c" has no end, so it is sung until the next line starts at 4000.
        val lyrics = synced(
            SyncedLine(
                1_000, "a b c",
                words = listOf(SyncedWord(1_000, "a"), SyncedWord(1_500, "b"), SyncedWord(2_000, "c")),
            ),
            SyncedLine(4_000, "d"),
        )

        val plan = VocalGapFinder.plan(lyrics, nowLyricsMs = 2_100)

        assertFalse(plan.inGap)
        assertEquals(2_160L, plan.startAtLyricsMs)
    }

    @Test
    fun `lines without word timing are sung until the next line`() {
        val lyrics = synced(SyncedLine(1_000, "one"), SyncedLine(3_000, "two"))

        val plan = VocalGapFinder.plan(lyrics, nowLyricsMs = 1_200)

        assertFalse(plan.inGap)
    }

    @Test
    fun `an explicit line end opens a gap before the next line`() {
        val lyrics = synced(SyncedLine(1_000, "one", endTime = 1_800), SyncedLine(3_000, "two"))

        val plan = VocalGapFinder.plan(lyrics, nowLyricsMs = 1_500)

        assertEquals(VocalGapFinder.Plan(startAtLyricsMs = 1_800, fadeMs = 700, inGap = true), plan)
    }

    @Test
    fun `an interlude is silent`() {
        // The first line is estimated to end at 2500; the next one starts at 20000, a 9 s+ interlude.
        val lyrics = synced(SyncedLine(1_000, "hello"), SyncedLine(20_000, "world"))

        val plan = VocalGapFinder.plan(lyrics, nowLyricsMs = 2_000)

        assertEquals(VocalGapFinder.Plan(startAtLyricsMs = 2_500, fadeMs = 700, inGap = true), plan)
    }

    @Test
    fun `a duet voice singing over the lead closes the gap`() {
        val voices = listOf(Voice(id = "lead", role = "lead"), Voice(id = "duet", role = "duet"))
        val lyrics = doc(
            line(1_000, 2_000, "lead", word(1_000, 1_000, "lead")),
            line(1_500, 2_600, "duet", word(1_500, 1_100, "duet")),
            voices = voices,
        )

        // The lead alone would be silent from 2000; the duet voice sings until 2600.
        val plan = VocalGapFinder.plan(lyrics, nowLyricsMs = 1_900)

        assertFalse(plan.inGap)
        assertEquals(1_960L, plan.startAtLyricsMs)
    }

    @Test
    fun `background vocals count as sung`() {
        val voices = listOf(Voice(id = "lead", role = "lead"), Voice(id = "bg", role = "background"))
        val lyrics = doc(
            line(1_000, 1_500, "lead", word(1_000, 500, "lead")),
            line(1_700, 2_800, "bg", word(1_700, 1_100, "ooh")),
            voices = voices,
        )

        // 1500..1700 is a 200 ms hole, too short; the background voice holds until 2800.
        val plan = VocalGapFinder.plan(lyrics, nowLyricsMs = 1_100)

        assertFalse(plan.inGap)
    }

    @Test
    fun `plain or missing lyrics start a moment from now`() {
        assertEquals(VocalGapFinder.Plan(5_060, 700, false), VocalGapFinder.plan(null, nowLyricsMs = 5_000))
    }

    @Test
    fun `voiced merges touching words and clips to the window`() {
        val lyrics = doc(line(1_000, 2_500, "lead", word(1_000, 500, "a "), word(1_500, 500, "b "), word(2_300, 200, "c")))

        val ranges = VocalGapFinder.voiced(lyrics, fromMs = 1_200, toMs = 2_400)

        assertEquals(listOf(1_200L until 2_000L, 2_300L until 2_400L), ranges)
    }

    @Test
    fun `voiced is empty for an empty window`() {
        assertTrue(VocalGapFinder.voiced(helloWorld, fromMs = 2_000, toMs = 2_000).isEmpty())
    }
}

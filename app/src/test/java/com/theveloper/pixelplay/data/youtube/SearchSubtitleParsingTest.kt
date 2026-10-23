package com.theveloper.pixelplay.data.youtube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The second column of a YouTube Music search row arrives as runs: every credited artist, the
 * " & " between them and each " • " separator is its own run. Only the separator may split fields.
 */
class SearchSubtitleParsingTest {

    private fun runs(vararg text: String) = text.toList()

    @Test fun `collaboration stays one artist field`() {
        val parsed = parseSearchSubtitle(
            runs("Song", " • ", "Lady Gaga", " & ", "Bruno Mars", " • ", "MAYHEM", " • ", "4:12")
        )
        assertEquals("Lady Gaga & Bruno Mars", parsed.artist)
        assertEquals("MAYHEM", parsed.album)
        assertEquals(252, parsed.durationSeconds)
    }

    @Test fun `comma separated credits stay one field`() {
        val parsed = parseSearchSubtitle(
            runs("Song", " • ", "Drake", ", ", "WizKid", " & ", "Kyla", " • ", "Views", " • ", "2:54")
        )
        assertEquals("Drake, WizKid & Kyla", parsed.artist)
        assertEquals("Views", parsed.album)
        assertEquals(174, parsed.durationSeconds)
    }

    @Test fun `plain song row`() {
        val parsed = parseSearchSubtitle(runs("Song", " • ", "The Weeknd", " • ", "Starboy", " • ", "3:50"))
        assertEquals("The Weeknd", parsed.artist)
        assertEquals("Starboy", parsed.album)
        assertEquals(230, parsed.durationSeconds)
    }

    @Test fun `video row has views rather than an album`() {
        val parsed = parseSearchSubtitle(runs("Eagles", " • ", "1.2B views", " • ", "6:31"))
        assertEquals("Eagles", parsed.artist)
        assertNull(parsed.album)
        assertEquals(391, parsed.durationSeconds)
    }

    @Test fun `video row with a type label`() {
        val parsed = parseSearchSubtitle(runs("Video", " • ", "Queen", " • ", "1.9B views", " • ", "5:55"))
        assertEquals("Queen", parsed.artist)
        assertNull(parsed.album)
        assertEquals(355, parsed.durationSeconds)
    }

    @Test fun `an album that looks like a duration is not the duration`() {
        val parsed = parseSearchSubtitle(runs("Song", " • ", "Some Band", " • ", "12:51", " • ", "3:07"))
        assertEquals("Some Band", parsed.artist)
        assertEquals("12:51", parsed.album)
        assertEquals(187, parsed.durationSeconds)
    }

    @Test fun `hour long durations`() {
        assertEquals(3753, parseSearchSubtitle(runs("Artist", " • ", "1:02:33")).durationSeconds)
    }

    @Test fun `separator inside a run still splits`() {
        val parsed = parseSearchSubtitle(runs("Song • Nova", " • First Light • 3:00"))
        assertEquals("Nova", parsed.artist)
        assertEquals("First Light", parsed.album)
        assertEquals(180, parsed.durationSeconds)
    }

    @Test fun `missing columns do not throw`() {
        val empty = parseSearchSubtitle(emptyList())
        assertEquals("", empty.artist)
        assertNull(empty.album)
        assertNull(empty.durationSeconds)

        val noDuration = parseSearchSubtitle(runs("Song", " • ", "Nova"))
        assertEquals("Nova", noDuration.artist)
        assertNull(noDuration.durationSeconds)
    }

    @Test fun `duration parsing`() {
        assertEquals(187, parseDurationSeconds("3:07"))
        assertEquals(3753, parseDurationSeconds("1:02:33"))
        assertNull(parseDurationSeconds("MAYHEM"))
        assertNull(parseDurationSeconds("12"))
    }
}

package com.theveloper.pixelplay.presentation.lyrics.model

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.data.model.TimedLine
import com.theveloper.pixelplay.data.model.TimedSyllable
import com.theveloper.pixelplay.data.model.Voice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedLyricsBuilderTest {

    private fun build(vararg lines: SyncedLine): PreparedLyrics =
        PreparedLyricsBuilder.build(Lyrics(synced = lines.toList())) ?: throw AssertionError("expected a model")

    private fun PreparedLyrics.lineRows(): List<Int> = rows.filterIsInstance<Row.Line>().map { it.lineIndex }

    // ---- basics ------------------------------------------------------------------------------

    @Test
    fun nullOrPlainLyrics_buildNothing() {
        assertNull(PreparedLyricsBuilder.build(null))
        assertNull(PreparedLyricsBuilder.build(Lyrics(plain = listOf("a", "b"))))
        assertNull(PreparedLyricsBuilder.build(Lyrics(synced = emptyList())))
    }

    @Test
    fun linesAreSortedAndStartsMirrored() {
        val p = build(
            SyncedLine(5_000, "third"),
            SyncedLine(1_000, "first"),
            SyncedLine(3_000, "second"),
        )
        assertEquals(listOf("first", "second", "third"), p.lines.map { it.text })
        assertEquals(listOf(0, 1, 2), p.lines.map { it.index })
        assertArrayEquals(longArrayOf(1_000, 3_000, 5_000), p.startsSorted)
        assertEquals(listOf(0, 1, 2), p.lineRows())
        assertFalse(p.hasWordTiming)
        assertFalse(p.hasDuet)
    }

    // ---- end inference -----------------------------------------------------------------------

    @Test
    fun inferredEnds_useNextLeadStart_andLastLineGetsAtLeastFourSeconds() {
        val p = build(
            SyncedLine(1_000, "a b"),
            SyncedLine(3_000, "c"),
            SyncedLine(5_000, "d"),
        )
        assertEquals(3_000L, p.lines[0].endMs)
        assertEquals(5_000L, p.lines[1].endMs)
        // Last line: start + max(4000, clamp(1×450+800, 1500, 6000)) = 5000 + 4000.
        assertEquals(9_000L, p.lines[2].endMs)
        assertTrue(p.lines.none { it.endIsExplicit })
        assertEquals(4_000L, p.maxLineDurationMs)
    }

    @Test
    fun explicitLineEnd_isKept() {
        val p = build(
            SyncedLine(1_000, "hello", endTime = 2_500),
            SyncedLine(3_000, "world"),
        )
        assertEquals(2_500L, p.lines[0].endMs)
        assertTrue(p.lines[0].endIsExplicit)
    }

    @Test
    fun inferredEnd_extendsPastNextLineWhenLastWordStartsLater() {
        val p = build(
            SyncedLine(
                1_000, "Hello world",
                words = listOf(SyncedWord(1_000, "Hello"), SyncedWord(2_500, "world")),
            ),
            SyncedLine(2_000, "next"),
        )
        assertEquals(2_501L, p.lines[0].endMs)
    }

    @Test
    fun inferredEnd_skipsBackgroundLinesWhenLookingForTheNextLead() {
        val p = build(
            SyncedLine(1_000, "lead one"),
            SyncedLine(1_500, "ooh", voiceRole = "background"),
            SyncedLine(4_000, "lead two"),
        )
        assertEquals(4_000L, p.lines[0].endMs)
    }

    @Test
    fun emptyLrcLine_isTheExplicitEndOfThePreviousLine() {
        val p = build(
            SyncedLine(1_000, "hello"),
            SyncedLine(4_000, ""),
            SyncedLine(6_000, "next"),
        )
        assertEquals(2, p.lines.size)
        assertEquals(4_000L, p.lines[0].endMs)
        assertTrue(p.lines[0].endIsExplicit)
        assertFalse(p.lines[1].endIsExplicit)
    }

    // ---- interludes --------------------------------------------------------------------------

    @Test
    fun intro_ofNineSecondsOrMore_getsAnInterludeRowFirst() {
        val p = build(SyncedLine(12_000, "late start"), SyncedLine(14_000, "then"))
        val first = p.rows.first()
        assertTrue(first is Row.Interlude)
        first as Row.Interlude
        assertEquals(0L, first.startMs)
        assertEquals(12_000L, first.endMs)
        assertFalse(first.alignEnd)
        assertEquals(listOf(0, 1), p.lineRows())
    }

    @Test
    fun intro_underNineSeconds_hasNoInterlude() {
        val p = build(SyncedLine(8_000, "soon"), SyncedLine(10_000, "then"))
        assertTrue(p.rows.none { it is Row.Interlude })
    }

    @Test
    fun emptyLrcLine_opensAnInterludeWhenTheGapIsLongEnough() {
        val p = build(
            SyncedLine(1_000, "hello"),
            SyncedLine(4_000, ""),
            SyncedLine(20_000, "next"),
        )
        assertEquals(3, p.rows.size)
        assertEquals(Row.Line(0), p.rows[0])
        assertEquals(Row.Interlude(4_000L, 20_000L, alignEnd = false), p.rows[1])
        assertEquals(Row.Line(1), p.rows[2])
    }

    @Test
    fun interludeAfterAnInferredEnd_usesTheEstimatedEnd_andClampsTheLine() {
        val p = build(
            SyncedLine(1_000, "one two three"),
            SyncedLine(30_000, "after the solo"),
        )
        // estimated = 1000 + clamp(3×450 + 800 = 2150, 1500, 6000) = 3150
        val interlude = p.rows[1] as Row.Interlude
        assertEquals(3_150L, interlude.startMs)
        assertEquals(30_000L, interlude.endMs)
        assertEquals(3_150L, p.lines[0].endMs)
        assertFalse(p.lines[0].endIsExplicit)
    }

    @Test
    fun shortGap_hasNoInterlude() {
        val p = build(
            SyncedLine(1_000, "a", endTime = 2_000),
            SyncedLine(10_500, "b"),
        )
        assertTrue(p.rows.none { it is Row.Interlude })
    }

    @Test
    fun interludeBeforeADuetLine_isRightAligned() {
        val p = build(
            SyncedLine(1_000, "lead", endTime = 2_000),
            SyncedLine(20_000, "duet", voiceRole = "duet"),
        )
        val interlude = p.rows.filterIsInstance<Row.Interlude>().single()
        assertTrue(interlude.alignEnd)
        assertTrue(p.hasDuet)
    }

    // ---- word timing -------------------------------------------------------------------------

    @Test
    fun syllables_indexIntoTheLineText_andMergeIntoWords() {
        val p = build(
            SyncedLine(
                1_000, "Hello world",
                words = listOf(
                    SyncedWord(1_000, "Hel", startsNewWord = true),
                    SyncedWord(1_600, "lo", startsNewWord = false),
                    SyncedWord(2_000, "world", startsNewWord = true),
                ),
            ),
            SyncedLine(6_000, "next"),
        )
        val line = p.lines[0]
        val syl = line.syllables!!
        assertEquals("Hello world", line.text)
        assertEquals(listOf("Hel", "lo", "world"), syl.map { line.text.substring(it.charStart, it.charEnd) })
        assertEquals(listOf(0, 0, 1), syl.map { it.wordIndex })
        assertEquals(1, line.lastWordIndex)
        // Inner ends = next start; last = min(lineEnd, start + 1200).
        assertEquals(listOf(1_600L, 2_000L, 3_200L), syl.map { it.endMs })
        assertTrue(syl.none { it.endIsExplicit })
        assertTrue(p.hasWordTiming)
    }

    @Test
    fun wordsThatDoNotMatchTheText_rebuildTheText() {
        val p = build(
            SyncedLine(1_000, "Totally different", words = listOf(SyncedWord(1_000, "Howdy"), SyncedWord(1_500, "there"))),
        )
        val line = p.lines[0]
        assertEquals("Howdy there", line.text)
        assertEquals(listOf("Howdy", "there"), line.syllables!!.map { line.text.substring(it.charStart, it.charEnd) })
    }

    @Test
    fun leadingVoiceTag_isStripped() {
        val p = build(SyncedLine(1_000, "v1: Hello"))
        assertEquals("Hello", p.lines[0].text)
    }

    // ---- emphasis ----------------------------------------------------------------------------

    @Test
    fun emphasis_onlyOnExplicitEnds() {
        // Enhanced-LRC style: ends are inferred, so nothing may glow however long the word is.
        val inferred = build(
            SyncedLine(1_000, "Hold on", words = listOf(SyncedWord(1_000, "Hold"), SyncedWord(4_000, "on"))),
            SyncedLine(9_000, "next"),
        )
        assertTrue(inferred.lines[0].syllables!!.none { it.emphasis })

        val explicit = build(
            SyncedLine(
                1_000, "Hello world",
                words = listOf(
                    SyncedWord(1_000, "Hello", endTime = 2_500),
                    SyncedWord(2_600, "world", endTime = 2_900),
                ),
            ),
        )
        val syl = explicit.lines[0].syllables!!
        assertTrue("1.5 s, 5 graphemes", syl[0].emphasis)
        assertFalse("300 ms is too short", syl[1].emphasis)
        assertTrue(syl.all { it.endIsExplicit })
    }

    @Test
    fun emphasis_graphemeLengthRule_andCjkException() {
        val p = build(
            SyncedLine(
                1_000, "extraordinary 爱 a",
                words = listOf(
                    SyncedWord(1_000, "extraordinary", endTime = 3_000),
                    SyncedWord(3_000, "爱", endTime = 4_500),
                    SyncedWord(4_500, "a", endTime = 6_000),
                ),
            ),
        )
        val syl = p.lines[0].syllables!!
        assertFalse("13 graphemes", syl[0].emphasis)
        assertTrue("CJK only needs the duration", syl[1].emphasis)
        assertFalse("1 grapheme", syl[2].emphasis)
    }

    @Test
    fun emphasis_testsMergedSyllablesAsOneWord() {
        val p = build(
            SyncedLine(
                1_000, "sugar",
                words = listOf(
                    SyncedWord(1_000, "su", startsNewWord = true, endTime = 1_500),
                    SyncedWord(1_500, "gar", startsNewWord = false, endTime = 2_200),
                ),
            ),
        )
        val syl = p.lines[0].syllables!!
        assertEquals(listOf(0, 0), syl.map { it.wordIndex })
        assertTrue("su + gar = 1.2 s", syl.all { it.emphasis })
    }

    // ---- LyricsDoc path: voices, grouping ------------------------------------------------------

    private val doc = LyricsDoc(
        voices = listOf(Voice("v1", "lead"), Voice("v2", "background"), Voice("v3", "duet")),
        lines = listOf(
            TimedLine(900, 2_000, "ooh", voiceId = "v2"),
            TimedLine(
                1_000, 4_000, "Hello world", voiceId = "v1",
                syllables = listOf(TimedSyllable(1_000, 1_500, "Hello "), TimedSyllable(2_600, 1_400, "world")),
            ),
            TimedLine(3_000, 3_800, "yeah", voiceId = "v2"),
            TimedLine(5_000, 7_000, "hi there", voiceId = "v3"),
            TimedLine(40_000, 41_000, "lonely echo", voiceId = "v2"),
        ),
    )

    @Test
    fun doc_backgroundVocalsGroupUnderTheirLead_aboveOrBelow() {
        val p = PreparedLyricsBuilder.build(Lyrics(document = doc))!!
        assertEquals(listOf("ooh", "Hello world", "yeah", "hi there", "lonely echo"), p.lines.map { it.text })

        val ooh = p.lines[0]
        val lead = p.lines[1]
        val yeah = p.lines[2]
        val duet = p.lines[3]
        val orphan = p.lines[4]
        assertEquals(VoiceRole.BACKGROUND, ooh.role)
        assertEquals(1, ooh.groupLeadIndex)
        assertTrue("starts before its lead", ooh.bgAbove)
        assertEquals(1, yeah.groupLeadIndex)
        assertFalse(yeah.bgAbove)
        assertTrue(lead.isGroupLead)
        assertEquals(VoiceRole.DUET, duet.role)
        assertTrue(duet.isGroupLead)
        assertTrue("no lead near it", orphan.isGroupLead)
        assertFalse(orphan.isGroupedBackground)

        // ooh above the lead, yeah below; an interlude before the orphan (7000 → 40000).
        assertEquals(
            listOf(Row.Line(0), Row.Line(1), Row.Line(2), Row.Line(3), Row.Interlude(7_000, 40_000, false), Row.Line(4)),
            p.rows.toList(),
        )
        assertTrue(p.hasDuet)
        assertTrue(p.lines.all { it.endIsExplicit })
    }

    @Test
    fun doc_syllableRangesExcludeWhitespace_andDurationsAreExplicit() {
        val p = PreparedLyricsBuilder.build(Lyrics(document = doc))!!
        val lead = p.lines[1]
        val syl = lead.syllables!!
        assertEquals(listOf("Hello", "world"), syl.map { lead.text.substring(it.charStart, it.charEnd) })
        assertEquals(listOf(0, 1), syl.map { it.wordIndex })
        assertEquals(listOf(2_500L, 4_000L), syl.map { it.endMs })
        assertTrue(syl.all { it.endIsExplicit })
        assertTrue("1.5 s and 1.4 s with explicit ends", syl.all { it.emphasis })
    }

    @Test
    fun everyLineAppearsInRowsExactlyOnce() {
        val p = PreparedLyricsBuilder.build(Lyrics(document = doc))!!
        assertEquals(p.lines.indices.toList(), p.lineRows().sorted())
    }

    @Test
    fun docWinsOverSynced_butSyncedTranslationsAreKept() {
        val lyrics = Lyrics(
            synced = listOf(SyncedLine(1_000, "Hello world", translation = "Hola mundo")),
            document = doc,
        )
        val p = PreparedLyricsBuilder.build(lyrics)!!
        assertEquals(5, p.lines.size)
        assertEquals("Hola mundo", p.lines[1].translation)
    }

    @Test
    fun equalModels_areEqual() {
        val a = PreparedLyricsBuilder.build(Lyrics(document = doc))
        val b = PreparedLyricsBuilder.build(Lyrics(document = doc))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun graphemeAndWordCounts() {
        assertEquals(5, PreparedLyricsBuilder.graphemeCount("hello"))
        assertEquals(2, PreparedLyricsBuilder.graphemeCount("éa"))
        assertEquals(3, PreparedLyricsBuilder.estimateWordCount("one two  three"))
        assertEquals(3, PreparedLyricsBuilder.estimateWordCount("我爱你"))
    }
}

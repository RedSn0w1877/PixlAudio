package com.theveloper.pixelplay.data.lyrics.sync

import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.LyricsMetadata
import com.theveloper.pixelplay.data.model.TimedLine
import com.theveloper.pixelplay.data.model.TimedSyllable
import com.theveloper.pixelplay.data.model.Voice
import com.theveloper.pixelplay.utils.LyricsUtils
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource

class LyricsExportTest {

    private val ttmlNs = "http://www.w3.org/ns/ttml"
    private val ttmlMetadataNs = "http://www.w3.org/ns/ttml#metadata"

    private val doc = LyricsDoc(
        metadata = LyricsMetadata("Song", "Artist", "Album", 200_000L, "user"),
        lines = listOf(
            TimedLine(
                1_234L, 3_000L, "Hello there, world", "lead",
                listOf(TimedSyllable(1_234L, 400L, "Hello "), TimedSyllable(1_634L, 500L, "there, "), TimedSyllable(2_134L, 866L, "world")),
            ),
            TimedLine(
                4_005L, 6_000L, "beautiful day", "lead",
                listOf(
                    TimedSyllable(4_005L, 300L, "beau"), TimedSyllable(4_305L, 300L, "ti"),
                    TimedSyllable(4_605L, 400L, "ful "), TimedSyllable(5_005L, 995L, "day"),
                ),
            ),
        ),
    )

    // ── LRC ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun lrc_hasHeadersWordTagsAndClosingTag() {
        val lines = LyricsExport.toEnhancedLrc(doc).lines().filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "[ti:Song]",
                "[ar:Artist]",
                "[al:Album]",
                "[length:03:20]",
                "[by:PixlAudio]",
                "[00:01.23]<00:01.23>Hello <00:01.63>there, <00:02.13>world<00:03.00>",
                "[00:04.01]<00:04.01>beau<00:04.31>ti<00:04.61>ful <00:05.01>day<00:06.00>",
            ),
            lines,
        )
    }

    @Test
    fun lrc_timeFormatRoundsTo10msAndGrowsMinutes() {
        assertEquals("00:01.24", LyricsExport.lrcTime(1_235L))
        assertEquals("00:01.23", LyricsExport.lrcTime(1_234L))
        assertEquals("01:00.00", LyricsExport.lrcTime(59_995L))
        assertEquals("100:00.00", LyricsExport.lrcTime(6_000_000L))
        assertEquals("00:00.00", LyricsExport.lrcTime(-5L))
    }

    @Test
    fun lrc_skipsEmptyHeadersAndFlattensNewlines() {
        val bare = LyricsDoc(
            metadata = LyricsMetadata(title = "Two\nlines"),
            lines = listOf(TimedLine(0L, 1_000L, "plain words")),
        )
        assertEquals(listOf("[ti:Two lines]", "[by:PixlAudio]", "[00:00.00]plain words"), LyricsExport.toEnhancedLrc(bare).lines().filter { it.isNotEmpty() })
    }

    @Test
    fun lrc_roundTripsThroughTheAppParser() {
        assertRoundTrip(doc)
    }

    @Test
    fun lrc_roundTripsTappedDrafts() {
        for (seed in 0 until 40) {
            val random = Random(seed)
            val words = listOf("love", "you", "twenty-one", "rock'n'roll", "(oh", "yeah,", "night", "—", "don't", "stay")
            val text = List(random.nextInt(1, 8)) { List(random.nextInt(1, 7)) { words.random(random) }.joinToString(" ") }
            var draft = LyricsTapSync.buildDraft("id", "Title", "Artist", "", 300_000L, null, text.joinToString("\n")).draft!!
            var t = random.nextLong(0L, 3_000L)
            while (!draft.isFinished) {
                t += random.nextLong(30L, 1_500L)
                draft = LyricsTapSync.tap(draft, t, listOf(0.5f, 1f).random(random), 120).draft
            }
            assertRoundTrip(LyricsTapSync.toLyricsDoc(draft, 120).getOrThrow())
        }
    }

    private fun assertRoundTrip(source: LyricsDoc) {
        val parsed = requireNotNull(LyricsUtils.parseLyrics(LyricsExport.toEnhancedLrc(source)).synced)
        assertEquals(source.lines.size, parsed.size)
        source.lines.zip(parsed).forEach { (expected, actual) ->
            assertEquals(expected.text, actual.line)
            assertTrue(abs(expected.startMs - actual.time) <= 10, "line start ${expected.startMs} vs ${actual.time}")
            val words = requireNotNull(actual.words)
            assertEquals(expected.syllables.map { it.text.trim() }, words.map { it.word })
            expected.syllables.zip(words).forEach { (syllable, word) ->
                assertTrue(abs(syllable.startMs - word.time) <= 10, "word start ${syllable.startMs} vs ${word.time}")
            }
        }
    }

    // ── TTML ─────────────────────────────────────────────────────────────────────────────────

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(InputSource(StringReader(xml)))

    private val clock = Regex("^\\d+:\\d{2}:\\d{2}\\.\\d{3}$")

    @Test
    fun ttml_isWellFormedAndEscaped() {
        val duet = LyricsDoc(
            metadata = LyricsMetadata("A & B", "", "", 200_000L, "user"),
            voices = listOf(Voice("lead", "lead"), Voice("v2", "duet"), Voice("bg", "background")),
            lines = listOf(
                TimedLine(
                    1_000L, 2_500L, "R&B <3 'yes\"", "lead",
                    listOf(TimedSyllable(1_000L, 400L, "R&B "), TimedSyllable(1_500L, 400L, "<3 "), TimedSyllable(2_000L, 400L, "'yes\"")),
                ),
                TimedLine(1_200L, 2_400L, "(ooh)", "bg", listOf(TimedSyllable(1_200L, 1_200L, "(ooh)"))),
                TimedLine(3_000L, 4_000L, "Hi there", "v2", listOf(TimedSyllable(3_000L, 400L, "Hi "), TimedSyllable(3_400L, 600L, "there"))),
            ),
        )
        val ttml = LyricsExport.toTtml(duet)
        val xml = parseXml(ttml)
        val root = xml.documentElement
        assertEquals("tt", root.localName)
        assertEquals(ttmlNs, root.namespaceURI)
        assertEquals("Word", root.getAttributeNS("http://music.apple.com/lyric-ttml-internal", "timing"))

        val body = xml.getElementsByTagNameNS(ttmlNs, "body").item(0) as Element
        assertEquals("0:03:20.000", body.getAttribute("dur"))

        val paragraphs = xml.getElementsByTagNameNS(ttmlNs, "p")
        assertEquals(2, paragraphs.length) // the background line is nested, not its own <p>
        val first = paragraphs.item(0) as Element
        val second = paragraphs.item(1) as Element
        assertEquals("v1", first.getAttributeNS(ttmlMetadataNs, "agent"))
        assertEquals("v2", second.getAttributeNS(ttmlMetadataNs, "agent"))
        assertEquals("0:00:01.000", first.getAttribute("begin"))
        assertEquals("0:00:02.500", first.getAttribute("end"))
        assertEquals("R&B <3 'yes\" (ooh)", first.textContent)
        assertEquals("Hi there", second.textContent)

        val spans = first.getElementsByTagNameNS(ttmlNs, "span")
        assertEquals(5, spans.length)
        assertEquals(listOf("R&B", "<3", "'yes\""), (0 until 3).map { spans.item(it).textContent })
        val background = (0 until spans.length).map { spans.item(it) as Element }
            .single { it.getAttributeNS(ttmlMetadataNs, "role") == "x-bg" }
        assertEquals("(ooh)", background.textContent)
        assertEquals("0:00:01.200", background.getAttribute("begin"))
        assertEquals("0:00:02.400", background.getAttribute("end"))

        val allSpans = xml.getElementsByTagNameNS(ttmlNs, "span")
        for (i in 0 until allSpans.length) {
            val span = allSpans.item(i) as Element
            assertTrue(clock.matches(span.getAttribute("begin")), span.getAttribute("begin"))
            assertTrue(clock.matches(span.getAttribute("end")), span.getAttribute("end"))
        }
        assertFalse(ttml.contains("R&B <"), "raw markup leaked into the file")
        val agents = xml.getElementsByTagNameNS(ttmlMetadataNs, "agent")
        assertEquals(listOf("v1", "v2"), (0 until agents.length).map { (agents.item(it) as Element).getAttribute("xml:id") })
    }

    @Test
    fun ttml_syllablesOfOneWordSitFlushAndTimesUseClockFormat() {
        val ttml = LyricsExport.toTtml(doc)
        assertTrue(ttml.contains("<span begin=\"0:00:04.005\" end=\"0:00:04.305\">beau</span><span begin=\"0:00:04.305\""))
        assertTrue(ttml.contains("ful</span> <span begin=\"0:00:05.005\" end=\"0:00:06.000\">day</span></p>"))
        assertEquals("1:02:03.004", LyricsExport.ttmlTime(3_723_004L))
        val xml = parseXml(ttml)
        assertEquals("beautiful day", xml.getElementsByTagNameNS(ttmlNs, "p").item(1).textContent)
    }

    @Test
    fun ttml_dropsCharactersXmlCannotCarryAndOmitsUnknownDuration() {
        val odd = LyricsDoc(
            metadata = LyricsMetadata(),
            lines = listOf(TimedLine(0L, 500L, "a\u0001b", "lead", listOf(TimedSyllable(0L, 500L, "a\u0001b")))),
        )
        val ttml = LyricsExport.toTtml(odd)
        val xml = parseXml(ttml)
        assertEquals("ab", xml.getElementsByTagNameNS(ttmlNs, "p").item(0).textContent)
        val body = xml.getElementsByTagNameNS(ttmlNs, "body").item(0) as Element
        assertFalse(body.hasAttribute("dur"))
    }

    @Test
    fun ttml_readsBackThroughTheAppParser() {
        val parsed = assertNotNull(LyricsUtils.parseLyrics(LyricsExport.toTtml(doc)).synced)
        assertEquals(listOf("Hello there, world", "beautiful day"), parsed.map { it.line })
        doc.lines.zip(parsed).forEach { (expected, actual) ->
            assertTrue(abs(expected.startMs - actual.time) <= 10, "line start ${expected.startMs} vs ${actual.time}")
        }
    }
}

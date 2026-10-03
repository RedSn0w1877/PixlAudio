package com.theveloper.pixelplay.utils

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.LyricsDocCodec
import com.theveloper.pixelplay.data.model.LyricsMetadata
import org.junit.jupiter.api.Test

/** All lyric text in these fixtures is made up for the tests. */
class AppleTtmlParserTest {

    private val head = """<head><metadata>
        <ttm:agent type="person" xml:id="v1"><ttm:name type="full">Singer One</ttm:name></ttm:agent>
        <ttm:agent type="person" xml:id="v2"/>
        <ttm:agent type="group" xml:id="v3"/>
        <sourceMetadata xmlns="http://lrc.red/lyric-ttml-internal" leadingSilence="0"><translations/>
        <songwriters><songwriter>Ada Writer</songwriter><songwriter>Bo Composer</songwriter></songwriters>
        <audio lyricOffset="1.115" role="spatial"/></sourceMetadata></metadata></head>"""

    private fun tt(timing: String, body: String) =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" """ +
            """xmlns:ttm="http://www.w3.org/ns/ttml#metadata" lrc:timing="$timing" xml:lang="en">$head$body</tt>"""

    private val wordTimed = tt("Word", """<body dur="3:21.570"><div begin="1.000" end="9.000" lrc:songPart="Verse">""" +
        """<p begin="1.000" end="3.000" lrc:key="L1" ttm:agent="v1"><span begin="1.000" end="1.400">Paper</span> """ +
        """<span begin="1.400" end="1.700">lan</span><span begin="1.700" end="2.100">terns</span> """ +
        """<span begin="2.100" end="3.000">drift</span><span ttm:role="x-bg"><span begin="2.500" end="2.900">(drift</span> """ +
        """<span begin="2.900" end="3.400">away)</span></span></p>""" +
        """<p begin="4.000" end="6.000" lrc:key="L2" ttm:agent="v2"><span begin="4.000" end="5.000">Second</span> """ +
        """<span begin="5.000" end="6.000">voice</span></p>""" +
        """<p begin="7.000" end="9.000" lrc:key="L3" ttm:agent="v3"><span begin="7.000" end="9.000">Together</span></p>""" +
        """</div></body>""")

    @Test
    fun wordTiming_mergesSyllablesWithoutSpaceIntoOneWord() {
        val result = AppleTtmlParser.parse(wordTimed, LyricsMetadata(source = "BiniLyrics"))!!
        val doc = result.document!!
        assertThat(LyricsDocCodec.isValid(doc)).isTrue()
        assertThat(result.wordTimed).isTrue()

        val first = doc.lines.first()
        assertThat(first.text).isEqualTo("Paper lanterns drift")
        assertThat(first.syllables.map { it.text }).containsExactly("Paper ", "lan", "terns ", "drift").inOrder()
        assertThat(first.syllables.map { it.startMs }).containsExactly(1000L, 1400L, 1700L, 2100L).inOrder()
        assertThat(first.syllables[1].durationMs).isEqualTo(300L)

        val words = result.toLyrics().synced!!.first().words!!
        assertThat(words.map { it.word }).containsExactly("Paper", "lan", "terns", "drift").inOrder()
        assertThat(words.map { it.startsNewWord }).containsExactly(true, true, false, true).inOrder()
        assertThat(words[2].endTime).isEqualTo(2100)
        assertThat(doc.metadata.source).isEqualTo("BiniLyrics")
    }

    @Test
    fun backgroundVocals_becomeTheirOwnTimedBackgroundLine() {
        val doc = AppleTtmlParser.parse(wordTimed)!!.document!!
        val bg = doc.lines.single { it.voiceId == "background" }
        assertThat(bg.text).isEqualTo("(drift away)")
        assertThat(bg.startMs).isEqualTo(2500L)
        assertThat(bg.endMs).isEqualTo(3400L)
        assertThat(doc.voices.single { it.id == "background" }.role).isEqualTo("background")
        // The lead line keeps only its own words.
        assertThat(doc.lines.first().text).doesNotContain("away")
        val synced = doc.toLyrics().synced!!
        assertThat(synced.map { it.voiceRole }).containsExactly("lead", "background", "duet", "lead").inOrder()
    }

    @Test
    fun agents_firstPersonLeadsOtherPersonsAreDuetGroupsLead() {
        val doc = AppleTtmlParser.parse(wordTimed)!!.document!!
        assertThat(doc.lines.single { it.text == "Second voice" }.voiceId).isEqualTo("duet")
        assertThat(doc.lines.single { it.text == "Together" }.voiceId).isEqualTo("lead")
        assertThat(doc.voices.map { it.id }).containsExactly("lead", "duet", "background").inOrder()
    }

    @Test
    fun songwritersAreReturnedAndSpatialOffsetIsIgnored() {
        val result = AppleTtmlParser.parse(wordTimed)!!
        assertThat(result.songwriters).containsExactly("Ada Writer", "Bo Composer").inOrder()
        // lyricOffset="1.115" must not shift anything.
        assertThat(result.document!!.lines.first().startMs).isEqualTo(1000L)
    }

    @Test
    fun lineTimedFile_keepsLinesAndInfersMissingEnds() {
        val ttml = tt("Line", """<body><div>""" +
            """<p begin="00:10.000" end="00:12.500" ttm:agent="v1">Quiet harbor lights</p>""" +
            """<p begin="00:13.000" ttm:agent="v2">Answer from the shore <span ttm:role="x-bg">(shore)</span></p>""" +
            """<p begin="00:16.250" end="00:18.000" ttm:agent="v1">Morning comes</p>""" +
            """</div></body>""")
        val result = AppleTtmlParser.parse(ttml)!!
        val doc = result.document!!
        assertThat(LyricsDocCodec.isValid(doc)).isTrue()
        assertThat(result.wordTimed).isFalse()
        assertThat(doc.lines.all { it.syllables.isEmpty() }).isTrue()
        assertThat(doc.lines.map { it.text })
            .containsExactly("Quiet harbor lights", "Answer from the shore", "(shore)", "Morning comes").inOrder()
        val answer = doc.lines[1]
        assertThat(answer.voiceId).isEqualTo("duet")
        assertThat(answer.endMs).isEqualTo(16_250L) // runs to the next sung line
        assertThat(doc.lines[2].voiceId).isEqualTo("background")
        assertThat(doc.lines[2].startMs).isEqualTo(13_000L)
        assertThat(result.toLyrics().synced!!.first().words).isNull()
    }

    @Test
    fun unsyncedFile_isPlainLyrics() {
        val ttml = tt("None", """<body><div><p>First plain line</p><p>Second plain line</p></div></body>""")
        val result = AppleTtmlParser.parse(ttml)!!
        assertThat(result.document).isNull()
        val lyrics = result.toLyrics()
        assertThat(lyrics.plain).containsExactly("First plain line", "Second plain line").inOrder()
        assertThat(lyrics.synced).isNull()
    }

    @Test
    fun timeExpressions_allFormats() {
        assertThat(AppleTtmlParser.parseTime("27.395")).isEqualTo(27_395L)
        assertThat(AppleTtmlParser.parseTime("27.395s")).isEqualTo(27_395L)
        assertThat(AppleTtmlParser.parseTime("27")).isEqualTo(27_000L)
        assertThat(AppleTtmlParser.parseTime("1500ms")).isEqualTo(1_500L)
        assertThat(AppleTtmlParser.parseTime("0.5m")).isEqualTo(30_000L)
        assertThat(AppleTtmlParser.parseTime("1h")).isEqualTo(3_600_000L)
        assertThat(AppleTtmlParser.parseTime("3:21.570")).isEqualTo(201_570L)
        assertThat(AppleTtmlParser.parseTime("03:21.5")).isEqualTo(201_500L)
        assertThat(AppleTtmlParser.parseTime("1:02:03.456")).isEqualTo(3_723_456L)
        assertThat(AppleTtmlParser.parseTime("00:00:01.0004")).isEqualTo(1_000L)
        assertThat(AppleTtmlParser.parseTime(" 2.5 ")).isEqualTo(2_500L)
        for (bad in listOf(null, "", "abc", "1:75.0", "1:60:00", "12:00:00:10", "-1", "10f", "999999999")) {
            assertThat(AppleTtmlParser.parseTime(bad)).isNull()
        }
    }

    @Test
    fun hoursFormatInsideDocument() {
        val ttml = tt("Word", """<body><div><p begin="1:00:00.000" end="1:00:02.000">""" +
            """<span begin="1:00:00.000" end="1:00:01.000">Long</span> <span begin="1:00:01.000" end="1:00:02.000">set</span></p></div></body>""")
        val line = AppleTtmlParser.parse(ttml)!!.document!!.lines.single()
        assertThat(line.startMs).isEqualTo(3_600_000L)
        assertThat(line.syllables.last().startMs).isEqualTo(3_601_000L)
    }

    @Test
    fun documentTypeDeclarationsAreRefused() {
        val withEntity = """<?xml version="1.0"?><!DOCTYPE tt [<!ENTITY boom "x">]>""" +
            tt("Line", """<body><div><p begin="1" end="2">&boom;</p></div></body>""")
        assertThat(AppleTtmlParser.parse(withEntity)).isNull()
        val external = """<!DOCTYPE tt SYSTEM "file:///etc/passwd">""" +
            tt("Line", """<body><div><p begin="1" end="2">Hi</p></div></body>""")
        assertThat(AppleTtmlParser.parse(external)).isNull()
        assertThat(TtmlLyricsParser.parseToEnhancedLrc(external)).isNull()
    }

    @Test
    fun garbageAndNonTtmlAreNull() {
        assertThat(AppleTtmlParser.parse("not xml at all")).isNull()
        assertThat(AppleTtmlParser.parse("<html><body><p>Hi</p></body></html>")).isNull()
        assertThat(AppleTtmlParser.parse(tt("Word", "<body><div></div></body>"))).isNull()
    }

    @Test
    fun untimedPunctuationJoinsItsWord() {
        val ttml = tt("Word", """<body><div><p begin="1" end="3"><span begin="1" end="2">Wait</span>, """ +
            """<span begin="2" end="3">now</span>!</p></div></body>""")
        val line = AppleTtmlParser.parse(ttml)!!.document!!.lines.single()
        assertThat(line.text).isEqualTo("Wait, now!")
        assertThat(line.syllables.map { it.text }).containsExactly("Wait, ", "now!").inOrder()
    }
}

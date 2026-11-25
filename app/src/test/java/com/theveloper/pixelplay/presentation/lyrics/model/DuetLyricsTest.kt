package com.theveloper.pixelplay.presentation.lyrics.model

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.TimedLine
import com.theveloper.pixelplay.data.model.Voice
import com.theveloper.pixelplay.utils.AppleTtmlParser
import org.junit.jupiter.api.Test

/**
 * Duet songs end to end: Apple-shaped TTML → [AppleTtmlParser] → [PreparedLyricsBuilder], and the
 * side every line lands on. All lyric text is made up for the tests.
 */
class DuetLyricsTest {

    private fun tt(head: String, body: String) =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" """ +
            """xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word" xml:lang="en">""" +
            """<head><metadata>$head</metadata></head><body><div>$body</div></body></tt>"""

    private val agents = """<ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/>""" +
        """<ttm:agent type="group" xml:id="v1000"/>"""

    private val duetBody =
        // Singer one, with a background echo that starts while singer two's line is already playing.
        """<p begin="1.000" end="4.000" ttm:agent="v1"><span begin="1.000" end="1.500">I</span> """ +
            """<span begin="1.500" end="2.000">said</span> <span begin="2.000" end="2.800">hello</span>""" +
            """<span ttm:role="x-bg"><span begin="3.000" end="3.800">(hello)</span></span></p>""" +
            // Singer two overlaps singer one.
            """<p begin="2.500" end="5.000" ttm:agent="v2"><span begin="2.500" end="3.000">You</span> """ +
            """<span begin="3.000" end="3.500">said</span> <span begin="3.500" end="5.000">goodbye</span></p>""" +
            // Singer two with their own background vocal.
            """<p begin="6.000" end="8.000" ttm:agent="v2"><span begin="6.000" end="6.500">Say</span> """ +
            """<span begin="6.500" end="7.000">it</span> <span begin="7.000" end="8.000">back</span>""" +
            """<span ttm:role="x-bg"><span begin="7.000" end="7.500">(back)</span></span></p>""" +
            // Both, after a long instrumental.
            """<p begin="20.000" end="22.000" ttm:agent="v1000"><span begin="20.000" end="22.000">Together</span></p>""" +
            // Singer two again, after another long gap.
            """<p begin="35.000" end="37.000" ttm:agent="v2"><span begin="35.000" end="37.000">Alone</span></p>"""

    private fun prepared(ttml: String): PreparedLyrics {
        val lyrics = AppleTtmlParser.parse(ttml)!!.toLyrics()
        return PreparedLyricsBuilder.build(lyrics)!!
    }

    private fun PreparedLyrics.line(text: String) = lines.single { it.text == text }

    @Test
    fun everyLineLandsOnItsSingersSide() {
        val p = prepared(tt(agents, duetBody))
        assertThat(p.hasDuet).isTrue()
        val sides = p.lines.associate { it.text to (if (it.duetEnd) "right" else "left") }
        assertThat(sides).containsExactly(
            "I said hello", "left",
            "(hello)", "left",
            "You said goodbye", "right",
            "Say it back", "right",
            "(back)", "right",
            "Together", "left",
            "Alone", "right",
        )
    }

    @Test
    fun backgroundVocalStaysWithTheLineItWasNestedIn_evenWhenTheOtherSingerOverlaps() {
        val p = prepared(tt(agents, duetBody))
        val echo = p.line("(hello)")
        // By time alone, "You said goodbye" (started 2.5 s, still playing at 3.0 s) would win.
        assertThat(p.lines[echo.groupLeadIndex].text).isEqualTo("I said hello")
        assertThat(p.lines[p.line("(back)").groupLeadIndex].text).isEqualTo("Say it back")
    }

    @Test
    fun interludeDotsSitOnTheSideOfTheGroupTheyLeadInto() {
        val p = prepared(tt(agents, duetBody))
        val interludes = p.rows.filterIsInstance<Row.Interlude>()
        assertThat(interludes.map { it.endMs to it.alignEnd }).containsExactly(20_000L to false, 35_000L to true).inOrder()
    }

    @Test
    fun undeclaredAgents_firstSingerLeads_othersAreDuet_groupIdsLead() {
        val p = prepared(tt("", duetBody))
        assertThat(p.hasDuet).isTrue()
        assertThat(p.line("I said hello").duetEnd).isFalse()
        assertThat(p.line("You said goodbye").duetEnd).isTrue()
        assertThat(p.line("Together").duetEnd).isFalse()
        assertThat(p.line("(back)").duetEnd).isTrue()
    }

    @Test
    fun soloSong_noLineIsOnTheRight_andBackgroundIdsStayPlain() {
        val solo = duetBody.replace("ttm:agent=\"v2\"", "ttm:agent=\"v1\"")
        val result = AppleTtmlParser.parse(tt(agents, solo))!!
        assertThat(result.document!!.voices.map { it.id }).containsExactly("lead", "background").inOrder()
        val p = PreparedLyricsBuilder.build(result.toLyrics())!!
        assertThat(p.hasDuet).isFalse()
        assertThat(p.lines.none { it.duetEnd }).isTrue()
    }

    @Test
    fun lrcDuet_backgroundVocalFollowsTheDuetLineItIsGroupedUnder() {
        val p = PreparedLyricsBuilder.build(
            Lyrics(
                synced = listOf(
                    SyncedLine(1_000, "Lead line", voiceRole = "lead"),
                    SyncedLine(5_000, "Answer line", voiceRole = "duet", endTime = 8_000),
                    SyncedLine(6_000, "(answer)", voiceRole = "background"),
                ),
            ),
        )!!
        assertThat(p.line("(answer)").duetEnd).isTrue()
        assertThat(p.line("Lead line").duetEnd).isFalse()
    }

    @Test
    fun oldDocsWithoutOwnerIds_stillGroupByTime() {
        val doc = LyricsDoc(
            voices = listOf(Voice("lead", "lead"), Voice("duet", "duet"), Voice("background", "background")),
            lines = listOf(
                TimedLine(1_000, 4_000, "One", "lead"),
                TimedLine(2_500, 5_000, "Two", "duet"),
                TimedLine(3_000, 3_800, "(echo)", "background"),
            ),
        )
        val p = PreparedLyricsBuilder.build(doc)!!
        // No owner hint: the latest line already playing wins, and the vocal takes its side.
        assertThat(p.lines[p.line("(echo)").groupLeadIndex].text).isEqualTo("Two")
        assertThat(p.line("(echo)").duetEnd).isTrue()
    }
}

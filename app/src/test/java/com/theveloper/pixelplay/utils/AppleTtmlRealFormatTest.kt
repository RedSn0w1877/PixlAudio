package com.theveloper.pixelplay.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The TTML shape BiniLyrics' file host (lrc.red) serves as of 2026-10-14, recorded from a live
 * response: the `lrc:` extension attributes (`lrc:timing`, `lrc:songPart`, `lrc:key`), the
 * `sourceMetadata` block with songwriters and a spatial-audio `lyricOffset`, `ttm:agent` on every
 * paragraph, syllable spans glued into one word (`Lan` + `terns`) and a time written as `m:ss.mmm`
 * on `<body dur>`. Structure and attributes are real; the words are synthetic.
 */
class AppleTtmlRealFormatTest {

    private val wordTimed = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" """ +
        """xmlns:ttm="http://www.w3.org/ns/ttml#metadata" lrc:timing="Word" xml:lang="en"><head><metadata>""" +
        """<ttm:agent type="person" xml:id="v1"><ttm:name type="full">Vocal 1</ttm:name></ttm:agent>""" +
        """<sourceMetadata xmlns="http://lrc.red/lyric-ttml-internal" leadingSilence="0"><translations/>""" +
        """<songwriters><songwriter>Writer One</songwriter><songwriter>Writer Two</songwriter></songwriters>""" +
        """<audio lyricOffset="1.115" role="spatial"/></sourceMetadata></metadata></head>""" +
        """<body dur="3:21.570"><div begin="27.395" end="48.621" lrc:songPart="Verse">""" +
        """<p begin="27.395" end="28.960" lrc:key="L1" ttm:agent="v1"><span begin="27.395" end="27.549">Paper</span> """ +
        """<span begin="27.549" end="27.740">Lan</span><span begin="27.740" end="28.960">terns</span></p>""" +
        """<p begin="30.189" end="32.529" lrc:key="L2" ttm:agent="v1"><span begin="30.189" end="30.396">glow</span> """ +
        """<span begin="30.396" end="32.529">tonight,</span></p></div></body></tt>"""

    private val lineTimed = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" """ +
        """lrc:timing="Line"><body dur="3:00.000"><div><p begin="12.000" end="15.000" lrc:key="L1">Paper lanterns glow</p>""" +
        """<p begin="16.500" end="19.000" lrc:key="L2">tonight</p></div></body></tt>"""

    private val unsynced = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" """ +
        """lrc:timing="None"><body><div><p>Paper lanterns glow</p><p>tonight</p></div></body></tt>"""

    @Test
    fun wordTimedFile_parsesWordsSyllablesAndSongwriters() {
        val result = AppleTtmlParser.parse(wordTimed)!!

        assertThat(result.wordTimed).isTrue()
        assertThat(result.songwriters).containsExactly("Writer One", "Writer Two").inOrder()
        val lyrics = result.toLyrics()
        val synced = lyrics.synced!!
        assertThat(synced.map { it.line.trim() }).containsExactly("Paper Lanterns", "glow tonight,").inOrder()
        assertThat(synced[0].time).isEqualTo(27_395)
        assertThat(synced[1].time).isEqualTo(30_189)
        assertThat(synced[0].words).isNotEmpty()
    }

    @Test
    fun lineTimedFile_givesTimedLinesWithoutWords() {
        val result = AppleTtmlParser.parse(lineTimed)!!

        assertThat(result.wordTimed).isFalse()
        val synced = result.toLyrics().synced!!
        assertThat(synced.map { it.line }).containsExactly("Paper lanterns glow", "tonight").inOrder()
        assertThat(synced.map { it.time }).containsExactly(12_000, 16_500).inOrder()
    }

    @Test
    fun unsyncedFile_givesPlainLines() {
        val lyrics = AppleTtmlParser.parse(unsynced)!!.toLyrics()

        assertThat(lyrics.synced).isNull()
        assertThat(lyrics.plain).containsExactly("Paper lanterns glow", "tonight").inOrder()
    }

    @Test
    fun clockTimes_areParsed() {
        assertThat(AppleTtmlParser.parseTime("3:21.570")).isEqualTo(201_570)
        assertThat(AppleTtmlParser.parseTime("27.395")).isEqualTo(27_395)
        assertThat(AppleTtmlParser.parseTime("1:02:03.456")).isEqualTo(3_723_456)
    }

    @Test
    fun notTtml_isRejectedNotThrown() {
        assertThat(AppleTtmlParser.parse("<html><body>Cloudflare challenge</body></html>")).isNull()
        assertThat(AppleTtmlParser.parse("")).isNull()
    }
}

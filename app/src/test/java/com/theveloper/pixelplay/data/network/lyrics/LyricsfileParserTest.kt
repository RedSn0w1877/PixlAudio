package com.theveloper.pixelplay.data.network.lyrics

import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LyricsfileParserTest {
    private val yaml = """
        version: "1.0"
        lines:
          - text: No surprises
            start_ms: 13440
            end_ms: 16000
            words:
              - text: "No "
                start_ms: 13440
              - text: surprises
                start_ms: 14000
          - text: On
            start_ms: 18000
            words:
              - text: On
                start_ms: 18000
    """.trimIndent()

    @Test fun `reads absolute word timings and keeps intro and spaces`() {
        val lyrics = LyricsfileParser.parse(yaml)!!
        assertEquals(13440, lyrics.synced!![0].time)
        assertEquals(listOf(13440, 14000), lyrics.synced!![0].words!!.map { it.time })
        assertEquals("No surprises", lyrics.synced!![0].line)
        assertTrue(lyrics.synced!![0].words!![1].startsNewWord)
    }
    @Test fun `yaml boolean-looking lyric words stay text`() {
        assertEquals("On", LyricsfileParser.parse(yaml)!!.synced!![1].words!![0].word)
    }
    @Test fun `unsupported versions and ambiguous offsets are rejected`() {
        assertNull(LyricsfileParser.parse(yaml.replace("1.0", "2.0")))
        assertNull(LyricsfileParser.parse("offset_ms: 200\n$yaml"))
    }
    @Test fun `invalid and reversed timestamps cannot replace good lyrics`() {
        assertNull(LyricsfileParser.parse(yaml.replace("14000", "12000")))
        assertNull(LyricsfileParser.parse(yaml.replace("13440", "-1")))
        assertNull(LyricsfileParser.parse(yaml.replace("13440", ".nan")))
    }
    @Test fun `yaml object tags and duplicate keys are rejected`() {
        assertNull(LyricsfileParser.parse("!!java.lang.ProcessBuilder {}"))
        assertNull(LyricsfileParser.parse("version: 2.0\n$yaml"))
    }
    @Test fun `amll metadata matching rejects alternate editions and missing albums`() {
        val song = Song.emptySong().copy(title="Song", artist="Artist", album="Album")
        assertTrue(AmllLyricsSource.matchesMetadata(song, listOf("SONG"), listOf("Artist"), listOf("Album")))
        assertFalse(AmllLyricsSource.matchesMetadata(song, listOf("Song (Live)"), listOf("Artist"), listOf("Album")))
        assertFalse(AmllLyricsSource.matchesMetadata(song, listOf("Song"), listOf("Another artist"), listOf("Album")))
        assertFalse(AmllLyricsSource.matchesMetadata(song.copy(album=""), listOf("Song"), listOf("Artist"), listOf("")))
    }
    @Test fun `real LRCLIB response structure retains every timed fragment`() {
        val raw = javaClass.getResourceAsStream("/lyrics/lrclib-lyricsfile-structure.yaml")!!.bufferedReader().use { it.readText() }
        val lyrics = LyricsfileParser.parse(raw)!!
        val wordLines = lyrics.synced!!.filter { !it.words.isNullOrEmpty() }
        val words = wordLines.flatMap { it.words!! }
        assertEquals(34, wordLines.size)
        assertEquals(190, words.size)
        assertEquals(16742, words[0].time)
        assertEquals(17179, words[1].time)
    }
}

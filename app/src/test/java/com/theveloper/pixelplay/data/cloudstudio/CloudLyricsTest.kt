package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDocCodec
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudLyricsTest {
    private fun cloud(text: String): CloudLyricsDocument = CloudJson.decodeFromString(CloudLyricsDocument.serializer(), text)

    @Test fun `the worker's aligned example becomes a valid word-timed document`() {
        val doc = CloudLyrics.lyricsDoc(cloud(CloudFixtures.worker("lyrics.aligned.json")), 241_000, "T", "A", "B")!!
        assertTrue(LyricsDocCodec.isValid(doc))
        assertEquals(CloudLyrics.SOURCE, doc.metadata.source)
        assertEquals(241_000L, doc.metadata.durationMs)
        assertEquals(3, doc.lines.size) // the blank fourth line is dropped
        val first = doc.lines[0]
        assertEquals(listOf("별빛 ", "아래 ", "우리 ", "둘이"), first.syllables.map { it.text })
        assertEquals(12_410L, first.syllables[0].startMs)
        // Punctuation the aligner dropped comes back from the original text.
        assertEquals(listOf("다시 ", "노래해, ", "오늘 ", "밤"), doc.lines[1].syllables.map { it.text })
        assertEquals(listOf("(Oh-oh) ", "Stay ", "with ", "me"), doc.lines[2].syllables.map { it.text })
        doc.lines.forEach { line -> assertEquals(line.text, line.syllables.joinToString("") { it.text }) }
        assertEquals(CloudLyrics.Level.WORD_SYNCED, CloudLyrics.level(doc))
        assertTrue(CloudLyrics.isUsable(doc))
        // It survives the store's own codec, and reads back as word-synced lyrics.
        val stored = LyricsDocCodec.decode(LyricsDocCodec.encode(doc))!!
        val lyrics = stored.toLyrics()
        assertEquals(listOf("별빛", "아래", "우리", "둘이"), lyrics.synced!![0].words!!.map { it.word })
        assertEquals(CloudLyrics.Level.WORD_SYNCED, CloudLyrics.level(lyrics))
    }

    @Test fun `transcribed lyrics are line-timed and marked as machine-written`() {
        val doc = CloudLyrics.lyricsDoc(cloud(CloudFixtures.worker("lyrics.transcribed.json")), 198_500)!!
        assertEquals(CloudLyrics.TRANSCRIBED_SOURCE, doc.metadata.source)
        assertEquals(CloudLyrics.Level.LINE_SYNCED, CloudLyrics.level(doc))
        assertTrue(doc.lines.all { it.syllables.isEmpty() })
        assertTrue(LyricsDocCodec.isValid(doc))
        assertTrue(CloudLyrics.isUsable(doc))
    }

    @Test fun `edge cases - surrogate pairs, punctuation and line-timed lines`() {
        val doc = CloudLyrics.lyricsDoc(cloud(CloudFixtures.phone("edge.lyrics.aligned.json")), 30_000)!!
        assertTrue(LyricsDocCodec.isValid(doc))
        assertEquals(listOf("Hello, ", "bright ", "world"), doc.lines[1].syllables.map { it.text })
        assertEquals(listOf("Fly 🚀 ", "high"), doc.lines[2].syllables.map { it.text })
        assertTrue(doc.lines[3].syllables.isEmpty())
        val transcribed = CloudLyrics.lyricsDoc(cloud(CloudFixtures.phone("edge.lyrics.transcribed.json")), 10_000)!!
        assertEquals(CloudLyrics.TRANSCRIBED_SOURCE, transcribed.metadata.source)
        assertEquals(3, transcribed.lines.single().syllables.size)
    }

    private fun oneLine(words: String, start: Long = 1_000, end: Long = 3_000, text: String = "ab cd"): CloudLyricsDocument =
        cloud("""{"schema":"pixl.cloudstudio.lyrics","v":1,"mode":"aligned","lines":[
            {"i":0,"startMs":$start,"endMs":$end,"text":"$text","timing":"word","words":[$words]}]}""")

    @Test fun `bad offsets or times fall back to line timing instead of failing`() {
        // c1 past the end of the text.
        val pastEnd = CloudLyrics.lyricsDoc(oneLine("""{"startMs":1000,"endMs":1500,"text":"ab","c0":0,"c1":9}"""), 10_000)!!
        assertTrue(pastEnd.lines.single().syllables.isEmpty())
        // Overlapping words.
        val overlap = CloudLyrics.lyricsDoc(oneLine(
            """{"startMs":1000,"endMs":1500,"text":"ab","c0":0,"c1":3},{"startMs":1600,"endMs":2000,"text":"cd","c0":2,"c1":5}"""), 10_000)!!
        assertTrue(overlap.lines.single().syllables.isEmpty())
        // A word far outside its line.
        val far = CloudLyrics.lyricsDoc(oneLine(
            """{"startMs":1000,"endMs":1500,"text":"ab","c0":0,"c1":2},{"startMs":9000,"endMs":9500,"text":"cd","c0":3,"c1":5}"""), 20_000)!!
        assertTrue(far.lines.single().syllables.isEmpty())
        // Splitting a surrogate pair.
        val split = CloudLyrics.lyricsDoc(oneLine("""{"startMs":1000,"endMs":1500,"text":"x","c0":0,"c1":1}""", text = "🚀a"), 10_000)!!
        assertTrue(split.lines.single().syllables.isEmpty())
    }

    @Test fun `word ends past the line or the song are clamped to what the store accepts`() {
        // The last word runs past the line end and past the song end; a middle word runs past the last word's end.
        val doc = CloudLyrics.lyricsDoc(oneLine(
            """{"startMs":1000,"endMs":2900,"text":"ab","c0":0,"c1":2},{"startMs":2000,"endMs":4500,"text":"cd","c0":3,"c1":5}""",
            end = 2_500), 4_000)!!
        val line = doc.lines.single()
        assertTrue(LyricsDocCodec.isValid(doc))
        assertEquals(2, line.syllables.size)
        assertEquals(4_000L, line.endMs)
        assertTrue(line.syllables.all { it.startMs + it.durationMs <= line.endMs })
    }

    @Test fun `wrong schema, empty or blank documents give nothing`() {
        assertNull(CloudLyrics.lyricsDoc(CloudLyricsDocument(schema = "other", mode = "aligned"), 1000))
        assertNull(CloudLyrics.lyricsDoc(CloudLyricsDocument(mode = "aligned", lines = emptyList()), 1000))
        assertNull(CloudLyrics.lyricsDoc(CloudLyricsDocument(mode = "aligned",
            lines = listOf(CloudLyricsLine(startMs = 0, endMs = 10, text = "  "))), 1000))
    }

    @Test fun `request lines come from synced lines, else plain text`() {
        val synced = Lyrics(synced = listOf(
            SyncedLine(5_000, "second"),
            SyncedLine(1_000, "first", endTime = 3_000),
            SyncedLine(9_000, " "),
            SyncedLine(7_000, "last"),
        ))
        val request = CloudLyrics.requestLines(synced)!!
        assertTrue(request.hasLineTimes)
        assertEquals(
            listOf(CloudLyricsInputLine(1_000, 3_000, "first"), CloudLyricsInputLine(5_000, 7_000, "second"),
                CloudLyricsInputLine(7_000, null, "last")),
            request.lines
        )
        val plain = CloudLyrics.requestLines(Lyrics(plain = listOf("こんにちは\nkonnichiwa", "", "two")))!!
        assertFalse(plain.hasLineTimes)
        assertEquals(listOf("こんにちは", "two"), plain.lines.map { it.text })
        assertNull(CloudLyrics.requestLines(Lyrics(plain = listOf(" "))))
        assertNull(CloudLyrics.requestLines(null))
        val long = CloudLyrics.capped(List(600) { CloudLyricsInputLine(text = "x".repeat(2_500)) })
        assertEquals(10, long.size) // lines cap at 2,000 characters, and 10 of them fill the 20,000 total
        assertTrue(long.all { it.text.length == 2_000 })
    }

    @Test fun `levels and the import rule`() {
        assertEquals(CloudLyrics.Level.NONE, CloudLyrics.level(null as Lyrics?))
        assertEquals(CloudLyrics.Level.PLAIN, CloudLyrics.level(Lyrics(plain = listOf("a"))))
        assertEquals(CloudLyrics.Level.LINE_SYNCED, CloudLyrics.level(Lyrics(synced = listOf(SyncedLine(0, "a")))))
        val zeroWords = Lyrics(synced = listOf(SyncedLine(0, "a", words = listOf(SyncedWord(0, "a")))))
        assertEquals(CloudLyrics.Level.LINE_SYNCED, CloudLyrics.level(zeroWords)) // a failed earlier alignment
        val words = Lyrics(synced = listOf(SyncedLine(10, "a", words = listOf(SyncedWord(10, "a")))))
        assertEquals(CloudLyrics.Level.WORD_SYNCED, CloudLyrics.level(words))
        assertTrue(CloudLyrics.shouldImport(CloudLyrics.Level.LINE_SYNCED, CloudLyrics.Level.WORD_SYNCED, false, false))
        assertFalse(CloudLyrics.shouldImport(CloudLyrics.Level.WORD_SYNCED, CloudLyrics.Level.WORD_SYNCED, false, false))
        assertFalse(CloudLyrics.shouldImport(CloudLyrics.Level.PLAIN, CloudLyrics.Level.WORD_SYNCED, true, false))
        assertTrue(CloudLyrics.shouldImport(CloudLyrics.Level.WORD_SYNCED, CloudLyrics.Level.LINE_SYNCED, true, true))
        assertNotNull(CloudLyrics.SOURCE)
    }
}

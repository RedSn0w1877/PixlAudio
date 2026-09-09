package com.theveloper.pixelplay.data.tais.lyrics

import android.content.Context
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.data.repository.LyricsRepository
import com.theveloper.pixelplay.data.tais.TaisAiEngine
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaisLyricsPersistenceTest {
    private val repository = mockk<LyricsRepository>(relaxed = true)
    private val aligner = TaisLyricsAligner(
        repository, mockk<TaisWav2Vec2Aligner>(), mockk<TaisAiEngine>(), mockk<Context>()
    )

    @Test
    fun `streaming IDs persist aligned lyrics instead of silently returning`() = runTest {
        val song = Song.emptySong().copy(id = "spotify_streaming-track")
        val lines = listOf(SyncedLine(1000, "Hello world", listOf(
            SyncedWord(1000, "Hello"), SyncedWord(1500, "world")
        )))
        aligner.persistAligned(song, lines)
        coVerify(exactly = 1) {
            repository.updateLyrics(song, "[00:01.00]<00:01.00>Hello <00:01.50>world")
        }
    }

    @Test
    fun `partial word timings remain eligible for retry`() {
        val lyrics = Lyrics(synced = listOf(
            SyncedLine(1000, "One", listOf(SyncedWord(1000, "One"))),
            SyncedLine(2000, "Two")
        ))
        assertTrue(aligner.alignmentStateFor(lyrics) is TaisLyricsAligner.AlignmentState.LineSyncedOnly)
    }

    @Test
    fun `failed zero-time output is not mistaken for completed sync`() {
        val lyrics = Lyrics(synced = listOf(SyncedLine(0, "One two", listOf(
            SyncedWord(0, "One"), SyncedWord(0, "two")
        ))))
        assertTrue(aligner.alignmentStateFor(lyrics) is TaisLyricsAligner.AlignmentState.LineSyncedOnly)
    }

    @Test
    fun `instrumental break does not invalidate real word sync`() {
        val lyrics = Lyrics(synced = listOf(
            SyncedLine(1000, "One", listOf(SyncedWord(1000, "One"))), SyncedLine(2000, "")
        ))
        assertEquals(TaisLyricsAligner.AlignmentState.WordSynced, aligner.alignmentStateFor(lyrics))
    }
    @Test
    fun `explicit resync keeps repeated text and drops bad timing anchors`() {
        val lyrics = Lyrics(plain = listOf("stale text"), synced = listOf(
            SyncedLine(90000, "Repeat", listOf(SyncedWord(90000, "Repeat"))),
            SyncedLine(95000, "Repeat", listOf(SyncedWord(95000, "Repeat")))
        ))
        assertEquals(TaisLyricsAligner.AlignmentState.WordSynced, aligner.alignmentStateFor(lyrics))
        assertEquals(TaisLyricsAligner.AlignmentState.PlainTextOnly(listOf("Repeat", "Repeat")),
            aligner.alignmentStateFor(lyrics, forceResync = true))
    }

    @Test
    fun `explicit resync of line lyrics ignores existing anchors`() {
        assertEquals(TaisLyricsAligner.AlignmentState.PlainTextOnly(listOf("Hello")),
            aligner.alignmentStateFor(Lyrics(synced = listOf(SyncedLine(99999, "Hello"))), true))
    }

    @Test
    fun `resync without text is skipped`() {
        assertEquals(TaisLyricsAligner.AlignmentState.NoLyrics, aligner.alignmentStateFor(null, true))
        assertEquals(TaisLyricsAligner.AlignmentState.NoLyrics,
            aligner.alignmentStateFor(Lyrics(plain = listOf(" ")), true))
    }

    @Test
    fun `unusable replacement never overwrites saved lyrics`() = runTest {
        val song = Song.emptySong().copy(id = "spotify_keep-existing")
        val invalid = listOf(
            emptyList(),
            listOf(SyncedLine(0, "Hello", listOf(SyncedWord(0, "Hello")))),
            listOf(SyncedLine(1000, "Hello world", listOf(
                SyncedWord(1000, "Hello"), SyncedWord(500, "world"))))
        )
        for (lines in invalid) {
            var rejected = false
            try { aligner.persistAligned(song, lines) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
        coVerify(exactly = 0) { repository.updateLyrics(any<Song>(), any<String>()) }
    }
    @Test
    fun `resync of persisted degenerate timing retains the entire original text`() {
        val raw = "[00:00.00]<00:00.00>First <00:00.00>line\n" +
            "[00:00.00]<00:00.00>Second <00:00.00>line"
        val parsed = com.theveloper.pixelplay.utils.LyricsUtils.parseLyrics(raw)
        assertEquals(TaisLyricsAligner.AlignmentState.PlainTextOnly(listOf("First line", "Second line")),
            aligner.alignmentStateFor(parsed, forceResync = true))
    }
}

package com.theveloper.pixelplay.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.media.SongMetadataEditResult
import com.theveloper.pixelplay.data.media.SongMetadataEditor
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Saving "Edit song" for a title fix must never reset or overwrite the user's own tap-sync
 * (it lives in Room + lyrics/{id}.json, not in `song.lyrics`).
 */
class MetadataEditLyricsPreservationTest {

    private val song = Song(
        id = "1",
        title = "Indian Summr",
        artist = "Blood Cultures",
        album = "Happy Birthday",
        path = "",
        contentUriString = "content://media/external/audio/media/1",
        albumArtUriString = null,
        duration = 295_000L,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100,
        artistId = 1L,
        albumId = 1L
    )

    private fun holder(repository: MusicRepository, editor: SongMetadataEditor) = MetadataEditStateHolder(
        songMetadataEditor = editor,
        musicRepository = repository,
        imageCacheManager = mockk(relaxed = true),
        themeStateHolder = mockk(relaxed = true),
        playbackStateHolder = mockk(relaxed = true),
        libraryStateHolder = mockk(relaxed = true),
        multiSelectionStateHolder = mockk(relaxed = true),
        albumArtThemeDao = mockk(relaxed = true),
        context = mockk(relaxed = true)
    )

    private fun editor(): SongMetadataEditor = mockk<SongMetadataEditor>().also {
        coEvery {
            it.editSongMetadata(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns SongMetadataEditResult(success = true, updatedAlbumArtUri = null)
    }

    private suspend fun save(holder: MetadataEditStateHolder, lyrics: String?) = holder.saveMetadata(
        song = song,
        newTitle = "Indian Summer",
        newArtist = song.artist,
        newAlbum = song.album,
        newAlbumArtist = "",
        newComposer = "",
        newGenre = "",
        newLyrics = lyrics,
        newTrackNumber = 1,
        newDiscNumber = null,
        coverArtUpdate = null
    )

    @Test
    fun titleOnlySave_leavesStoredLyricsAndTapSyncAlone() = runBlocking {
        val repository = mockk<MusicRepository>(relaxed = true)
        every { repository.getSong(any()) } returns flowOf(song)
        val editor = editor()

        val result = save(holder(repository, editor), lyrics = null)

        assertThat(result.success).isTrue()
        assertThat(result.lyricsUntouched).isTrue()
        coVerify(exactly = 0) { repository.resetLyrics(any()) }
        coVerify(exactly = 0) { repository.updateLyrics(any(), any()) }
        // The file's lyrics tag is kept as well (null = leave it).
        coVerify { editor.editSongMetadata(any(), any(), any(), any(), any(), any(), any(), isNull(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun clearingTheLyricsField_stillResetsLyrics() = runBlocking {
        val repository = mockk<MusicRepository>(relaxed = true)
        every { repository.getSong(any()) } returns flowOf(song)

        val result = save(holder(repository, editor()), lyrics = "  ")

        assertThat(result.lyricsUntouched).isFalse()
        coVerify(exactly = 1) { repository.resetLyrics(1L) }
    }

    @Test
    fun editedLyrics_areWritten() = runBlocking {
        val repository = mockk<MusicRepository>(relaxed = true)
        every { repository.getSong(any()) } returns flowOf(song)

        save(holder(repository, editor()), lyrics = "new words\n")

        coVerify(exactly = 1) { repository.updateLyrics(1L, "new words") }
        coVerify(exactly = 0) { repository.resetLyrics(any()) }
    }
}

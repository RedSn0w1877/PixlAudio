package com.theveloper.pixelplay.data.database

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MusicRebuildRetentionTest {
    private fun song(id: Long, source: Int) = SongEntity(
        id = id, title = "Song $id", artistName = "Artist", artistId = id,
        albumName = "Album", albumId = id, contentUriString = if (source == SourceType.LOCAL)
            "content://media/external/audio/media/$id" else "spotify://yt_video$id",
        albumArtUriString = null, duration = 180_000L, genre = null,
        filePath = "", parentDirectoryPath = "", sourceType = source,
        isFavorite = true, lyrics = "Saved lyrics"
    )

    @Test fun `rebuilding local library retains streaming songs and their metadata`() = runTest {
        val oldLocal = song(1L, SourceType.LOCAL)
        val stream = song(-20L, SourceType.SPOTIFY)
        val newLocal = song(2L, SourceType.LOCAL)
        val stored = mutableMapOf(oldLocal.id to oldLocal, stream.id to stream)
        val refs = mutableListOf(SongArtistCrossRef(oldLocal.id, oldLocal.artistId, true),
            SongArtistCrossRef(stream.id, stream.artistId, true))
        val dao = mockk<MusicDao>(relaxed = true)
        coEvery { dao.rebuildMusicDataWithCrossRefs(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.getAllMediaStoreSongIds() } answers {
            stored.values.filter { it.sourceType == SourceType.LOCAL }.map { it.id }
        }
        coEvery { dao.deleteCrossRefsBySongIds(any()) } answers {
            val ids = firstArg<List<Long>>().toSet()
            refs.removeAll { it.songId in ids }; Unit
        }
        coEvery { dao.deleteSongsByIds(any()) } answers {
            firstArg<List<Long>>().forEach(stored::remove); Unit
        }
        coEvery { dao.insertSongs(any()) } answers {
            firstArg<List<SongEntity>>().forEach { stored[it.id] = it }; Unit
        }
        coEvery { dao.insertSongArtistCrossRefs(any()) } answers {
            refs.addAll(firstArg<List<SongArtistCrossRef>>()); Unit
        }
        // These model the old destructive implementation, so regression fails on data loss.
        coEvery { dao.clearAllSongs() } answers { stored.clear() }
        coEvery { dao.clearAllSongArtistCrossRefs() } answers { refs.clear() }

        dao.rebuildMusicDataWithCrossRefs(listOf(newLocal), emptyList(), emptyList(),
            listOf(SongArtistCrossRef(newLocal.id, newLocal.artistId, true)))

        assertEquals(setOf(stream.id, newLocal.id), stored.keys)
        assertEquals(stream, stored[stream.id])
        assertEquals(setOf(stream.id, newLocal.id), refs.map { it.songId }.toSet())
        coVerify(exactly = 0) { dao.clearAllAlbums() }
        coVerify(exactly = 0) { dao.clearAllArtists() }
    }

    @Test fun `large rebuild bounds deletion batches to SQLite limits`() = runTest {
        val ids = (1L..1_100L).toList()
        val deleted = mutableListOf<List<Long>>()
        val dao = mockk<MusicDao>(relaxed = true)
        coEvery { dao.rebuildMusicDataWithCrossRefs(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.getAllMediaStoreSongIds() } returns ids
        coEvery { dao.deleteSongsByIds(any()) } answers { deleted.add(firstArg()); Unit }

        dao.rebuildMusicDataWithCrossRefs(emptyList(), emptyList(), emptyList(), emptyList())

        assertEquals(ids, deleted.flatten())
        assertEquals(4, deleted.size)
        assertEquals(true, deleted.all { it.size <= MusicDao.CROSS_REF_BATCH_SIZE })
        coVerify(exactly = 0) { dao.clearAllSongs() }
    }
}

package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.SpotifySongEntity
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StreamingSongIdentityTest {
    private val rawId = "aBcDeFgHiJkLmNoPqRsTuV"
    private val alias = "spotify_$rawId"
    private val canonical = SpotifyRepository.unifiedSongId(rawId)
    private val musicDao = mockk<MusicDao>()
    private val spotifyDao = mockk<SpotifyDao>()

    private fun repository(): MusicRepositoryImpl {
        val preferences = mockk<UserPreferencesRepository> {
            every { allowedDirectoriesFlow } returns flowOf(emptySet())
            every { blockedDirectoriesFlow } returns flowOf(emptySet())
        }
        return MusicRepositoryImpl(mockk(), preferences, mockk(), mockk(), musicDao,
            mockk(), mockk(), mockk(), mockk(), mockk(), spotifyDao)
    }

    private fun mirrored(id: Long = canonical) = SongEntity(
        id = id, title = "Northern Lights", artistName = "Nova", artistId = -4L,
        albumName = "First Light", albumId = -5L, contentUriString = "spotify://$rawId",
        albumArtUriString = null, duration = 180_000L, genre = "Ambient", filePath = "",
        parentDirectoryPath = "", isFavorite = true, lyrics = "[00:01.00]A word-synced line"
    )

    private fun source() = SpotifySongEntity("source", rawId, "playlist", "Northern Lights",
        "Nova", "First Light", null, 180_000L, null, null, 0L)

    @Test fun `legacy alias resolves detailed library row for lyrics and stem workers`() = runTest {
        every { musicDao.getSongById(canonical) } returns flowOf(mirrored())
        val result = repository().getSong(alias).first()
        assertEquals(alias, result?.id)
        assertEquals(rawId, result?.spotifyId)
        assertEquals("[00:01.00]A word-synced line", result?.lyrics)
        assertTrue(result?.isFavorite == true)
        coVerify(exactly = 0) { spotifyDao.getSongBySpotifyId(any()) }
    }

    @Test fun `new discovery numeric identity resolves without changing its ID`() = runTest {
        every { musicDao.getSongById(canonical) } returns flowOf(mirrored())
        assertEquals(canonical.toString(), repository().getSong(canonical.toString()).first()?.id)
    }

    @Test fun `saved mixed ID playlist preserves requested aliases order and duplicates`() = runTest {
        val local = mirrored(42L).copy(contentUriString = "content://media/external/audio/media/42")
        every { musicDao.getSongsByIds(any(), any(), false) } returns flowOf(listOf(mirrored(), local))
        val requested = listOf("42", alias, canonical.toString(), alias)
        val results = repository().getSongsByIds(requested).first()
        assertEquals(requested, results.map { it.id })
        assertTrue(results[1].isFavorite)
        coVerify(exactly = 0) { spotifyDao.getSongsBySpotifyIds(any()) }
    }

    @Test fun `legacy source row remains usable before its library mirror exists`() = runTest {
        every { musicDao.getSongById(canonical) } returns flowOf(null)
        coEvery { spotifyDao.getSongBySpotifyId(rawId) } returns source()
        val result = repository().getSong(alias).first()
        assertEquals(alias, result?.id)
        assertEquals("spotify://$rawId", result?.contentUriString)
    }

    @Test fun `legacy batch lookup falls back to source rows without losing saved order`() = runTest {
        every { musicDao.getSongsByIds(any(), any(), false) } returns flowOf(emptyList())
        coEvery { spotifyDao.getSongsBySpotifyIds(listOf(rawId)) } returns listOf(source())
        assertEquals(listOf(alias), repository().getSongsByIds(listOf("404", alias)).first().map { it.id })
    }
}

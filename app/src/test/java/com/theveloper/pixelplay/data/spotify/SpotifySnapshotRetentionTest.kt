package com.theveloper.pixelplay.data.spotify

import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.SpotifyPlaylistEntity
import com.theveloper.pixelplay.data.network.spotify.SpotifyApiService
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylist
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylistTrackItem
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylistsPage
import com.theveloper.pixelplay.data.network.spotify.SpotifyTracksPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

class SpotifySnapshotRetentionTest {
    private val api = mockk<SpotifyApiService>()
    private val dao = mockk<SpotifyDao>(relaxed = true) {
        every { getAllMatchStatesFlow() } returns flowOf(emptyList())
    }
    private val auth = mockk<SpotifyAuthManager> {
        every { isLoggedIn } returns MutableStateFlow(true)
        coEvery { authorizationHeader() } returns "test-authorization"
    }
    private fun repository() = SpotifyRepository(mockk(), api, auth, dao, mockk(), mockk(relaxed = true))
    private fun saved(id: String) = SpotifyPlaylistEntity(id, id, null, 2, 123L)

    @Test fun `catalog discoveries survive successful Spotify playlist pruning`() = runTest {
        coEvery { api.getUserPlaylists(any(), any(), 0) } returns Response.success(
            SpotifyPlaylistsPage(items = listOf(SpotifyPlaylist("remote", "Remote")), total = 1)
        )
        coEvery { dao.getAllPlaylistsList() } returns listOf(saved("remote"), saved("deleted"),
            saved(SpotifyPlaylistEntity.BROWSE_ID), saved(SpotifyPlaylistEntity.LIKED_SONGS_ID))
        repository().syncUserPlaylists()
        coVerify(exactly = 1) { dao.deleteSongsByPlaylist("deleted") }
        coVerify(exactly = 0) { dao.deleteSongsByPlaylist(SpotifyPlaylistEntity.BROWSE_ID) }
        coVerify(exactly = 0) { dao.deletePlaylist(SpotifyPlaylistEntity.BROWSE_ID) }
        coVerify(exactly = 0) { dao.deleteSongsByPlaylist(SpotifyPlaylistEntity.LIKED_SONGS_ID) }
    }

    @Test fun `failed first playlist page never prunes the saved library`() = runTest {
        coEvery { api.getUserPlaylists(any(), any(), any()) } returns Response.error(503, "{}".toResponseBody())
        assertTrue(runCatching { repository().syncUserPlaylists() }.exceptionOrNull() is IOException)
        coVerify(exactly = 0) { dao.deleteSongsByPlaylist(any()) }
        coVerify(exactly = 0) { dao.deletePlaylist(any()) }
        coVerify(exactly = 0) { dao.insertPlaylist(any()) }
    }

    @Test fun `failed later playlist page cannot replace a complete snapshot with its prefix`() = runTest {
        coEvery { api.getUserPlaylists(any(), any(), 0) } returns Response.success(
            SpotifyPlaylistsPage(items = listOf(SpotifyPlaylist("first", "First")), total = 2,
                offset = 0, limit = 1, next = "https://api.spotify.com/v1/me/playlists?offset=1")
        )
        coEvery { api.getUserPlaylists(any(), any(), 1) } returns Response.error(503, "{}".toResponseBody())
        assertTrue(runCatching { repository().syncUserPlaylists() }.exceptionOrNull() is IOException)
        coVerify(exactly = 1) { api.getUserPlaylists(any(), any(), 1) }
        coVerify(exactly = 0) { dao.deletePlaylist(any()) }
        coVerify(exactly = 0) { dao.insertPlaylist(any()) }
    }

    @Test fun `short page with a next cursor still fetches the remaining playlists`() = runTest {
        coEvery { api.getUserPlaylists(any(), any(), 0) } returns Response.success(
            SpotifyPlaylistsPage(items = listOf(SpotifyPlaylist("first", "First")), total = 2,
                offset = 0, limit = 1, next = "https://api.spotify.com/v1/me/playlists?offset=1")
        )
        coEvery { api.getUserPlaylists(any(), any(), 1) } returns Response.success(
            SpotifyPlaylistsPage(items = listOf(SpotifyPlaylist("second", "Second")), total = 2, offset = 1)
        )
        assertEquals(listOf("first", "second"), repository().syncUserPlaylists().map { it.id })
    }

    @Test fun `failed liked songs fetch preserves existing membership`() = runTest {
        coEvery { api.getSavedTracks(any(), any(), any()) } returns Response.error(503, "{}".toResponseBody())
        assertTrue(runCatching { repository().syncLikedSongs(emptyMap()) }.exceptionOrNull() is IOException)
        coVerify(exactly = 0) { dao.replaceSongsForPlaylist(any(), any()) }
        coVerify(exactly = 0) { dao.deleteSongsByPlaylist(any()) }
    }

    @Test fun `playlist access denied preserves already downloaded songs`() = runTest {
        coEvery { api.getPlaylistTracks(any(), any(), any(), any(), any()) } returns
            Response.error(403, "{}".toResponseBody())
        val repository = repository()
        assertTrue(runCatching { repository.syncPlaylistSongs("saved", emptyMap()) }.exceptionOrNull() is IOException)
        assertTrue(repository.playlistAccessDenied.value)
        coVerify(exactly = 0) { dao.replaceSongsForPlaylist(any(), any()) }
        coVerify(exactly = 0) { dao.deleteSongsByPlaylist(any()) }
    }

    @Test fun `partial liked songs snapshot is not published after a later failure`() = runTest {
        coEvery { api.getSavedTracks(any(), any(), 0) } returns Response.success(
            SpotifyTracksPage(items = listOf(SpotifyPlaylistTrackItem()), total = 2, limit = 1,
                offset = 0, next = "https://api.spotify.com/v1/me/tracks?offset=1")
        )
        coEvery { api.getSavedTracks(any(), any(), 1) } returns Response.error(503, "{}".toResponseBody())
        assertTrue(runCatching { repository().syncLikedSongs(emptyMap()) }.exceptionOrNull() is IOException)
        coVerify(exactly = 1) { api.getSavedTracks(any(), any(), 1) }
        coVerify(exactly = 0) { dao.replaceSongsForPlaylist(any(), any()) }
    }

    @Test fun `partial playlist contents survive both filtered and fallback failures`() = runTest {
        coEvery { api.getPlaylistTracks(any(), "saved", any(), 0, any()) } returns Response.success(
            SpotifyTracksPage(items = listOf(SpotifyPlaylistTrackItem()), total = 2, limit = 1,
                offset = 0, next = "https://api.spotify.com/v1/playlists/saved/items?offset=1")
        )
        coEvery { api.getPlaylistTracks(any(), "saved", any(), 1, any()) } returns Response.error(503, "{}".toResponseBody())
        assertTrue(runCatching { repository().syncPlaylistSongs("saved", emptyMap()) }.exceptionOrNull() is IOException)
        coVerify(exactly = 2) { api.getPlaylistTracks(any(), "saved", any(), 1, any()) }
        coVerify(exactly = 0) { dao.replaceSongsForPlaylist(any(), any()) }
    }

    @Test fun `successful explicitly empty liked snapshot can clear membership`() = runTest {
        coEvery { api.getSavedTracks(any(), any(), any()) } returns Response.success(
            SpotifyTracksPage(items = emptyList(), total = 0)
        )
        assertEquals(0, repository().syncLikedSongs(emptyMap()))
        coVerify(exactly = 1) { dao.replaceSongsForPlaylist(SpotifyPlaylistEntity.LIKED_SONGS_ID, emptyList()) }
    }
}

package com.theveloper.pixelplay.data.spotify

import com.theveloper.pixelplay.data.database.SpotifyPlaylistEntity
import com.theveloper.pixelplay.data.network.spotify.SpotifyApiService
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylist
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylistTrackItem
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylistsPage
import com.theveloper.pixelplay.data.network.spotify.SpotifyTracksPage
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

/**
 * A failed or incomplete fetch must never shrink what is already saved: pages are added as
 * they arrive, but rows are only removed once every page of a snapshot has been read.
 */
class SpotifySnapshotRetentionTest {
    private val f = SpotifyFixture()
    private val api get() = f.api
    private val dao get() = f.dao
    private fun repository() = f.repository()
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
        coEvery { api.getUserPlaylists(any(), any(), any()) } returns errorResponse(503)
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
        coEvery { api.getUserPlaylists(any(), any(), 1) } returns errorResponse(503)
        assertTrue(runCatching { repository().syncUserPlaylists() }.exceptionOrNull() is IOException)
        coVerify(atLeast = 1) { api.getUserPlaylists(any(), any(), 1) }
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
        coEvery { api.getSavedTracks(any(), any(), any()) } returns errorResponse(503)
        assertTrue(runCatching { repository().syncLikedSongs() }.exceptionOrNull() is IOException)
        coVerify(exactly = 0) { dao.removeSongsNotIn(any(), any()) }
        coVerify(exactly = 0) { dao.deleteSongsByPlaylist(any()) }
    }

    @Test fun `playlist access denied preserves already downloaded songs and is not a reconnect`() = runTest {
        coEvery { api.getPlaylistTracks(any(), any(), any(), any(), any()) } returns errorResponse(403)
        val repository = repository()
        val error = runCatching { repository.syncPlaylistSongs("saved") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error is PlaylistSkipped)
        assertFalse(repository.reconnectRequired.value)
        coVerify(exactly = 0) { dao.removeSongsNotIn(any(), any()) }
        coVerify(exactly = 0) { dao.deleteSongsByPlaylist(any()) }
    }

    @Test fun `partial liked songs snapshot never removes anything after a later failure`() = runTest {
        coEvery { api.getSavedTracks(any(), any(), 0) } returns Response.success(
            SpotifyTracksPage(items = listOf(SpotifyPlaylistTrackItem()), total = 2, limit = 1,
                offset = 0, next = "https://api.spotify.com/v1/me/tracks?offset=1")
        )
        coEvery { api.getSavedTracks(any(), any(), 1) } returns errorResponse(503)
        assertTrue(runCatching { repository().syncLikedSongs() }.exceptionOrNull() is IOException)
        coVerify(atLeast = 1) { api.getSavedTracks(any(), any(), 1) }
        coVerify(exactly = 0) { dao.removeSongsNotIn(any(), any()) }
    }

    @Test fun `partial playlist contents survive both filtered and fallback failures`() = runTest {
        coEvery { api.getPlaylistTracks(any(), "saved", any(), 0, any()) } returns Response.success(
            SpotifyTracksPage(items = listOf(SpotifyPlaylistTrackItem()), total = 2, limit = 1,
                offset = 0, next = "https://api.spotify.com/v1/playlists/saved/items?offset=1")
        )
        coEvery { api.getPlaylistTracks(any(), "saved", any(), 1, any()) } returns errorResponse(503)
        assertTrue(runCatching { repository().syncPlaylistSongs("saved") }.exceptionOrNull() is IOException)
        // Filtered request, then the unfiltered fallback: both were tried before giving up.
        coVerify(atLeast = 1) { api.getPlaylistTracks(any(), "saved", any(), 1, SpotifyApiService.PLAYLIST_TRACK_FIELDS) }
        coVerify(atLeast = 1) { api.getPlaylistTracks(any(), "saved", any(), 1, null) }
        coVerify(exactly = 0) { dao.removeSongsNotIn(any(), any()) }
    }

    @Test fun `successful explicitly empty liked snapshot can clear membership`() = runTest {
        coEvery { api.getSavedTracks(any(), any(), any()) } returns Response.success(
            SpotifyTracksPage(items = emptyList(), total = 0)
        )
        assertEquals(0, repository().syncLikedSongs())
        coVerify(exactly = 1) { dao.removeSongsNotIn(SpotifyPlaylistEntity.LIKED_SONGS_ID, emptySet()) }
    }
}

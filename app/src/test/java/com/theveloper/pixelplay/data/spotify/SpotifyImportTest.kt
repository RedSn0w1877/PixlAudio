package com.theveloper.pixelplay.data.spotify

import com.theveloper.pixelplay.data.database.SpotifyPlaylistEntity
import com.theveloper.pixelplay.data.database.SpotifySongEntity
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylist
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylistOwner
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylistTrackItem
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaylistsPage
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.network.spotify.SpotifyTracksPage
import com.theveloper.pixelplay.data.network.spotify.SpotifyUserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.util.concurrent.CopyOnWriteArrayList

class SpotifyImportTest {

    private fun track(id: String) = SpotifyTrack(id = id, name = "Song $id", durationMs = 1000L, type = "track")
    private fun item(id: String) = SpotifyPlaylistTrackItem(addedAt = "2026-01-01T00:00:00Z", item = track(id))
    private fun playlist(id: String, owner: String = "me", collaborative: Boolean = false, snapshot: String? = "s-$id") =
        SpotifyPlaylist(
            id = id, name = "Playlist $id", owner = SpotifyPlaylistOwner(id = owner),
            collaborative = collaborative, snapshotId = snapshot
        )

    private fun SpotifyFixture.stubAccount(
        likedIds: List<String> = emptyList(),
        playlists: List<SpotifyPlaylist> = emptyList()
    ) {
        coEvery { api.getProfile(any()) } returns
            Response.success(SpotifyUserProfile(id = "me", displayName = "Me", email = null, product = null))
        coEvery { api.getSavedTracks(any(), any(), any()) } returns Response.success(
            SpotifyTracksPage(items = likedIds.map(::item), total = likedIds.size, limit = 50, offset = 0)
        )
        coEvery { api.getUserPlaylists(any(), any(), any()) } returns Response.success(
            SpotifyPlaylistsPage(items = playlists, total = playlists.size, limit = 50, offset = 0)
        )
    }

    @Test fun `a 403 on one playlist skips only that playlist and the import completes`() = runTest {
        val f = SpotifyFixture()
        f.stubAccount(playlists = listOf(playlist("A"), playlist("B"), playlist("C", owner = "someone-else")))
        coEvery { f.api.getPlaylistTracks(any(), "A", any(), any(), any()) } returns errorResponse(403)
        coEvery { f.api.getPlaylistTracks(any(), "B", any(), any(), any()) } returns Response.success(
            SpotifyTracksPage(items = listOf(item("b1"), item("b2")), total = 2, limit = 50, offset = 0)
        )
        val published = CopyOnWriteArrayList<SpotifySongEntity>()
        coEvery { f.dao.upsertImportedSongs(any()) } coAnswers { published += firstArg<List<SpotifySongEntity>>() }

        val result = f.repository().syncAllPlaylistsAndSongs()

        assertTrue(result.isComplete)
        assertEquals(2, result.syncedSongCount) // B's two songs; A refused, C not even requested
        assertEquals(2, result.skippedPlaylistCount)
        assertEquals(setOf("b1", "b2"), published.map { it.spotifyId }.toSet())

        val skipped = f.store.skipped.value.associateBy { it.id }
        assertEquals(SkipReason.FORBIDDEN, skipped["A"]?.reason)
        assertEquals(SkipReason.NOT_OWNED, skipped["C"]?.reason)
        // Someone else's playlist is never requested: Spotify would answer 403 for sure.
        coVerify(exactly = 0) { f.api.getPlaylistTracks(any(), "C", any(), any(), any()) }
    }

    @Test fun `a refused playlist does not raise the reconnect banner when every scope is granted`() = runTest {
        val f = SpotifyFixture()
        f.stubAccount(playlists = listOf(playlist("A")))
        coEvery { f.api.getPlaylistTracks(any(), "A", any(), any(), any()) } returns errorResponse(403)
        val repo = f.repository()
        repo.syncAllPlaylistsAndSongs()
        assertFalse(repo.reconnectRequired.value)
    }

    @Test fun `a 403 with a missing scope raises the reconnect prompt but still finishes the pass`() = runTest {
        val f = SpotifyFixture(scopesMissing = setOf("playlist-read-private"))
        f.stubAccount(playlists = listOf(playlist("A"), playlist("B")))
        coEvery { f.api.getPlaylistTracks(any(), "A", any(), any(), any()) } returns errorResponse(403)
        coEvery { f.api.getPlaylistTracks(any(), "B", any(), any(), any()) } returns errorResponse(403)
        val repo = f.repository()
        val result = repo.syncAllPlaylistsAndSongs()
        assertTrue(repo.reconnectRequired.value)
        assertTrue(result.isComplete)
        coVerify(exactly = 1) { f.api.getPlaylistTracks(any(), "A", any(), any(), any()) }
        coVerify(exactly = 1) { f.api.getPlaylistTracks(any(), "B", any(), any(), any()) }
    }

    @Test fun `Liked Songs is imported and published before any playlist is requested`() = runTest {
        val f = SpotifyFixture()
        f.stubAccount(likedIds = listOf("l1", "l2"), playlists = listOf(playlist("A"), playlist("B")))
        val calls = CopyOnWriteArrayList<String>()
        coEvery { f.api.getSavedTracks(any(), any(), any()) } coAnswers {
            calls += "liked"
            Response.success(SpotifyTracksPage(items = listOf(item("l1"), item("l2")), total = 2, limit = 50, offset = 0))
        }
        coEvery { f.api.getUserPlaylists(any(), any(), any()) } coAnswers {
            calls += "playlists"
            Response.success(SpotifyPlaylistsPage(items = listOf(playlist("A"), playlist("B")), total = 2, limit = 50, offset = 0))
        }
        coEvery { f.api.getPlaylistTracks(any(), any(), any(), any(), any()) } coAnswers {
            calls += "items:${secondArg<String>()}"
            Response.success(SpotifyTracksPage(items = listOf(item("x-${secondArg<String>()}")), total = 1, limit = 50, offset = 0))
        }
        val publishedPlaylists = CopyOnWriteArrayList<String>()
        coEvery { f.dao.upsertImportedSongs(any()) } coAnswers {
            firstArg<List<SpotifySongEntity>>().forEach { publishedPlaylists += it.playlistId }
        }

        f.repository().syncAllPlaylistsAndSongs()

        assertEquals("liked", calls.first())
        assertTrue(calls.indexOf("playlists") < calls.indexOfFirst { it.startsWith("items:") })
        // The first songs to reach the database are the liked ones.
        assertEquals(SpotifyPlaylistEntity.LIKED_SONGS_ID, publishedPlaylists.first())
    }

    @Test fun `playlist items are paged at the maximum size of 50 and every page is published`() = runTest {
        val f = SpotifyFixture()
        f.stubAccount(playlists = listOf(playlist("P")))
        val page1 = (1..50).map { item("t$it") }
        val page2 = (51..60).map { item("t$it") }
        coEvery { f.api.getPlaylistTracks(any(), "P", 50, 0, any()) } returns Response.success(
            SpotifyTracksPage(items = page1, total = 60, limit = 50, offset = 0,
                next = "https://api.spotify.com/v1/playlists/P/items?offset=50&limit=50")
        )
        coEvery { f.api.getPlaylistTracks(any(), "P", 50, 50, any()) } returns Response.success(
            SpotifyTracksPage(items = page2, total = 60, limit = 50, offset = 50)
        )
        val batches = CopyOnWriteArrayList<Int>()
        coEvery { f.dao.upsertImportedSongs(any()) } coAnswers { batches += firstArg<List<SpotifySongEntity>>().size }

        val result = f.repository().syncAllPlaylistsAndSongs()

        assertEquals(60, result.syncedSongCount)
        assertEquals(listOf(50, 10), batches.filter { it > 0 })
        coVerify(exactly = 1) { f.api.getPlaylistTracks(any(), "P", 50, 0, any()) }
        coVerify(exactly = 1) { f.api.getPlaylistTracks(any(), "P", 50, 50, any()) }
        // Rows of songs no longer in the playlist are removed only after the last page arrived.
        coVerify(exactly = 1) { f.dao.removeSongsNotIn("P", match { it.size == 60 }) }
    }

    @Test fun `an invalid limit falls back to a smaller page instead of failing the playlist`() = runTest {
        val f = SpotifyFixture()
        f.stubAccount(playlists = listOf(playlist("P")))
        coEvery { f.api.getPlaylistTracks(any(), "P", 50, any(), any()) } returns errorResponse(400)
        coEvery { f.api.getPlaylistTracks(any(), "P", 20, any(), any()) } returns Response.success(
            SpotifyTracksPage(items = listOf(item("t1")), total = 1, limit = 20, offset = 0)
        )
        val result = f.repository().syncAllPlaylistsAndSongs()
        assertEquals(1, result.syncedSongCount)
        assertEquals(0, result.failedPlaylistCount)
    }

    @Test fun `a playlist whose snapshot did not change costs no requests`() = runTest {
        val f = SpotifyFixture()
        f.stubAccount(playlists = listOf(playlist("A", snapshot = "snap-1")))
        f.store.markSynced("A", "snap-1")
        coEvery { f.dao.getAllPlaylistsList() } returns
            listOf(SpotifyPlaylistEntity("A", "Playlist A", null, 10, lastSyncTime = 123L))

        val result = f.repository().syncAllPlaylistsAndSongs()

        assertTrue(result.isComplete)
        coVerify(exactly = 0) { f.api.getPlaylistTracks(any(), "A", any(), any(), any()) }
    }

    @Test fun `a changed snapshot is downloaded again and remembered afterwards`() = runTest {
        val f = SpotifyFixture()
        f.stubAccount(playlists = listOf(playlist("A", snapshot = "snap-2")))
        f.store.markSynced("A", "snap-1")
        coEvery { f.dao.getAllPlaylistsList() } returns
            listOf(SpotifyPlaylistEntity("A", "Playlist A", null, 10, lastSyncTime = 123L))
        coEvery { f.api.getPlaylistTracks(any(), "A", any(), any(), any()) } returns Response.success(
            SpotifyTracksPage(items = listOf(item("a1")), total = 1, limit = 50, offset = 0)
        )
        f.repository().syncAllPlaylistsAndSongs()
        coVerify(exactly = 1) { f.api.getPlaylistTracks(any(), "A", any(), any(), any()) }
        assertEquals("snap-2", f.store.snapshotOf("A"))
    }

    @Test fun `Spotify rate limiting for a long time stops the pass cleanly and reports when to come back`() = runTest {
        val f = SpotifyFixture()
        f.stubAccount(playlists = (1..6).map { playlist("P$it") })
        coEvery { f.api.getPlaylistTracks(any(), any(), any(), any(), any()) } returns
            errorResponse(429, "Retry-After" to "900")

        val result = f.repository().syncAllPlaylistsAndSongs()

        assertFalse(result.isComplete)
        // Spotify asked for 900 s; a caller that failed fast reports what is left of it.
        assertTrue(result.retryAfterSeconds!! in 890L..900L, "retryAfter=${result.retryAfterSeconds}")
        // At most the playlists already in flight made a request; the rest never hit Spotify.
        coVerify(atMost = 3) { f.api.getPlaylistTracks(any(), any(), any(), any(), any()) }
    }

    @Test fun `a dead login stops the import and asks to reconnect`() = runTest {
        val f = SpotifyFixture()
        coEvery { f.api.getProfile(any()) } returns errorResponse(401)
        coEvery { f.auth.refreshAfterRejected(any()) } returns false
        val repo = f.repository()
        val result = repo.syncAllPlaylistsAndSongs()
        assertTrue(result.needsReconnect)
        assertTrue(repo.reconnectRequired.value)
        coVerify(exactly = 0) { f.api.getSavedTracks(any(), any(), any()) }
    }

    @Test fun `unchanged Liked Songs costs a single request`() = runTest {
        val f = SpotifyFixture()
        f.stubAccount(likedIds = listOf("l1", "l2"))
        f.store.likedSignature = "2|l1"
        coEvery { f.dao.getAllPlaylistsList() } returns listOf(
            SpotifyPlaylistEntity(SpotifyPlaylistEntity.LIKED_SONGS_ID, "Liked Songs", null, 2, lastSyncTime = 5L)
        )
        val result = f.repository().syncAllPlaylistsAndSongs()
        assertTrue(result.isComplete)
        coVerify(exactly = 1) { f.api.getSavedTracks(any(), any(), any()) }
        coVerify(exactly = 0) { f.dao.upsertImportedSongs(any()) }
    }
}

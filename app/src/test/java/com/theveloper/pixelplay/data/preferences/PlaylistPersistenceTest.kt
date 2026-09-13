package com.theveloper.pixelplay.data.preferences

import com.theveloper.pixelplay.data.database.LocalPlaylistDao
import com.theveloper.pixelplay.data.database.PlaylistEntity
import com.theveloper.pixelplay.data.database.PlaylistSongEntity
import com.theveloper.pixelplay.data.database.PlaylistWithSongsEntity
import com.theveloper.pixelplay.data.model.SortOption
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaylistPersistenceTest {
    private val playlist = PlaylistEntity(id = "spotify_playlist", name = "Mix", createdAt = 42L, sortOrder = 7)
    private val songs = listOf("c", "b", "a")
    private val dao = mockk<LocalPlaylistDao>(relaxed = true) {
        every { observePlaylistsWithSongs() } returns flowOf(listOf(
            PlaylistWithSongsEntity(playlist, songs.mapIndexed { index, id ->
                PlaylistSongEntity(playlist.id, id, index)
            })
        ))
        coEvery { getPlaylistCount() } returns 1
    }
    private val preferences = mockk<UserPreferencesRepository>(relaxed = true) {
        every { playlistSongOrderModesFlow } returns flowOf(mapOf(playlist.id to "manual"))
        every { playlistsSortOptionFlow } returns flowOf(SortOption.PlaylistNameAZ.storageKey)
    }
    private val repository = PlaylistPreferencesRepository(dao, preferences)

    @Test
    fun `provider refresh preserves drag order and metadata while reconciling membership`() = runTest {
        val updated = repository.createPlaylist(
            name = "Mix refreshed", songIds = listOf("a", "c", "new"),
            customId = playlist.id, source = "SPOTIFY"
        )
        assertEquals(listOf("c", "a", "new"), updated.songIds)
        assertEquals(42L, updated.createdAt)
        assertEquals(7, updated.sortOrder)
        coVerify { dao.savePlaylistWithSongs(any(), listOf("c", "a", "new")) }
    }

    @Test
    fun `playlist drag saves custom sort preference as well as positions`() = runTest {
        repository.reorderPlaylists(listOf(playlist.id))
        coVerify { dao.updatePlaylistOrder(listOf(playlist.id)) }
        coVerify { preferences.setPlaylistsSortOption(SortOption.PlaylistCustomOrder.storageKey) }
    }

    @Test
    fun `song drag saves manual mode and retains temporarily hidden tracks`() = runTest {
        repository.reorderSongsInPlaylist(playlist.id, listOf("a", "c"))
        coVerify { dao.savePlaylistWithSongs(any(), listOf("a", "c", "b")) }
        coVerify { preferences.setPlaylistSongOrderMode(playlist.id, "manual") }
    }

    @Test
    fun `renaming updates the row without replacement side effects`() = runTest {
        repository.renamePlaylist(playlist.id, "Renamed")
        coVerify { dao.updatePlaylist(match { it.name == "Renamed" && it.sortOrder == 7 }) }
        coVerify(exactly = 0) { dao.upsertPlaylist(any()) }
    }
}

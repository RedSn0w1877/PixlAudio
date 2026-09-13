package com.theveloper.pixelplay.data.preferences

import com.theveloper.pixelplay.data.database.LocalPlaylistDao
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.database.toEntity
import com.theveloper.pixelplay.data.database.toPlaylist
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.playlist.mergePlaylistOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistPreferencesRepository @Inject constructor(
    private val localPlaylistDao: LocalPlaylistDao,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    private val migrationMutex = Mutex()
    // Serializes read-modify-write edits to playlists. Without this, concurrent edits
    // (e.g. removing several songs in quick succession) each read the same snapshot via
    // userPlaylistsFlow.first() and the last writer wins, silently dropping the other
    // edits — which left the Playlists-menu song count stuck high. See issue #2391.
    private val editMutex = Mutex()
    @Volatile
    private var migrationChecked = false

    val userPlaylistsFlow: Flow<List<Playlist>> = localPlaylistDao.observePlaylistsWithSongs()
        .onStart { ensureMigratedIfNeeded() }
        .map { rows ->
            rows.map { row ->
                row.playlist.toPlaylist(
                    songIds = row.songs.sortedBy { it.sortOrder }.map { it.songId }
                )
            }
        }

    val playlistSongOrderModesFlow: Flow<Map<String, String>> =
        userPreferencesRepository.playlistSongOrderModesFlow
    val playlistsSortOptionFlow: Flow<String> = userPreferencesRepository.playlistsSortOptionFlow
    suspend fun createPlaylist(
        name: String,
        songIds: List<String> = emptyList(),
        isAiGenerated: Boolean = false,
        isQueueGenerated: Boolean = false,
        coverImageUri: String? = null,
        coverColorArgb: Int? = null,
        coverIconName: String? = null,
        coverShapeType: String? = null,
        coverShapeDetail1: Float? = null,
        coverShapeDetail2: Float? = null,
        coverShapeDetail3: Float? = null,
        coverShapeDetail4: Float? = null,
        customId: String? = null,
        source: String = "LOCAL"
    ): Playlist = editMutex.withLock {
        ensureMigratedIfNeeded()
        val now = System.currentTimeMillis()
        val playlists = userPlaylistsFlow.first()
        val existing = customId?.let { id -> playlists.firstOrNull { it.id == id } }
        val keepManualOrder = existing != null &&
            userPreferencesRepository.playlistSongOrderModesFlow.first()[existing.id] == "manual"
        // Spotify refresh reuses customId. Keep a user's drag order for surviving tracks,
        // append newly imported tracks, and still remove songs removed from the source.
        val incomingIds = songIds.distinct()
        val savedSongIds = if (keepManualOrder) mergePlaylistOrder(incomingIds, existing!!.songIds) else incomingIds
        val newPlaylist = Playlist(
            id = customId ?: UUID.randomUUID().toString(),
            name = name,
            songIds = savedSongIds,
            createdAt = existing?.createdAt ?: now,
            lastModified = now,
            isAiGenerated = isAiGenerated,
            isQueueGenerated = isQueueGenerated,
            coverImageUri = coverImageUri,
            coverColorArgb = coverColorArgb,
            coverIconName = coverIconName,
            coverShapeType = coverShapeType,
            coverShapeDetail1 = coverShapeDetail1,
            coverShapeDetail2 = coverShapeDetail2,
            coverShapeDetail3 = coverShapeDetail3,
            coverShapeDetail4 = coverShapeDetail4,
            source = source,
            sortOrder = existing?.sortOrder ?: ((playlists.maxOfOrNull { it.sortOrder } ?: -1) + 1),
        )
        localPlaylistDao.savePlaylistWithSongs(newPlaylist.toEntity(), newPlaylist.songIds)
        newPlaylist
    }

    suspend fun deletePlaylist(playlistId: String) {
        ensureMigratedIfNeeded()
        localPlaylistDao.deletePlaylist(playlistId)
        clearPlaylistSongOrderMode(playlistId)
    }

    suspend fun renamePlaylist(playlistId: String, newName: String) {
        editMutex.withLock {
            ensureMigratedIfNeeded()
            val existing = userPlaylistsFlow.first().find { it.id == playlistId } ?: return
            val updated = existing.copy(
                name = newName,
                lastModified = System.currentTimeMillis()
            )
            localPlaylistDao.updatePlaylist(updated.toEntity())
        }
    }

    suspend fun updatePlaylist(playlist: Playlist) {
        editMutex.withLock {
            updatePlaylistLocked(playlist)
        }
    }

    /** Cover/name editing must not restore membership from a screen's stale snapshot. */
    suspend fun updatePlaylistMetadata(playlist: Playlist) {
        editMutex.withLock {
            ensureMigratedIfNeeded()
            val current = localPlaylistDao.getPlaylistById(playlist.id) ?: return
            localPlaylistDao.updatePlaylist(
                playlist.copy(lastModified = System.currentTimeMillis()).toEntity().copy(sortOrder = current.sortOrder)
            )
        }
    }

    // Persists a playlist and its songs. Caller must hold [editMutex] so the
    // surrounding read-modify-write stays atomic.
    private suspend fun updatePlaylistLocked(playlist: Playlist) {
        ensureMigratedIfNeeded()
        val updated = playlist.copy(lastModified = System.currentTimeMillis())
        localPlaylistDao.savePlaylistWithSongs(updated.toEntity(), updated.songIds)
    }

    suspend fun addSongsToPlaylist(playlistId: String, songIdsToAdd: List<String>) {
        editMutex.withLock {
            ensureMigratedIfNeeded()
            val existing = userPlaylistsFlow.first().find { it.id == playlistId } ?: return
            val merged = (existing.songIds + songIdsToAdd).distinct()
            updatePlaylistLocked(existing.copy(songIds = merged))
        }
    }

    suspend fun addOrRemoveSongFromPlaylists(songId: String, playlistIds: List<String>): MutableList<String> {
        ensureMigratedIfNeeded()
        val currentPlaylists = userPlaylistsFlow.first()
        val removedPlaylistIds = mutableListOf<String>()

        currentPlaylists.forEach { playlist ->
            val shouldContain = playlist.id in playlistIds
            val hasSong = songId in playlist.songIds
            when {
                shouldContain && !hasSong -> {
                    addSongsToPlaylist(playlist.id, listOf(songId))
                }
                !shouldContain && hasSong -> {
                    removeSongFromPlaylist(playlist.id, songId)
                    removedPlaylistIds.add(playlist.id)
                }
            }
        }
        return removedPlaylistIds
    }

    suspend fun removeSongFromPlaylist(playlistId: String, songIdToRemove: String) {
        editMutex.withLock {
            ensureMigratedIfNeeded()
            val existing = userPlaylistsFlow.first().find { it.id == playlistId } ?: return
            updatePlaylistLocked(existing.copy(songIds = existing.songIds.filterNot { it == songIdToRemove }))
        }
    }

    suspend fun reorderSongsInPlaylist(playlistId: String, newSongOrderIds: List<String>) {
        editMutex.withLock {
            ensureMigratedIfNeeded()
            val existing = userPlaylistsFlow.first().find { it.id == playlistId } ?: return
            updatePlaylistLocked(existing.copy(songIds = mergePlaylistOrder(existing.songIds, newSongOrderIds)))
            userPreferencesRepository.setPlaylistSongOrderMode(playlistId, "manual")
        }
    }

    /** Reorders the playlists themselves (the Playlists tab list), not any one playlist's songs. */
    suspend fun reorderPlaylists(orderedPlaylistIds: List<String>) {
        editMutex.withLock {
            ensureMigratedIfNeeded()
            val currentIds = userPlaylistsFlow.first().sortedBy { it.sortOrder }.map { it.id }
            localPlaylistDao.updatePlaylistOrder(mergePlaylistOrder(currentIds, orderedPlaylistIds))
            userPreferencesRepository.setPlaylistsSortOption(SortOption.PlaylistCustomOrder.storageKey)
        }
    }

    suspend fun setPlaylistSongOrderMode(playlistId: String, modeValue: String) =
        userPreferencesRepository.setPlaylistSongOrderMode(playlistId, modeValue)

    suspend fun clearPlaylistSongOrderMode(playlistId: String) =
        userPreferencesRepository.clearPlaylistSongOrderMode(playlistId)

    suspend fun setPlaylistSongOrderModes(modes: Map<String, String>) =
        userPreferencesRepository.setPlaylistSongOrderModes(modes)

    suspend fun setPlaylistsSortOption(optionKey: String) =
        userPreferencesRepository.setPlaylistsSortOption(optionKey)


    suspend fun getPlaylistsOnce(): List<Playlist> {
        ensureMigratedIfNeeded()
        return userPlaylistsFlow.first()
    }

    suspend fun replaceAllPlaylists(playlists: List<Playlist>) {
        ensureMigratedIfNeeded()
        localPlaylistDao.replaceAllPlaylistsTransactional(
            playlists.map { playlist -> playlist.toEntity() to playlist.songIds }
        )
        userPreferencesRepository.clearLegacyUserPlaylists()
    }

    suspend fun removeSongFromAllPlaylists(songId: String) {
        editMutex.withLock {
            ensureMigratedIfNeeded()
            val playlists = userPlaylistsFlow.first()
            playlists.forEach { playlist ->
                if (songId in playlist.songIds) {
                    updatePlaylistLocked(
                        playlist.copy(
                            songIds = playlist.songIds.filterNot { it == songId }
                        )
                    )
                }
            }
        }
    }

    suspend fun resetPlaylistPreferencesToDefaults() {
        setPlaylistSongOrderModes(emptyMap())
        setPlaylistsSortOption(SortOption.PlaylistNameAZ.storageKey)
    }

    private suspend fun ensureMigratedIfNeeded() {
        if (migrationChecked) return
        migrationMutex.withLock {
            if (migrationChecked) return
            val roomCount = localPlaylistDao.getPlaylistCount()
            if (roomCount == 0) {
                val legacy = userPreferencesRepository.getLegacyUserPlaylistsOnce()
                legacy.forEach { playlist ->
                    localPlaylistDao.upsertPlaylist(playlist.toEntity())
                    localPlaylistDao.replacePlaylistSongs(playlist.id, playlist.songIds)
                }
                if (legacy.isNotEmpty()) {
                    userPreferencesRepository.clearLegacyUserPlaylists()
                }
            }
            migrationChecked = true
        }
    }
}

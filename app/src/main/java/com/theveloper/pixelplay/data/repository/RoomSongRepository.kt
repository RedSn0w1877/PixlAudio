package com.theveloper.pixelplay.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.model.StorageFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed song access. The app is Spotify-only: rows with source_type = LOCAL (0) from older
 * installs are never surfaced. Every query is forced to filter mode ONLINE (source_type != 0), so
 * even before the one-time purge in SyncWorker has run, no on-device row can reach the UI.
 */
@Singleton
class RoomSongRepository @Inject constructor(
    private val musicDao: MusicDao
) : SongRepository {

    private val spotifyOnly = StorageFilter.ONLINE.value

    // maxSize 1000 (a Song is ~1-2 KB); jumpThreshold lets a far fast-scroller drag load around the target.
    private val defaultPagingConfig = PagingConfig(
        pageSize = 50,
        enablePlaceholders = true,
        maxSize = 1000,
        jumpThreshold = 150
    )

    override fun getPaginatedSongs(sortOption: SortOption, storageFilter: StorageFilter): Flow<PagingData<Song>> =
        Pager(config = defaultPagingConfig, pagingSourceFactory = {
            musicDao.getSongsPaginated(
                allowedParentDirs = emptyList(),
                applyDirectoryFilter = false,
                sortOrder = sortOption.storageKey,
                filterMode = spotifyOnly
            )
        }).flow.map { pagingData -> pagingData.map { entity -> entity.toSong() } }

    override fun getPaginatedFavoriteSongs(sortOption: SortOption, storageFilter: StorageFilter): Flow<PagingData<Song>> =
        Pager(config = defaultPagingConfig, pagingSourceFactory = {
            musicDao.getFavoriteSongsPaginated(
                allowedParentDirs = emptyList(),
                applyDirectoryFilter = false,
                sortOrder = sortOption.storageKey,
                filterMode = spotifyOnly
            )
        }).flow.map { pagingData -> pagingData.map { entity -> entity.toSong().copy(isFavorite = true) } }

    override suspend fun getFavoriteSongsOnce(storageFilter: StorageFilter): List<Song> =
        withContext(Dispatchers.IO) {
            musicDao.getFavoriteSongsList(
                allowedParentDirs = emptyList(),
                applyDirectoryFilter = false,
                filterMode = spotifyOnly
            ).map { entity -> entity.toSong().copy(isFavorite = true) }
        }

    override fun getFavoriteSongCountFlow(storageFilter: StorageFilter): Flow<Int> =
        musicDao.getFavoriteSongCount(
            allowedParentDirs = emptyList(),
            applyDirectoryFilter = false,
            filterMode = spotifyOnly
        )
}

package com.theveloper.pixelplay.data.repository

import androidx.paging.PagingData
import com.theveloper.pixelplay.data.model.Song
import kotlinx.coroutines.flow.Flow

/** Spotify-only library access over Room. There is no on-device (MediaStore) source any more. */
interface SongRepository {
    fun getPaginatedSongs(sortOption: com.theveloper.pixelplay.data.model.SortOption, storageFilter: com.theveloper.pixelplay.data.model.StorageFilter): Flow<PagingData<Song>>
    fun getPaginatedFavoriteSongs(
        sortOption: com.theveloper.pixelplay.data.model.SortOption,
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter
    ): Flow<PagingData<Song>>
    suspend fun getFavoriteSongsOnce(
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter
    ): List<Song>
    fun getFavoriteSongCountFlow(
        storageFilter: com.theveloper.pixelplay.data.model.StorageFilter
    ): Flow<Int>
}

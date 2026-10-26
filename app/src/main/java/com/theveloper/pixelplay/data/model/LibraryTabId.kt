package com.theveloper.pixelplay.data.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.R

@Immutable
enum class LibraryTabId(
    val storageKey: String,
    val title: String,
    @StringRes val titleRes: Int,
    val defaultSort: SortOption
) {
    SONGS("SONGS", "SONGS", R.string.library_tab_songs, SortOption.SongDateAdded),
    ALBUMS("ALBUMS", "ALBUMS", R.string.library_tab_albums, SortOption.AlbumDateAdded),
    ARTISTS("ARTIST", "ARTIST", R.string.library_tab_artists, SortOption.ArtistDateAdded),
    PLAYLISTS("PLAYLISTS", "PLAYLISTS", R.string.library_tab_playlists, SortOption.PlaylistDateCreated),
    FOLDERS("FOLDERS", "FOLDERS", R.string.library_tab_folders, SortOption.FolderNameAZ),
    LIKED("LIKED", "LIKED", R.string.library_tab_liked, SortOption.LikedSongDateLiked);

    companion object {
        fun fromStorageKey(key: String): LibraryTabId =
            entries.firstOrNull { it.storageKey == key } ?: SONGS
    }
}

fun String.toLibraryTabIdOrNull(): LibraryTabId? =
    LibraryTabId.entries.firstOrNull { it.storageKey == this }
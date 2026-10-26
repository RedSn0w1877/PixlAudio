package com.theveloper.pixelplay.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SortOptionTest {

    @Test
    fun `method option keeps a single representative per sort method`() {
        assertEquals(SortOption.SongTitleAZ, SortOption.SongTitleZA.methodOption())
        assertEquals(SortOption.SongArtist, SortOption.SongArtistDesc.methodOption())
        assertEquals(SortOption.SongDateAdded, SortOption.SongDateAddedAsc.methodOption())
        assertEquals(SortOption.SongDefaultOrder, SortOption.SongDefaultOrder.methodOption())
    }

    @Test
    fun `resolve for direction keeps method while switching order`() {
        assertEquals(
            SortOption.SongArtistDesc,
            SortOption.SongArtist.resolveForDirection(SortDirection.Descending)
        )
        assertEquals(
            SortOption.SongArtist,
            SortOption.SongArtistDesc.resolveForDirection(SortDirection.Ascending)
        )
        assertEquals(
            SortOption.SongDateAddedAsc,
            SortOption.SongDateAdded.resolveForDirection(SortDirection.Ascending)
        )
        assertEquals(
            SortOption.PlaylistDateCreated,
            SortOption.PlaylistDateCreatedAsc.resolveForDirection(SortDirection.Descending)
        )
    }

    @Test
    fun `flip direction swaps paired sort options`() {
        assertEquals(SortOption.SongTitleZA, SortOption.SongTitleAZ.flipDirection())
        assertEquals(SortOption.SongTitleAZ, SortOption.SongTitleZA.flipDirection())
        assertEquals(SortOption.LikedSongDateLikedAsc, SortOption.LikedSongDateLiked.flipDirection())
        assertEquals(SortOption.FolderSongCountAsc, SortOption.FolderSongCountDesc.flipDirection())
        assertEquals(SortOption.SongDefaultOrder, SortOption.SongDefaultOrder.flipDirection())
    }

    @Test
    fun `from storage key still resolves legacy display names`() {
        val resolved = SortOption.fromStorageKey(
            rawValue = SortOption.SongArtist.displayName,
            allowed = SortOption.SONGS,
            fallback = SortOption.SongTitleAZ
        )

        assertEquals(SortOption.SongArtist, resolved)
    }
    @Test
    fun `library tabs default to date added, newest first`() {
        assertEquals(SortOption.SongDateAdded, LibraryTabId.SONGS.defaultSort)
        assertEquals(SortOption.AlbumDateAdded, LibraryTabId.ALBUMS.defaultSort)
        assertEquals(SortOption.ArtistDateAdded, LibraryTabId.ARTISTS.defaultSort)
        assertEquals(SortOption.PlaylistDateCreated, LibraryTabId.PLAYLISTS.defaultSort)
        for (option in listOf(
            SortOption.SongDateAdded, SortOption.AlbumDateAdded, SortOption.ArtistDateAdded, SortOption.PlaylistDateCreated
        )) {
            assertEquals(SortDirection.Descending, option.direction)
        }
        assertEquals(true, SortOption.ARTISTS.contains(SortOption.ArtistDateAdded))
    }

    @Test
    fun `a missing stored sort resolves to the new default`() {
        assertEquals(
            SortOption.ArtistDateAdded,
            SortOption.fromStorageKey(null, SortOption.ARTISTS, SortOption.ArtistDateAdded)
        )
        assertEquals(
            SortOption.ArtistDateAdded,
            SortOption.fromStorageKey("artist_date_added", SortOption.ARTISTS, SortOption.ArtistNameAZ)
        )
    }
}

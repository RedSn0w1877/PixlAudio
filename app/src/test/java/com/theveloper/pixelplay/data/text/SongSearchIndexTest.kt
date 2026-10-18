package com.theveloper.pixelplay.data.text

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Test

class SongSearchIndexTest {

    private fun song(id: String, title: String, artist: String, album: String, extraArtist: String? = null) = Song(
        id = id, title = title, artist = artist, artistId = 1L,
        artists = if (extraArtist == null) emptyList()
        else listOf(ArtistRef(1L, artist, true), ArtistRef(2L, extraArtist)),
        album = album, albumId = 1L, path = "", contentUriString = "", albumArtUriString = null,
        duration = 1000L, mimeType = null, bitrate = null, sampleRate = null,
    )

    private val songs = listOf(
        song("1", "Halo", "Beyoncé", "I Am... Sasha Fierce"),
        song("2", "Crazy In Love", "Beyoncé", "Dangerously in Love", extraArtist = "JAY-Z"),
        song("3", "Skinny Love", "Bon Iver", "For Emma, Forever Ago"),
        song("4", "Nights", "Frank Ocean", "Blonde"),
    )
    private val index = SongSearchIndex(songs)

    @Test
    fun blankQueryReturnsEverythingInOrder() {
        assertThat(index.filter("   ").map { it.id }).containsExactly("1", "2", "3", "4").inOrder()
    }

    @Test
    fun matchesTitleArtistAndAlbumIgnoringCaseAndAccents() {
        assertThat(index.filter("BEYONCE").map { it.id }).containsExactly("1", "2").inOrder()
        assertThat(index.filter("blonde").map { it.id }).containsExactly("4")
        assertThat(index.filter("skinny").map { it.id }).containsExactly("3")
    }

    @Test
    fun everyWordMustMatchInAnyOrder() {
        assertThat(index.filter("love beyonce").map { it.id }).containsExactly("2")
        assertThat(index.filter("ocean nights").map { it.id }).containsExactly("4")
        assertThat(index.filter("halo ocean")).isEmpty()
    }

    @Test
    fun creditedGuestsAreSearchable() {
        assertThat(index.filter("jay-z").map { it.id }).containsExactly("2")
    }

    @Test
    fun noMatchGivesEmptyList() {
        assertThat(index.filter("zzzz")).isEmpty()
    }

    @Test
    fun keepsOriginalOrderNotRelevanceOrder() {
        assertThat(index.filter("love").map { it.id }).containsExactly("2", "3").inOrder()
    }
}

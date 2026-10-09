package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.ArtistImageRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.YouTubeSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Room re-emits an artist and their songs on ANY write to those tables. These tests pin that such a
 * re-emission (the first visit's own image-url write, a heart tap...) no longer searches YouTube Music
 * again, restarts the image job or blanks "More from", while a real change still updates the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistDetailViewModelReloadTest {

    @BeforeEach fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterEach fun resetMain() { Dispatchers.resetMain() }

    private fun song(id: String, title: String = "Song $id", favorite: Boolean = false) =
        Song.emptySong().copy(id = id, title = title, artist = "Band", album = "Album", albumId = 1L, isFavorite = favorite)

    private val artist = Artist(id = 5L, name = "Band", songCount = 2)

    private class Fixture {
        val artist = MutableStateFlow<Artist?>(null)
        val songs = MutableStateFlow<List<Song>>(emptyList())
        val repository: MusicRepository = mockk(relaxed = true)
        val images: ArtistImageRepository = mockk(relaxed = true)
        val innerTube: InnerTubeClient = mockk(relaxed = true)
        lateinit var viewModel: ArtistDetailViewModel
    }

    private fun fixture(initialArtist: Artist, initialSongs: List<Song>): Fixture {
        val f = Fixture()
        f.artist.value = initialArtist
        f.songs.value = initialSongs
        every { f.repository.getArtistById(5L) } returns f.artist
        every { f.repository.getSongsForArtist(5L) } returns f.songs
        coEvery { f.images.getEffectiveArtistImageUrl(any(), any()) } returns "https://cdn/band.jpg"
        coEvery { f.innerTube.searchSongs("Band", limit = any()) } returns listOf(
            YouTubeSearchResult("v1", "Online one", "Band", "Online album", 200),
            YouTubeSearchResult("v2", "Online two", "Band", null, 180)
        )
        f.viewModel = ArtistDetailViewModel(
            context = mockk(relaxed = true),
            musicRepository = f.repository,
            artistImageRepository = f.images,
            engagementDao = mockk<EngagementDao>(relaxed = true),
            innerTubeClient = f.innerTube,
            spotifyRepository = mockk<SpotifyRepository>(relaxed = true),
            themeStateHolder = mockk(relaxed = true),
            savedStateHandle = SavedStateHandle(mapOf("artistId" to "5"))
        )
        return f
    }

    private suspend fun Fixture.awaitState(predicate: (ArtistDetailUiState) -> Boolean): ArtistDetailUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { viewModel.uiState.first(predicate) } }

    @Test
    fun `an unrelated re-emission neither searches again nor blanks More from`() = runTest {
        val f = fixture(artist, listOf(song("a"), song("b")))
        val loaded = f.awaitState { it.moreFromArtist.isNotEmpty() && !it.isLoadingMoreFromArtist }
        assertEquals(2, loaded.moreFromArtist.sumOf { it.tracks.size })

        // 1) The first visit's own write of artists.image_url, 2) a heart tap on one song.
        f.artist.value = artist.copy(imageUrl = "https://cdn/band.jpg")
        f.songs.value = listOf(song("a", favorite = true), song("b"))
        val afterHeart = f.awaitState { state -> state.songs.any { it.id == "a" && it.isFavorite } }

        assertTrue(afterHeart.moreFromArtist.isNotEmpty(), "More from must survive a song update")
        assertFalse(afterHeart.isLoadingMoreFromArtist)
        coVerify(exactly = 1) { f.innerTube.searchSongs("Band", limit = any()) }
        coVerify(exactly = 1) { f.images.getEffectiveArtistImageUrl(5L, "Band") }
    }

    @Test
    fun `a real change of the songs updates the screen and keeps More from`() = runTest {
        val f = fixture(artist, listOf(song("a"), song("b")))
        f.awaitState { it.moreFromArtist.isNotEmpty() }

        f.songs.value = listOf(song("a"), song("b"), song("c"))
        val updated = f.awaitState { it.songs.size == 3 }

        assertEquals(setOf("a", "b", "c"), updated.songs.map { it.id }.toSet())
        assertEquals(3, updated.albumSections.sumOf { it.songs.size })
        assertTrue(updated.moreFromArtist.isNotEmpty())
        coVerify(exactly = 1) { f.innerTube.searchSongs("Band", limit = any()) }
    }

    @Test
    fun `changing the artist's custom image does restart the image work`() = runTest {
        val f = fixture(artist, listOf(song("a")))
        f.awaitState { it.moreFromArtist.isNotEmpty() }

        f.artist.value = artist.copy(customImageUri = "/data/custom.jpg")
        f.awaitState { it.artist?.customImageUri == "/data/custom.jpg" }

        coVerify(timeout = 5_000, exactly = 2) { f.images.getEffectiveArtistImageUrl(5L, "Band") }
        coVerify(exactly = 1) { f.innerTube.searchSongs("Band", limit = any()) }
    }

    @Test
    fun `screen input comparison ignores only the stored image url of an artist without a custom image`() {
        val songs = listOf(song("a"))
        assertTrue(isSameArtistScreenInput(artist, songs, artist.copy(imageUrl = "x"), songs))
        assertFalse(isSameArtistScreenInput(artist, songs, artist.copy(name = "Other"), songs))
        assertFalse(isSameArtistScreenInput(artist, songs, artist.copy(songCount = 3), songs))
        assertFalse(isSameArtistScreenInput(artist, songs, artist.copy(customImageUri = "/c.jpg"), songs))
        assertFalse(isSameArtistScreenInput(artist, songs, artist, listOf(song("a", favorite = true))))
        // With a custom image the stored URL is what is shown, so it counts.
        val custom = artist.copy(customImageUri = "/c.jpg", imageUrl = "one")
        assertFalse(isSameArtistScreenInput(custom, songs, custom.copy(imageUrl = "two"), songs))
        assertTrue(isSameArtistScreenInput(null, songs, null, songs))
        assertFalse(isSameArtistScreenInput(null, songs, artist, songs))
    }
}

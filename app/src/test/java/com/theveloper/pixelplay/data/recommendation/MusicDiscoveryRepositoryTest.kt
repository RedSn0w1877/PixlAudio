package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.YouTubeSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class MusicDiscoveryRepositoryTest {
    private val client = mockk<InnerTubeClient>()
    private val spotify = mockk<SpotifyRepository>(relaxed = true)
    private val music = mockk<MusicRepository>()
    private val taste = mockk<MusicTasteRepository> {
        every { state } returns flowOf(MusicIntelligenceState())
    }
    private val repository = MusicDiscoveryRepository(client, spotify, music, taste)
    private val base = (1..10).map {
        Song.emptySong().copy(id = "local_$it", title = "Saved $it", artist = "Seed artist", genre = null)
    }

    @Test
    fun `duplicate video versions do not crowd out unique recordings before import`() = runTest {
        val audio = YouTubeSearchResult("audio", "New track", "New artist", null, 180)
        val video = audio.copy(videoId = "video", isMusicVideo = true)
        val unique = audio.copy(videoId = "unique", title = "Another track")
        coEvery { client.searchMusic(any(), any(), any()) } returns listOf(audio, video, unique)
        every { spotify.youTubeMusicSyntheticId(any()) } answers { "synthetic_${firstArg<String>()}" }
        val songs = listOf(audio, video, unique).associate { result ->
            val id = SpotifyRepository.unifiedSongId("synthetic_${result.videoId}").toString()
            id to Song.emptySong().copy(id = id, title = result.title, artist = result.artist)
        }
        every { music.getSongsByIds(any()) } answers {
            flowOf(firstArg<List<String>>().mapNotNull(songs::get))
        }
        coEvery { spotify.importYouTubeMusicTracks(any()) } returns 2

        val mix = repository.augment(base, base, maxOnlineSongs = 2)

        coVerify(exactly = 1) { spotify.importYouTubeMusicTracks(listOf(audio, unique)) }
        assertEquals(base.size + 2, mix.size)
        assertTrue(mix.any { it.title == "New track" })
        assertTrue(mix.any { it.title == "Another track" })
        assertEquals(mix.size, mix.distinctBy(MusicRecommendationEngine::recordingKey).size)
    }

    @Test
    fun `one song library retains every selected discovery without dropping its seed`() = runTest {
        val results = (1..6).map { YouTubeSearchResult("video_$it", "Discovery $it", "Artist $it", null, 180) }
        coEvery { client.searchMusic(any(), any(), any()) } returns results
        every { spotify.youTubeMusicSyntheticId(any()) } answers { "synthetic_${firstArg<String>()}" }
        val songs = results.associate { result ->
            val id = SpotifyRepository.unifiedSongId("synthetic_${result.videoId}").toString()
            id to Song.emptySong().copy(id = id, title = result.title, artist = result.artist)
        }
        every { music.getSongsByIds(any()) } answers { flowOf(firstArg<List<String>>().mapNotNull(songs::get)) }
        coEvery { spotify.importYouTubeMusicTracks(any()) } returns results.size

        val mix = repository.augment(base.take(1), base.take(1))

        assertEquals(7, mix.size)
        assertTrue(mix.contains(base.first()))
        assertTrue(mix.map { it.id }.containsAll(songs.keys))
    }

    @Test
    fun `offline search failure preserves the entire base mix`() = runTest {
        coEvery { client.searchMusic(any(), any(), any()) } throws IOException("Offline")
        assertEquals(base, repository.augment(base, base))
        coVerify(exactly = 0) { spotify.importYouTubeMusicTracks(any()) }
    }

    @Test
    fun `discovery disabled performs no search or import`() = runTest {
        every { taste.state } returns flowOf(MusicIntelligenceState(discoveryEnabled = false))
        assertEquals(base, repository.augment(base, base))
        coVerify(exactly = 0) { client.searchMusic(any(), any(), any()) }
        coVerify(exactly = 0) { spotify.importYouTubeMusicTracks(any()) }
    }

    @Test
    fun `empty seed mix stays empty without network activity`() = runTest {
        assertEquals(emptyList<Song>(), repository.augment(emptyList(), emptyList()))
        coVerify(exactly = 0) { client.searchMusic(any(), any(), any()) }
    }
}

package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.network.deezer.DeezerApiService
import com.theveloper.pixelplay.data.network.deezer.DeezerArtist
import com.theveloper.pixelplay.data.network.deezer.DeezerSearchResponse
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtistImageRepositoryTest {

    @Test
    fun `calculateCustomImageSampleSize keeps small bitmaps at full resolution`() {
        assertEquals(1, ArtistImageRepository.calculateCustomImageSampleSize(1024, 1024))
    }

    @Test
    fun `calculateCustomImageSampleSize aggressively downsamples oversized inputs`() {
        val sampleSize = ArtistImageRepository.calculateCustomImageSampleSize(12000, 8000)

        assertTrue(sampleSize >= 4)
        assertEquals(8, sampleSize)
    }

    @Test
    fun `cancelled prefetch does not mark artist as failed for the session`() = runTest {
        val deezerApiService = mockk<DeezerApiService>()
        val musicDao = mockk<MusicDao>()
        val repository = ArtistImageRepository(deezerApiService, musicDao)
        val firstAttemptStarted = CompletableDeferred<Unit>()
        val searchAttempts = AtomicInteger(0)
        val rawUrl = "https://cdn-images.dzcdn.net/images/artist/250x250-000000-80-0-0.jpg"
        val upgradedUrl = "https://cdn-images.dzcdn.net/images/artist/1000x1000-000000-80-0-0.jpg"

        coEvery { musicDao.getArtistIdByNormalizedName("Artist Name") } returns 42L
        coEvery { musicDao.getArtistImageUrl(42L) } returns null
        coEvery { musicDao.getArtistImageUrlByNormalizedName("Artist Name") } returns null
        coJustRun { musicDao.updateArtistImageUrl(42L, any()) }
        coEvery { deezerApiService.searchArtist("Artist Name", 1) } coAnswers {
            when (searchAttempts.incrementAndGet()) {
                1 -> {
                    firstAttemptStarted.complete(Unit)
                    awaitCancellation()
                }

                else -> DeezerSearchResponse(
                    data = listOf(
                        DeezerArtist(
                            id = 7L,
                            name = "Artist Name",
                            pictureBig = rawUrl
                        )
                    )
                )
            }
        }

        val prefetchJob = launch {
            repository.prefetchArtistImages(listOf(42L to "Artist Name"))
        }
        firstAttemptStarted.await()
        prefetchJob.cancel()
        prefetchJob.join()

        val imageUrl = repository.getArtistImageUrl("Artist Name", 42L)

        assertEquals(upgradedUrl, imageUrl)
        assertEquals(2, searchAttempts.get())
        coVerify(exactly = 1) { musicDao.updateArtistImageUrl(42L, upgradedUrl) }
    }

    @Test
    fun `prefetch saves images in batches and keeps misses across trimMemory`() = runTest {
        val deezerApiService = mockk<DeezerApiService>()
        val musicDao = mockk<MusicDao>()
        val repository = ArtistImageRepository(deezerApiService, musicDao)
        val saved = mutableListOf<List<com.theveloper.pixelplay.data.database.ArtistImageUpdate>>()
        val url = "https://cdn-images.dzcdn.net/images/artist/1000x1000-000000-80-0-0.jpg"
        val artists = (1L..48L).map { it to "Artist $it" }
        artists.forEach { (id, name) ->
            coEvery { musicDao.getArtistIdByNormalizedName(name) } returns id
            coEvery { musicDao.getArtistImageUrl(id) } returns null
            coEvery { musicDao.getArtistImageUrlByNormalizedName(name) } returns null
        }
        coEvery { musicDao.updateArtistImageUrls(any()) } coAnswers { saved += firstArg<List<com.theveloper.pixelplay.data.database.ArtistImageUpdate>>() }
        val searches = AtomicInteger(0)
        coEvery { deezerApiService.searchArtist(any(), 1) } coAnswers {
            searches.incrementAndGet()
            val name = firstArg<String>()
            // Odd artists exist on Deezer, even ones do not.
            if (name.removePrefix("Artist ").toInt() % 2 == 1) {
                DeezerSearchResponse(data = listOf(DeezerArtist(id = 1L, name = name, pictureXl = url)))
            } else DeezerSearchResponse(data = emptyList())
        }

        repository.prefetchArtistImages(artists)
        assertEquals(24, saved.sumOf { it.size })
        assertTrue(saved.size <= 3, "one commit per ~24 artists, not per artist")
        coVerify(exactly = 0) { musicDao.updateArtistImageUrl(any(), any()) }

        repository.trimMemory()
        val before = searches.get()
        repository.prefetchArtistImages(artists.filter { it.first % 2 == 0L })
        assertEquals(before, searches.get(), "misses are not searched again after the UI is hidden")
    }
}

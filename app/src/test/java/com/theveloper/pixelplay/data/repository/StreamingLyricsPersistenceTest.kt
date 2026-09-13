package com.theveloper.pixelplay.data.repository

import android.content.Context
import com.theveloper.pixelplay.data.database.LyricsDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.lyrics.LrcLibApiService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StreamingLyricsPersistenceTest {
    @TempDir lateinit var directory: File

    @Test
    fun `interrupted legacy JSON cache does not abort a playlist preflight`() = runTest {
        val context = mockk<Context>(relaxed = true) { every { filesDir } returns directory }
        val cache = File(directory, "lyrics/spotify_broken.json")
        cache.parentFile!!.mkdirs()
        cache.writeText("{\"wordByWordLyrics\":")
        val repository = LyricsRepositoryImpl(
            context, mockk<LrcLibApiService>(relaxed = true), mockk<LyricsDao>(relaxed = true),
            mockk<OkHttpClient>(relaxed = true)
        )
        assertNull(repository.getStoredLyrics(Song.emptySong().copy(id = "spotify_broken", lyrics = null)))
    }

    @Test
    fun `streaming word sync survives a new repository instance with no network`() = runTest {
        val context = mockk<Context>(relaxed = true) { every { filesDir } returns directory }
        val api = mockk<LrcLibApiService>(relaxed = true)
        val dao = mockk<LyricsDao>(relaxed = true)
        fun repository() = LyricsRepositoryImpl(context, api, dao, mockk<OkHttpClient>(relaxed = true))
        val song = Song.emptySong().copy(id = "spotify_abc123", lyrics = "[00:01.00]Hello world")
        val content = "[00:01.00]<00:01.00>Hello <00:01.50>world"

        repository().updateLyrics(song, content)
        val stored = repository().getStoredLyrics(song)

        assertEquals(listOf(1000, 1500), stored?.first?.synced?.single()?.words?.map { it.time })
        assertTrue(File(directory, "lyrics/spotify_abc123.json").isFile)
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { api.getLyrics(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.searchLyrics(any(), any(), any(), any()) }
    }
}

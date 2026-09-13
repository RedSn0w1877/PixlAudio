package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.worker.AiWorkerManager
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DailyMixPersistenceTest {
    private val manager = mockk<DailyMixManager>()
    private val preferences = mockk<UserPreferencesRepository>(relaxed = true)
    private val music = mockk<MusicRepository>()
    private val workers = mockk<AiWorkerManager>(relaxed = true)
    private val holder = DailyMixStateHolder(manager, preferences, music, workers, mockk())
    private val saved = Song.emptySong().copy(id = "spotify_saved", title = "Discovery")

    private fun savedToday() {
        every { preferences.lastDailyMixUpdateFlow } returns flowOf(System.currentTimeMillis())
        every { preferences.dailyMixSongIdsFlow } returns flowOf(listOf(saved.id))
        every { preferences.yourMixSongIdsFlow } returns flowOf(emptyList())
        every { music.getSongsByIds(listOf(saved.id)) } returns flowOf(listOf(saved))
    }

    @Test fun `startup and library sync retain today's saved catalog discovery`() = runBlocking {
        savedToday()
        holder.initialize(this)
        holder.updateDailyMix(flowOf(emptySet()))!!.join()
        holder.updateDailyMix(flowOf(emptySet()))!!.join()
        assertEquals(listOf(saved), holder.dailyMixSongs.value)
        coVerify(exactly = 0) { music.getAllSongsOnce() }
        verify(exactly = 0) { workers.enqueueDailyMixRefresh() }
        holder.onCleared()
    }

    @Test fun `slow database hydration cannot overwrite a newer published mix`() = runBlocking {
        savedToday()
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        every { music.getSongsByIds(listOf(saved.id)) } returns flow {
            reading.complete(Unit)
            resume.await()
            emit(listOf(saved))
        }
        holder.initialize(this)
        val loading = holder.loadPersistedDailyMix()!!
        reading.await()
        val fresh = saved.copy(id = "fresh", title = "Fresh discovery")
        holder.publishBackgroundMix(listOf(fresh), emptyList())
        resume.complete(Unit)
        loading.join()
        assertEquals(listOf(fresh), holder.dailyMixSongs.value)
        holder.onCleared()
    }

    @Test fun `explicit refresh can replace today's persisted mix`() = runBlocking {
        savedToday()
        val fresh = saved.copy(id = "fresh")
        coEvery { music.getAllSongsOnce() } returns listOf(fresh)
        coEvery { manager.generateDailyMix(any(), any(), any()) } returns listOf(fresh)
        coEvery { manager.generateYourMix(any(), any(), any()) } returns listOf(fresh)
        holder.initialize(this)
        holder.updateDailyMix(flowOf(emptySet()), force = true)!!.join()
        assertEquals(listOf(fresh), holder.dailyMixSongs.value)
        coVerify { preferences.saveDailyMixSongIds(listOf(fresh.id)) }
        verify(exactly = 1) { workers.enqueueDailyMixRefresh() }
        holder.onCleared()
    }
}

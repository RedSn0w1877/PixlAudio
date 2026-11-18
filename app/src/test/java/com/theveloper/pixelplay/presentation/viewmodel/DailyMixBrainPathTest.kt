package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.MixPlanner
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.brain.BrainEngineChoice
import com.theveloper.pixelplay.data.brain.BrainItemTag
import com.theveloper.pixelplay.data.brain.BrainMixes
import com.theveloper.pixelplay.data.brain.BrainPlayTags
import com.theveloper.pixelplay.data.brain.BrainSwitch
import com.theveloper.pixelplay.data.brain.MixReason
import com.theveloper.pixelplay.data.brain.MixView
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.worker.AiWorkerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which engine publishes the mixes (docs/brain-spec.md, 14 stage 2): the brain when it owns them, the legacy path otherwise. */
class DailyMixBrainPathTest {
    private val manager = mockk<DailyMixManager>()
    private val preferences = mockk<UserPreferencesRepository>(relaxed = true)
    private val music = mockk<MusicRepository>()
    private val workers = mockk<AiWorkerManager>(relaxed = true)
    private val mixes = mockk<BrainMixes>()
    private val playTags = mockk<BrainPlayTags>(relaxed = true)
    private val switch = mockk<BrainSwitch>()
    private val holder = DailyMixStateHolder(manager, preferences, music, workers, mockk(), mixes, playTags, switch).also { it.startupQuiet = {} }

    private fun song(id: String) = Song.emptySong().copy(id = id, title = "T $id")

    private fun view(kind: Int, library: List<String>, novel: List<String>): MixView {
        val songs = (library + novel).map(::song)
        return MixView(
            kind = kind, builtAtMs = 1L, recId = 77L, songs = songs,
            reasons = novel.associateWith { MixReason("radio_fans", "Somebody") },
            tags = songs.associate { it.id to BrainItemTag(surface = "dailyMix", arm = Arm.nameOf(Arm.FAV), chosen = true) },
            novelIds = novel.toSet(), seedLists = 3,
        )
    }

    private fun savedToday(daily: MixView, your: MixView) {
        coEvery { switch.engine() } returns BrainEngineChoice.BRAIN
        coEvery { mixes.saved(MixPlanner.KIND_DAILY) } returns daily
        coEvery { mixes.saved(MixPlanner.KIND_YOUR) } returns your
    }

    @Test fun `the brain's saved mixes are published with their reasons, and the legacy keys keep only the library songs`() = runBlocking {
        savedToday(view(MixPlanner.KIND_DAILY, listOf("a", "b"), listOf("-9")), view(MixPlanner.KIND_YOUR, listOf("c"), listOf("-8", "-7")))
        holder.initialize(this)
        holder.updateDailyMix(flowOf(emptySet()))!!.join()

        assertEquals(listOf("a", "b", "-9"), holder.dailyMixSongs.value.map { it.id })
        assertEquals(listOf("c", "-8", "-7"), holder.yourMixSongs.value.map { it.id })
        assertEquals(setOf("-9", "-8", "-7"), holder.novelSongIds.value)
        assertEquals("Somebody", holder.mixReasons.value["-9"]!!.arg)
        coVerify { preferences.saveDailyMixSongIds(listOf("a", "b")) }
        coVerify { preferences.saveYourMixSongIds(listOf("c")) }
        coVerify(exactly = 0) { music.getAllSongsOnce() }
        verify(exactly = 0) { workers.enqueueDailyMixRefresh() }
        holder.onCleared()
    }

    @Test fun `a play of a mix announces its rows to the queue before it starts`() = runBlocking {
        val daily = view(MixPlanner.KIND_DAILY, listOf("a"), listOf("-9"))
        savedToday(daily, view(MixPlanner.KIND_YOUR, listOf("c"), emptyList()))
        holder.initialize(this)
        holder.updateDailyMix(flowOf(emptySet()))!!.join()
        holder.expectMixPlay("Daily Mix", isYourMix = false)
        verify { playTags.expect(queueName = "Daily Mix", default = any(), bySongId = daily.tags, startsStation = any()) }
        holder.onCleared()
    }

    @Test fun `with no state the brain does not own the mixes and the legacy mixes build as they always did`() = runBlocking {
        val fresh = song("fresh")
        coEvery { switch.engine() } returns BrainEngineChoice.BRAIN
        coEvery { mixes.saved(any()) } returns null
        coEvery { mixes.hasState() } returns false
        every { preferences.lastDailyMixUpdateFlow } returns flowOf(0L)
        coEvery { music.getAllSongsOnce() } returns listOf(fresh)
        coEvery { manager.generateDailyMix(any(), any(), any()) } returns listOf(fresh)
        coEvery { manager.generateYourMix(any(), any(), any()) } returns listOf(fresh)
        holder.initialize(this)
        holder.updateDailyMix(flowOf(emptySet()), force = true)!!.join()
        assertEquals(listOf(fresh), holder.dailyMixSongs.value)
        assertTrue(holder.novelSongIds.value.isEmpty())
        coVerify(exactly = 0) { mixes.today(any(), any()) }
        verify(exactly = 1) { workers.enqueueDailyMixRefresh() }
        holder.onCleared()
    }

    @Test fun `with the engine on legacy the brain is not asked at all`() = runBlocking {
        val fresh = song("fresh")
        coEvery { switch.engine() } returns BrainEngineChoice.LEGACY
        every { preferences.lastDailyMixUpdateFlow } returns flowOf(0L)
        coEvery { music.getAllSongsOnce() } returns listOf(fresh)
        coEvery { manager.generateDailyMix(any(), any(), any()) } returns listOf(fresh)
        coEvery { manager.generateYourMix(any(), any(), any()) } returns listOf(fresh)
        holder.initialize(this)
        holder.updateDailyMix(flowOf(emptySet()), force = true)!!.join()
        assertEquals(listOf(fresh), holder.dailyMixSongs.value)
        coVerify(exactly = 0) { mixes.saved(any()) }
        coVerify(exactly = 0) { mixes.hasState() }
        holder.onCleared()
    }

    @Test fun `Regenerate draws a new brain list for Your Mix and leaves the Daily Mix alone`() = runBlocking {
        savedToday(view(MixPlanner.KIND_DAILY, listOf("a"), listOf("-9")), view(MixPlanner.KIND_YOUR, listOf("c"), emptyList()))
        coEvery { mixes.hasState() } returns true
        coEvery { mixes.today(MixPlanner.KIND_YOUR, true) } returns view(MixPlanner.KIND_YOUR, listOf("d", "e"), listOf("-5"))
        holder.initialize(this)
        holder.updateDailyMix(flowOf(emptySet()))!!.join()
        holder.regenerateYourMix(flowOf(emptySet()))
        // regenerate runs on the holder's scope; wait for it
        while (holder.isRegeneratingYourMix.value || holder.yourMixSongs.value.first().id == "c") kotlinx.coroutines.delay(5)
        assertEquals(listOf("d", "e", "-5"), holder.yourMixSongs.value.map { it.id })
        assertEquals(listOf("a", "-9"), holder.dailyMixSongs.value.map { it.id })
        assertEquals(setOf("-9", "-5"), holder.novelSongIds.value)
        holder.onCleared()
    }
}

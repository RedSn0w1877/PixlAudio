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
    private val holder = DailyMixStateHolder(manager, preferences, music, workers, mixes, playTags, switch).also { it.startupQuiet = {} }

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

    /** The brain has today's mixes saved, and it is the first launch of the day (nothing saved in the preferences yet). */
    private fun savedToday(daily: MixView, your: MixView) {
        coEvery { switch.engine() } returns BrainEngineChoice.BRAIN
        coEvery { switch.brainStateSeen() } returns true
        every { preferences.lastDailyMixUpdateFlow } returns flowOf(0L)
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

    @Test fun `a launch on a day that has a saved mix does not touch the brain, and a mix screen reads it`() = runBlocking {
        val daily = view(MixPlanner.KIND_DAILY, listOf("a", "b"), listOf("-9"))
        val your = view(MixPlanner.KIND_YOUR, listOf("c"), listOf("-8"))
        coEvery { switch.engine() } returns BrainEngineChoice.BRAIN
        coEvery { switch.brainStateSeen() } returns true
        coEvery { mixes.saved(MixPlanner.KIND_DAILY) } returns daily
        coEvery { mixes.saved(MixPlanner.KIND_YOUR) } returns your
        every { preferences.lastDailyMixUpdateFlow } returns flowOf(System.currentTimeMillis())
        every { preferences.dailyMixSongIdsFlow } returns flowOf(listOf("a", "b"))
        every { preferences.yourMixSongIdsFlow } returns flowOf(listOf("c"))
        every { music.getSongsByIds(listOf("a", "b")) } returns flowOf(listOf(song("a"), song("b")))
        every { music.getSongsByIds(listOf("c")) } returns flowOf(listOf(song("c")))
        holder.startupQuiet = { error("the app start must not wait for, or read, the brain") }
        holder.initialize(this)

        holder.updateDailyMix(flowOf(emptySet()))!!.join()

        assertEquals(listOf("a", "b"), holder.dailyMixSongs.value.map { it.id })
        coVerify(exactly = 0) { mixes.saved(any()) }
        coVerify(exactly = 0) { mixes.hasState() }
        coVerify(exactly = 0) { mixes.today(any(), any()) }

        holder.onMixSurfaceOpened(full = true)!!.join()
        assertEquals(listOf("a", "b", "-9"), holder.dailyMixSongs.value.map { it.id })
        assertEquals(setOf("-9", "-8"), holder.novelSongIds.value)
        holder.onCleared()
    }

    @Test fun `Home's mix card reads only the saved mix, with no state load and no build`() = runBlocking {
        val daily = view(MixPlanner.KIND_DAILY, listOf("a"), listOf("-9"))
        val your = view(MixPlanner.KIND_YOUR, listOf("c"), emptyList())
        coEvery { switch.engine() } returns BrainEngineChoice.BRAIN
        coEvery { switch.brainStateSeen() } returns true
        coEvery { mixes.saved(MixPlanner.KIND_DAILY) } returns daily
        coEvery { mixes.saved(MixPlanner.KIND_YOUR) } returns your
        holder.initialize(this)

        holder.onMixSurfaceOpened(full = false)!!.join()

        assertEquals(listOf("a", "-9"), holder.dailyMixSongs.value.map { it.id })
        coVerify(exactly = 0) { mixes.hasState() }
        coVerify(exactly = 0) { mixes.today(any(), any()) }
        holder.onCleared()
    }

    @Test fun `a brain database that cannot be read is the legacy mixes, not a crash`() = runBlocking {
        val fresh = song("fresh")
        coEvery { switch.engine() } returns BrainEngineChoice.BRAIN
        coEvery { switch.brainStateSeen() } returns true
        coEvery { mixes.saved(any()) } throws java.io.IOException("disk full")
        every { preferences.lastDailyMixUpdateFlow } returns flowOf(0L)
        coEvery { music.getAllSongsOnce() } returns listOf(fresh)
        coEvery { manager.generateDailyMix(any(), any(), any()) } returns listOf(fresh)
        coEvery { manager.generateYourMix(any(), any(), any()) } returns listOf(fresh)
        holder.initialize(this)

        holder.updateDailyMix(flowOf(emptySet()))!!.join()

        assertEquals(listOf(fresh), holder.dailyMixSongs.value)
        holder.onCleared()
    }

    @Test fun `with no brain state seen the legacy mixes build at once, without waiting for the brain`() = runBlocking {
        val fresh = song("fresh")
        coEvery { switch.engine() } returns BrainEngineChoice.BRAIN
        coEvery { switch.brainStateSeen() } returns false
        every { preferences.lastDailyMixUpdateFlow } returns flowOf(0L)
        coEvery { music.getAllSongsOnce() } returns listOf(fresh)
        coEvery { manager.generateDailyMix(any(), any(), any()) } returns listOf(fresh)
        coEvery { manager.generateYourMix(any(), any(), any()) } returns listOf(fresh)
        holder.startupQuiet = { error("no wait when there is no brain state") }
        holder.initialize(this)

        holder.updateDailyMix(flowOf(emptySet()))!!.join()

        assertEquals(listOf(fresh), holder.dailyMixSongs.value)
        coVerify(exactly = 0) { mixes.saved(any()) }
        holder.onCleared()
    }
}

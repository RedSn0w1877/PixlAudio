package com.theveloper.pixelplay.data.service.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Spatial audio render swap against fake players (cloud-audio-spec §6.3): the render follows the main player's own
 * play state, a render player that fails gives the main player back, and a level written while a render is heard
 * reaches ReplayGain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpatialRenditionSwapTest {

    private fun render(profile: SpatialProfile = SpatialProfile.STUDIO) = SpatialRender(
        videoId = "J7p4bzqLvCw",
        profile = profile,
        file = File("cloud_audio/J7p4bzqLvCw/sp-${profile.id}.flac"),
        sampleRate = 48_000,
        samples = 48_000L * 200,
        durationMs = 200_000,
        headroomDb = 4f,
    )

    /** The engine's player: the test sets its state, and its listeners hear the play-state changes the test makes. */
    private class FakeMain {
        var vol = 1f
        var playingNow = true
        var pwr = true
        var pos = 30_000L
        val listeners = mutableListOf<Player.Listener>()
        val player: Player = mockk(relaxed = true) {
            every { volume } answers { vol }
            every { setVolume(any()) } answers { vol = firstArg() }
            every { isPlaying } answers { playingNow }
            every { playWhenReady } answers { pwr }
            every { playbackState } returns Player.STATE_READY
            every { currentPosition } answers { pos }
            every { duration } returns 200_000L
            every { currentMediaItem } answers { MediaItem.Builder().setMediaId("A").build() }
            every { playbackParameters } returns PlaybackParameters.DEFAULT
            every { addListener(any()) } answers { listeners += firstArg<Player.Listener>() }
            every { removeListener(any()) } answers { listeners -= firstArg<Player.Listener>() }
        }

        /** Play, a stall or a seek that rebuffers: what ExoPlayer reports as `isPlaying`. */
        fun setPlaying(playing: Boolean) {
            playingNow = playing
            listeners.toList().forEach { it.onIsPlayingChanged(playing) }
        }
    }

    /** A second player on a local render: ready at once, sought where it is told. */
    private class FakeRender {
        var vol = 1f
        var pwr = false
        var pos = 0L
        var error: ExoPlaybackException? = null
        var released = false
        val listeners = mutableListOf<Player.Listener>()
        val player: ExoPlayer = mockk(relaxed = true) {
            every { volume } answers { vol }
            every { setVolume(any()) } answers { vol = firstArg() }
            every { playWhenReady } answers { pwr }
            every { setPlayWhenReady(any()) } answers { pwr = firstArg() }
            every { playbackState } answers { if (error != null) Player.STATE_IDLE else Player.STATE_READY }
            every { currentPosition } answers { pos }
            every { seekTo(any<Long>()) } answers { pos = firstArg() }
            every { playerError } answers { error }
            every { addListener(any()) } answers { listeners += firstArg<Player.Listener>() }
            every { removeListener(any()) } answers { listeners -= firstArg<Player.Listener>() }
            every { release() } answers { released = true }
        }

        fun fail() {
            val e = mockk<ExoPlaybackException>(relaxed = true)
            error = e
            listeners.toList().forEach { it.onPlayerError(e) }
        }
    }

    private class Rig(scope: TestScope) {
        val main = FakeMain()
        val renders = mutableListOf<FakeRender>()
        val absorbed = mutableListOf<Float>()
        val swap = SpatialRenditionSwap(
            context = mockk<Context>(relaxed = true),
            scope = scope.backgroundScope,
            onLevelAbsorbed = { absorbed += it },
            newPlayer = { FakeRender().also { renders += it }.player },
            clock = { scope.testScheduler.currentTime },
        )

        init {
            swap.onSongChanged("A", main.player)
        }
    }

    // ── The render follows the main player (finding 4) ──────────────────────────────────────────────────────────

    @Test
    fun `the render waits while the main player buffers, then starts again from its position`() = runTest {
        val rig = Rig(this)
        assertTrue(rig.swap.fadeIn(rig.main.player, render()) { true })
        val heard = rig.renders.single()
        assertTrue(heard.pwr)

        // A seek outside the buffer (or a stall): the stream reloads with play still on.
        rig.main.setPlaying(false)
        assertFalse(heard.pwr, "the local render must not run ahead of a stream that rebuffers")

        // The stream moves on 1.5 s later in the song (a seek forward that reloaded, say).
        rig.main.pos = 31_500L
        rig.main.setPlaying(true)
        assertTrue(heard.pwr)
        assertEquals(31_500L, heard.pos, "the render starts again where the main player is")
    }

    @Test
    fun `a render started while the main player buffers waits for it`() = runTest {
        val rig = Rig(this)
        rig.main.playingNow = false // play is on, but the stream is still loading

        assertTrue(rig.swap.fadeIn(rig.main.player, render()) { true })
        val heard = rig.renders.single()
        assertFalse(heard.pwr)

        rig.main.setPlaying(true)
        assertTrue(heard.pwr)
    }

    @Test
    fun `a pause still pauses the render and a resume of a ready stream starts it`() = runTest {
        val rig = Rig(this)
        assertTrue(rig.swap.fadeIn(rig.main.player, render()) { true })
        val heard = rig.renders.single()

        rig.main.pwr = false
        rig.main.playingNow = false
        rig.swap.onPlayWhenReadyChanged(false)
        assertFalse(heard.pwr)

        rig.main.pwr = true
        rig.main.playingNow = true
        rig.swap.onPlayWhenReadyChanged(true)
        assertTrue(heard.pwr)
    }

    @Test
    fun `the main player's listener goes with the render`() = runTest {
        val rig = Rig(this)
        assertTrue(rig.swap.fadeIn(rig.main.player, render()) { true })
        assertEquals(1, rig.main.listeners.size)

        rig.swap.teardown()
        assertTrue(rig.main.listeners.isEmpty())
    }

    // ── A render player that fails (finding 5) ──────────────────────────────────────────────────────────────────

    @Test
    fun `a heard render that fails gives the main player back at its level`() = runTest {
        val rig = Rig(this)
        assertTrue(rig.swap.fadeIn(rig.main.player, render()) { true })
        assertEquals(0f, rig.main.vol)
        val heard = rig.renders.single()

        heard.fail()

        assertNull(rig.swap.audibleRender, "the status must not stay on the cloud render")
        assertEquals(1f, rig.main.vol, "the main player must be heard again")
        assertTrue(heard.released)
        assertFalse(rig.swap.ownsMasterVolume)
    }

    @Test
    fun `a render that fails during its fade-in is never faded in`() = runTest {
        val rig = Rig(this)
        val result = async { rig.swap.fadeIn(rig.main.player, render()) { true } }
        advanceTimeBy(100) // part way through the 250 ms fade
        rig.renders.single().fail()
        advanceUntilIdle()

        assertFalse(result.await())
        assertNull(rig.swap.audibleRender)
        assertEquals(1f, rig.main.vol)
        assertTrue(rig.renders.single().released)
    }

    @Test
    fun `an incoming render that fails during a profile change leaves the heard one`() = runTest {
        val rig = Rig(this)
        val studio = render(SpatialProfile.STUDIO)
        assertTrue(rig.swap.fadeIn(rig.main.player, studio) { true })

        val result = async { rig.swap.crossTo(rig.main.player, render(SpatialProfile.WIDE)) { true } }
        advanceTimeBy(100)
        val (old, incoming) = rig.renders
        incoming.fail()
        advanceUntilIdle()

        assertFalse(result.await())
        assertSame(studio, rig.swap.audibleRender)
        assertEquals(1f, old.vol, "the render heard comes back to its level")
        assertTrue(incoming.released)
        assertFalse(old.released)
        assertEquals(0f, rig.main.vol)
    }

    // ── Levels written while a render is heard (finding 6) ──────────────────────────────────────────────────────

    @Test
    fun `a level written while a render is heard reaches ReplayGain once`() = runTest {
        val rig = Rig(this)
        assertTrue(rig.swap.fadeIn(rig.main.player, render()) { true })
        val heard = rig.renders.single()

        // The person's slider (or ReplayGain) writes the session player.
        rig.main.vol = 0.6f
        assertTrue(rig.swap.onMainVolumeChanged(rig.main.player, 0.6f))
        assertEquals(listOf(0.6f), rig.absorbed)
        assertEquals(0.6f, heard.vol)
        assertEquals(0f, rig.main.vol, "the main player stays silent")

        // The swap's own write back to 0 is its echo: not the person's.
        assertTrue(rig.swap.onMainVolumeChanged(rig.main.player, 0f))
        assertEquals(listOf(0.6f), rig.absorbed)
    }

    @Test
    fun `nothing reaches ReplayGain while no render is heard`() = runTest {
        val rig = Rig(this)
        assertFalse(rig.swap.onMainVolumeChanged(rig.main.player, 0.6f))
        assertTrue(rig.absorbed.isEmpty())
    }
}

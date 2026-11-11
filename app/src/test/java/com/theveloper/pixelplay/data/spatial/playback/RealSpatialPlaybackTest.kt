package com.theveloper.pixelplay.data.spatial.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.service.player.SpatialRenditionSwap
import com.theveloper.pixelplay.data.spatial.SpatialLibrary
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import com.theveloper.pixelplay.data.spatial.SpatialRouteMode
import com.theveloper.pixelplay.data.spatial.SpatialSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * When [RealSpatialPlayback] starts a render mid-song (cloud-audio-spec §6.3), against a swap that records its calls:
 * only for the song the main player plays, and never while Sing takes over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealSpatialPlaybackTest {

    @TempDir
    lateinit var dir: File

    private val videoId = "J7p4bzqLvCw"

    private inner class Rig(scope: TestScope) {
        val render = SpatialRender(
            videoId = videoId,
            profile = SpatialProfile.STUDIO,
            file = File(dir, "sp-studio.flac").apply { writeBytes(ByteArray(16)) },
            sampleRate = 48_000,
            samples = 48_000L * 200,
            durationMs = 200_000,
            headroomDb = 4f,
        )

        // A strict context: the table read and the route monitor fail quietly (no table, the phone's own route).
        val stage = SpatialStage(mockk<Context>())
        val library = mockk<SpatialLibrary>(relaxed = true) {
            every { events } returns MutableSharedFlow()
            coEvery { localRender(videoId, SpatialProfile.STUDIO) } returns render
        }
        val dao = mockk<SpotifyDao> { coEvery { getMatchedVideoId(any()) } returns videoId }

        /** The song the engine's master player is on. */
        var mainSongId = "A"
        val main = mockk<Player>(relaxed = true) {
            every { currentMediaItem } answers { MediaItem.Builder().setMediaId(mainSongId).build() }
            every { currentPosition } returns 30_000L
            every { duration } returns 200_000L
            every { playbackState } returns Player.STATE_READY
        }

        var singAudible = false
        var singOwns = false

        /** What the swap holds heard, and the `stillWanted` of every fade-in it was asked for. */
        var heard: SpatialRender? = null
        val stillWanted = mutableListOf<() -> Boolean>()
        val swap = mockk<SpatialRenditionSwap>(relaxed = true)

        val hostScope = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob())
        val playback = RealSpatialPlayback(mockk<Context>(), stage, library, dao)

        init {
            every { swap.audibleRender } answers { heard }
            every { swap.isRamping } returns false
            coEvery { swap.fadeIn(any(), any(), any()) } answers {
                stillWanted += thirdArg<() -> Boolean>()
                heard = secondArg()
                playback.onSwapChanged()
                true
            }
            coEvery { swap.fadeToMain(any()) } answers {
                val wasHeard = heard != null
                heard = null
                if (wasHeard) playback.onSwapChanged()
                wasHeard
            }
            playback.ioDispatcher = StandardTestDispatcher(scope.testScheduler)
            playback.attach(object : RealSpatialPlayback.Host {
                override val scope: CoroutineScope get() = hostScope
                override val swap: SpatialRenditionSwap get() = this@Rig.swap
                override fun mainPlayer(): Player = main
                override fun isRemote(): Boolean = false
                override fun isSingAudible(): Boolean = singAudible
                override fun singOwnsVolume(): Boolean = singOwns
                override fun isTransitionRunning(): Boolean = false
                override fun onLevelLiftChanged() = Unit
            })
        }

        /** Studio selected, `always` route; song [song] starts from its stream (the stage's report). */
        fun play(song: String) {
            playback.apply(SpatialSettings(profile = SpatialProfile.STUDIO, routeMode = SpatialRouteMode.ALWAYS, cloud = false))
            playback.onItemChanged(song, "sp$song", "spotify://sp$song")
            stage.reportListener!!.invoke(SpatialStreamReport(song, render = null, formatSupported = true))
        }

        fun fadeIns(count: Int) = coVerify(exactly = count) { swap.fadeIn(any(), any(), any()) }

        fun close() {
            playback.detach()
            hostScope.cancel()
        }
    }

    // ── The song the main player plays (finding 1) ──────────────────────────────────────────────────────────────

    @Test
    fun `the render of the song playing fades in`() = runTest {
        val rig = Rig(this)
        rig.play("A")
        advanceUntilIdle()

        coVerify(exactly = 1) { rig.swap.fadeIn(rig.main, rig.render, any()) }
        rig.close()
    }

    @Test
    fun `a render of the last song never starts over the song an overlap handed over to`() = runTest {
        val rig = Rig(this)
        rig.play("A")
        // A crossfade made B's player the master; no transition reached the service, so A is still the song here.
        rig.mainSongId = "B"
        advanceUntilIdle()

        rig.fadeIns(0)
        rig.close()
    }

    @Test
    fun `a fade-in under way stops being wanted when the main player leaves the song`() = runTest {
        val rig = Rig(this)
        rig.play("A")
        advanceUntilIdle()
        val wanted = rig.stillWanted.single()
        assertTrue(wanted())

        rig.mainSongId = "B"
        assertFalse(wanted())
        rig.close()
    }

    // ── Sing takes over (finding 2) ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a render that gave way to Sing does not fade back in while Sing prepares`() = runTest {
        val rig = Rig(this)
        rig.play("A")
        advanceUntilIdle()
        rig.fadeIns(1)

        // Sing tapped: the render fades back, then Sing prepares, lines up and waits, with nothing of it heard yet.
        rig.playback.yieldToSing()
        advanceUntilIdle()

        coVerify(exactly = 1) { rig.swap.fadeToMain(any()) }
        rig.fadeIns(1)
        rig.close()
    }

    @Test
    fun `a fade-in under way stops being wanted when Sing is tapped`() = runTest {
        val rig = Rig(this)
        rig.play("A")
        advanceUntilIdle()
        val wanted = rig.stillWanted.single()
        rig.heard = null // still lining up: nothing heard, nothing to fade back

        rig.playback.yieldToSing()

        assertFalse(wanted())
        rig.close()
    }

    @Test
    fun `the render comes back when Sing does not take over`() = runTest {
        val rig = Rig(this)
        rig.play("A")
        advanceUntilIdle()
        rig.playback.yieldToSing()
        advanceUntilIdle()

        // No instrumental, or the switch was cancelled.
        rig.playback.endSingSwitch()
        advanceUntilIdle()

        rig.fadeIns(2)
        rig.close()
    }

    @Test
    fun `the render waits for Sing to end, then comes back`() = runTest {
        val rig = Rig(this)
        rig.play("A")
        advanceUntilIdle()
        rig.playback.yieldToSing()
        advanceUntilIdle()

        // Sing's instrumental is heard.
        rig.singAudible = true
        rig.singOwns = true
        rig.playback.onSingChanged()
        rig.playback.endSingSwitch()
        advanceUntilIdle()
        rig.fadeIns(1)

        // The fade back to the vocals: the stage first, Sing still owns the volume.
        rig.playback.prepareForSingReturn()
        advanceUntilIdle()
        rig.fadeIns(1)

        // Sing is over.
        rig.singAudible = false
        rig.singOwns = false
        rig.playback.onSingChanged()
        rig.playback.endSingReturn()
        advanceUntilIdle()
        rig.fadeIns(2)
        rig.close()
    }
}

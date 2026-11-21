package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.Player
import com.theveloper.pixelplay.brain.HostVoice
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The music duck of the spoken host against a fake player and virtual time: the ramp, the exact ends, giving the level back, and the rules
 * that keep it from fighting the other writers of the master volume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostDuckTest {

    private class Fixture(scope: TestScope, start: Float = 0.8f) {
        var vol = start
        val writes = mutableListOf<Float>()
        val player: Player = mockk(relaxed = true) {
            every { volume } answers { vol }
            every { setVolume(any()) } answers { vol = firstArg(); writes.add(vol); Unit }
        }
        val ended = mutableListOf<String>()
        val duck = HostDuck(scope = { scope }, player = { player }, now = { scope.testScheduler.currentTime }).also { it.onEnded = { r -> ended.add(r) } }
    }

    @Test
    fun `it brings the music down to a third of its level over a quarter of a second and holds it`() = runTest {
        val f = Fixture(this)
        assertTrue(f.duck.begin())
        assertTrue(f.duck.isActive)
        advanceTimeBy(HostVoice.RAMP_DOWN_MS + 10)
        assertEquals(0.8f * HostVoice.DUCK_PERMILLE / 1000f, f.vol, 0.001f)
        // It never goes below the level, and it holds there.
        assertTrue(f.writes.all { it >= 0.8f * HostVoice.DUCK_PERMILLE / 1000f - 0.001f && it <= 0.8f + 0.001f })
        advanceTimeBy(2_000L)
        assertEquals(0.8f * HostVoice.DUCK_PERMILLE / 1000f, f.vol, 0.001f)
    }

    @Test
    fun `it gives back exactly the level it found, a ReplayGain level included`() = runTest {
        val f = Fixture(this, start = 0.62f)
        f.duck.begin()
        advanceTimeBy(HostVoice.RAMP_DOWN_MS + 10)
        f.duck.end()
        advanceTimeBy(HostVoice.RAMP_UP_MS + 10)
        assertEquals(0.62f, f.vol, 0.0001f)
        assertFalse(f.duck.isActive)
        assertTrue(f.ended.isEmpty())
    }

    @Test
    fun `a quick end is over in about a tenth of a second`() = runTest {
        val f = Fixture(this)
        f.duck.begin()
        advanceTimeBy(HostVoice.RAMP_DOWN_MS + 10)
        f.duck.end(quick = true)
        advanceTimeBy(HostVoice.RAMP_UP_QUICK_MS + 30)
        assertEquals(0.8f, f.vol, 0.0001f)
    }

    @Test
    fun `ending during the ramp down climbs from where it is`() = runTest {
        val f = Fixture(this)
        f.duck.begin()
        advanceTimeBy(100)
        val mid = f.vol
        assertTrue(mid < 0.8f && mid > 0.8f * 0.35f)
        f.duck.end(quick = true)
        advanceTimeBy(HostVoice.RAMP_UP_QUICK_MS + 30)
        assertEquals(0.8f, f.vol, 0.0001f)
    }

    @Test
    fun `abort puts the level back at once and says why`() = runTest {
        val f = Fixture(this)
        f.duck.begin()
        advanceTimeBy(HostVoice.RAMP_DOWN_MS + 10)
        f.duck.abort("transition")
        assertEquals(0.8f, f.vol, 0.0001f)
        assertFalse(f.duck.isActive)
        assertEquals(listOf("transition"), f.ended)
        // Nothing keeps writing afterwards.
        val count = f.writes.size
        advanceTimeBy(1_000L)
        assertEquals(count, f.writes.size)
    }

    @Test
    fun `abort when not ducked does nothing and says nothing`() = runTest {
        val f = Fixture(this)
        f.duck.abort("focus")
        assertTrue(f.ended.isEmpty())
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun `its own writes are kept from the rest of the app, another writer's are not`() = runTest {
        val f = Fixture(this)
        f.duck.begin()
        advanceTimeBy(HostVoice.RAMP_DOWN_MS + 10)
        // The service asks with each value the player reports: the duck's own are taken.
        assertTrue(f.duck.onMainVolumeChanged(f.vol))
        assertTrue(f.ended.isEmpty())
        // A ReplayGain write mid-duck: not ours. The duck lets go WITHOUT writing and the host stops.
        val before = f.writes.size
        f.vol = 0.5f
        assertFalse(f.duck.onMainVolumeChanged(0.5f))
        assertEquals(listOf("volume_taken"), f.ended)
        assertFalse(f.duck.isActive)
        advanceTimeBy(1_000L)
        assertEquals(before, f.writes.size)
        assertEquals(0.5f, f.vol, 0.0001f)
    }

    @Test
    fun `the echo of its last write is still taken for a moment after it ended, later it is not`() = runTest {
        val f = Fixture(this)
        f.duck.begin()
        advanceTimeBy(HostVoice.RAMP_DOWN_MS + 10)
        f.duck.end()
        advanceTimeBy(HostVoice.RAMP_UP_MS + 10)
        assertTrue(f.duck.onMainVolumeChanged(0.8f))
        advanceTimeBy(1_000L)
        assertFalse(f.duck.onMainVolumeChanged(0.8f))
    }

    @Test
    fun `nothing audible is not ducked and a second duck is refused`() = runTest {
        val silent = Fixture(this, start = 0f)
        assertFalse(silent.duck.begin())
        assertFalse(silent.duck.isActive)
        val f = Fixture(this)
        assertTrue(f.duck.begin())
        assertFalse(f.duck.begin())
    }

    @Test
    fun `the steps are the shared ramp`() = runTest {
        val f = Fixture(this, start = 1f)
        f.duck.begin()
        advanceTimeBy(HostVoice.RAMP_DOWN_MS + 10)
        // Every step the duck wrote is a value the shared rule gives for some elapsed time.
        val allowed = (0..HostVoice.RAMP_DOWN_MS.toInt()).map { HostVoice.ramp(1000, HostVoice.DUCK_PERMILLE, it.toLong(), HostVoice.RAMP_DOWN_MS) / 1000f }
        assertTrue(f.writes.all { w -> allowed.any { kotlin.math.abs(it - w) < 0.002f } })
    }
}

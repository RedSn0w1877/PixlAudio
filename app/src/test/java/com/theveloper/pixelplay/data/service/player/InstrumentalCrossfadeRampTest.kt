package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstrumentalCrossfadeRampTest {

    private class VolumePlayer(var volume: Float) {
        val player: Player = mockk(relaxed = true) {
            every { this@mockk.volume } answers { this@VolumePlayer.volume }
            every { this@mockk.volume = any() } answers { this@VolumePlayer.volume = firstArg() }
        }
    }

    @Test
    fun `a finished ramp leaves from silent and to at full volume`() = runTest {
        val from = VolumePlayer(1f)
        val to = VolumePlayer(0f)

        val completed = rampCrossfade(from.player, to.player, durationMs = 700L, stepMs = 16L) { true }

        assertTrue(completed)
        assertEquals(0f, from.volume)
        assertEquals(1f, to.volume)
    }

    @Test
    fun `a ramp whose shadow player was released stops instead of silencing the main player`() = runTest {
        val main = VolumePlayer(1f)
        val shadow = VolumePlayer(0f)
        var checks = 0

        // The song changes after a few steps: the shadow is torn down and the check turns false.
        val completed = rampCrossfade(main.player, shadow.player, durationMs = 700L, stepMs = 16L) {
            ++checks <= 5
        }

        assertFalse(completed)
        // Not driven down to 0: the ramp must not finish the job on a player that moved on.
        assertTrue(main.volume > 0f)
        assertTrue(shadow.volume < 1f)
    }

    @Test
    fun `a ramp that is invalid from the start does not touch either player`() = runTest {
        val from = VolumePlayer(1f)
        val to = VolumePlayer(0f)

        val completed = rampCrossfade(from.player, to.player, durationMs = 700L, stepMs = 16L) { false }

        assertFalse(completed)
        assertEquals(1f, from.volume)
        assertEquals(0f, to.volume)
    }
}

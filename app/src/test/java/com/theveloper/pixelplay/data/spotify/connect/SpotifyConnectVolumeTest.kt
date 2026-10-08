package com.theveloper.pixelplay.data.spotify.connect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The volume keys on a Connect device (shared spec with iOS `SpotifyConnectVolumeTests`). */
class SpotifyConnectVolumeTest {

    @Test fun `percent maps to the 0-20 remote scale, one unit per press`() {
        assertEquals(0, SpotifyConnectVolume.percentToSteps(0))
        assertEquals(0, SpotifyConnectVolume.percentToSteps(2))
        assertEquals(1, SpotifyConnectVolume.percentToSteps(3))
        assertEquals(9, SpotifyConnectVolume.percentToSteps(47))
        assertEquals(10, SpotifyConnectVolume.percentToSteps(48))
        assertEquals(20, SpotifyConnectVolume.percentToSteps(100))
        assertEquals(10, SpotifyConnectVolume.percentToSteps(null))
        assertEquals(20, SpotifyConnectVolume.percentToSteps(130))
        // Android's volume panel shows current + 1 unit for a second after each press: one press
        // must always be exactly one unit, from any volume.
        for (p in 0..95) {
            assertEquals(SpotifyConnectVolume.percentToSteps(p) + 1, SpotifyConnectVolume.percentToSteps(p + 5), "at $p")
        }
    }

    @Test fun `steps map back to percent and clamp`() {
        assertEquals(0, SpotifyConnectVolume.stepsToPercent(-1))
        assertEquals(100, SpotifyConnectVolume.stepsToPercent(21))
        for (k in 0..20) {
            assertEquals(5 * k, SpotifyConnectVolume.stepsToPercent(SpotifyConnectVolume.percentToSteps(5 * k)))
        }
        assertEquals(100, SpotifyConnectVolume.adjusted(98, 1))
        assertEquals(0, SpotifyConnectVolume.adjusted(3, -1))
        assertEquals(55, SpotifyConnectVolume.adjusted(null, 1))
    }

    @Test fun `the keys drive speakers that take volume commands, never a phone or tablet`() {
        for (type in listOf("Speaker", "TV", "CastAudio", "Unknown", "Computer", null)) {
            assertTrue(SpotifyConnectVolume.keysControlDevice(type, supportsVolume = true), "$type")
        }
        for (type in listOf("Smartphone", "smartphone", "Tablet")) {
            assertFalse(SpotifyConnectVolume.keysControlDevice(type, supportsVolume = true), type)
        }
        assertFalse(SpotifyConnectVolume.keysControlDevice("Speaker", supportsVolume = false))
    }

    @Test fun `the session reports local for a phone, fixed without volume, else the 0-20 scale`() {
        assertNull(SpotifyConnectVolume.remoteFor("Smartphone", supportsVolume = true, percent = 40))
        assertNull(SpotifyConnectVolume.remoteFor("Tablet", supportsVolume = false, percent = 40))
        assertEquals(SpotifyConnectVolume.Remote(0, 0, false), SpotifyConnectVolume.remoteFor("Speaker", false, 40))
        assertEquals(SpotifyConnectVolume.Remote(20, 9, true), SpotifyConnectVolume.remoteFor("Speaker", true, 47))
    }

    @Test fun `the lane sends the first change at once, then the latest value every 300 ms`() {
        var lane = SpotifyConnectVolumeLane().enqueue(55)
        var step = lane.next(nowMs = 1_000)
        assertEquals(SpotifyConnectVolumeLane.Action.Send(55), step.second)
        lane = step.first
        // While one request is in flight, nothing else goes; newer values replace each other.
        lane = lane.enqueue(60).enqueue(65)
        step = lane.next(nowMs = 1_100)
        assertEquals(SpotifyConnectVolumeLane.Action.Idle, step.second)
        lane = step.first.completed()
        step = lane.next(nowMs = 1_150)
        assertEquals(SpotifyConnectVolumeLane.Action.Wait(150), step.second)
        step = step.first.next(nowMs = 1_300)
        assertEquals(SpotifyConnectVolumeLane.Action.Send(65), step.second)
        lane = step.first.completed()
        assertTrue(lane.isIdle)
        assertEquals(SpotifyConnectVolumeLane.Action.Idle, lane.next(nowMs = 2_000).second)
        // Clamped.
        assertEquals(100, SpotifyConnectVolumeLane().enqueue(130).pending)
    }

    @Test fun `the lane waits out a Retry-After gate and then sends the latest value`() {
        var lane = SpotifyConnectVolumeLane().gate(untilMs = 5_000).enqueue(40)
        assertEquals(SpotifyConnectVolumeLane.Action.Wait(4_000), lane.next(nowMs = 1_000).second)
        // A 429 on the request: that value goes again once the gate opens.
        var step = SpotifyConnectVolumeLane().enqueue(60).next(nowMs = 10_000)
        lane = step.first.rateLimited(retryAfterMs = 5_000, nowMs = 10_100)
        assertEquals(60, lane.pending)
        assertEquals(SpotifyConnectVolumeLane.Action.Wait(5_000), lane.next(nowMs = 10_100).second)
        // A newer value enqueued meanwhile wins over the one that was refused.
        step = SpotifyConnectVolumeLane().enqueue(60).next(nowMs = 10_000)
        lane = step.first.enqueue(70).rateLimited(retryAfterMs = 5_000, nowMs = 10_100)
        assertEquals(70, lane.pending)
        assertEquals(SpotifyConnectVolumeLane.Action.Send(70), lane.next(nowMs = 15_100).second)
        // Any other failure drops that value and frees the lane.
        step = SpotifyConnectVolumeLane().enqueue(30).next(nowMs = 0)
        assertTrue(step.first.failed().isIdle)
    }
}

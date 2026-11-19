package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.data.brain.store.PlayStamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Repeat activation as the app feeds it (docs/brain-spec.md, 5.6): from the plays the log holds. */
class ActivationFeaturesTest {
    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L

    private fun stamp(rid: Long, agoMs: Long, endReason: Int = EndReason.TRACK_DONE, playedMs: Int = 200_000) = PlayStamp(
        rid = rid, startedAtMs = now - agoMs, playedMs = playedMs, durationMs = 200_000, endReason = endReason,
        flags = 0, origin = Origin.LIVE, surface = Surface.PLAYLIST, arm = Arm.NONE,
    )

    @Test
    fun `a track played an hour ago is hotter than one played last month, and one never played has no entry`() {
        val features = ActivationFeatures.compute(
            listOf(stamp(1L, hour), stamp(2L, 30 * 24 * hour)),
            now,
        )
        assertTrue(features.getValue(1L) > features.getValue(2L))
        assertFalse(features.containsKey(3L))
        assertTrue(features.values.all { it in 0.0..1.0 })
    }

    @Test
    fun `more recent plays add up, in whatever order the rows come`() {
        val one = ActivationFeatures.compute(listOf(stamp(1L, 2 * hour)), now).getValue(1L)
        val many = ActivationFeatures.compute(
            listOf(stamp(1L, 2 * hour), stamp(1L, 50 * hour), stamp(1L, 26 * hour)).shuffled(java.util.Random(4)),
            now,
        ).getValue(1L)
        assertTrue(many > one)
    }

    @Test
    fun `a play that counts for nothing adds nothing`() {
        // Put the phone down: ended by "stopped" with under 30 s heard is x0 (4.3).
        val silent = stamp(1L, hour, endReason = EndReason.STOPPED, playedMs = 5_000)
        assertEquals(emptyMap<Long, Double>(), ActivationFeatures.compute(listOf(silent), now))
    }
}

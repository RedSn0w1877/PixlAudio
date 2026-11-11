package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.Player
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeekPositionPolicyTest {

    @Test
    fun `seeks and seek adjustments are seek discontinuities`() {
        assertTrue(SeekPositionPolicy.isSeekDiscontinuity(Player.DISCONTINUITY_REASON_SEEK))
        assertTrue(SeekPositionPolicy.isSeekDiscontinuity(Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT))
    }

    @Test
    fun `playback driven discontinuities are not seeks`() {
        assertFalse(SeekPositionPolicy.isSeekDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION))
        assertFalse(SeekPositionPolicy.isSeekDiscontinuity(Player.DISCONTINUITY_REASON_SKIP))
        assertFalse(SeekPositionPolicy.isSeekDiscontinuity(Player.DISCONTINUITY_REASON_REMOVE))
        assertFalse(SeekPositionPolicy.isSeekDiscontinuity(Player.DISCONTINUITY_REASON_INTERNAL))
    }

    @Test
    fun `no override means nothing to drop`() {
        assertFalse(SeekPositionPolicy.shouldDropPausedOverride(null, 0L, 1_500L))
    }

    @Test
    fun `an override that matches the new position is the slider seek landing and is kept`() {
        assertFalse(SeekPositionPolicy.shouldDropPausedOverride(90_000L, 90_000L, 1_500L))
        assertFalse(SeekPositionPolicy.shouldDropPausedOverride(90_000L, 91_000L, 1_500L))
    }

    @Test
    fun `a restart to zero drops an override left from an earlier slider seek`() {
        assertTrue(SeekPositionPolicy.shouldDropPausedOverride(90_000L, 0L, 1_500L))
    }

    @Test
    fun `a seek elsewhere drops the stale override`() {
        assertTrue(SeekPositionPolicy.shouldDropPausedOverride(30_000L, 120_000L, 1_500L))
    }
}

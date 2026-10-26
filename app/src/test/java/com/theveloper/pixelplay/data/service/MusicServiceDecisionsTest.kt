package com.theveloper.pixelplay.data.service

import androidx.media3.common.Player
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MusicServiceDecisionsTest {

    @Test
    fun `a freshly replaced queue is skipped forward even when its index is lower than the last one`() {
        // Previous queue ended on index 5; the new queue starts on index 0 with a blocked song.
        assertTrue(
            isBlockedSkipForward(
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                shuffleEnabled = false,
                lastIndex = 5,
                currentIndex = 0
            )
        )
    }

    @Test
    fun `a manual skip back to a blocked song keeps going backwards`() {
        assertFalse(
            isBlockedSkipForward(
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
                shuffleEnabled = false,
                lastIndex = 5,
                currentIndex = 4
            )
        )
    }

    @Test
    fun `auto advance shuffle and an unknown last index go forward`() {
        assertTrue(isBlockedSkipForward(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, false, 5, 2))
        assertTrue(isBlockedSkipForward(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, true, 5, 2))
        assertTrue(isBlockedSkipForward(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, false, -1, 2))
        assertTrue(isBlockedSkipForward(null, false, 3, 3))
    }

    @Test
    fun `a play press before the restore wins over a queue saved paused`() {
        assertTrue(
            shouldPlayRestoredQueue(
                snapshotWasPlaying = false,
                keepPlayingInBackground = true,
                playPressedBeforeRestore = true
            )
        )
    }

    @Test
    fun `restore follows the saved state when nothing was pressed`() {
        assertTrue(shouldPlayRestoredQueue(true, true, false))
        assertFalse(shouldPlayRestoredQueue(false, true, false))
        // Background playback off: a queue saved as playing is restored paused.
        assertFalse(shouldPlayRestoredQueue(true, false, false))
    }

    @Test
    fun `a queue saved as playing long ago is restored paused`() {
        assertTrue(shouldPlayRestoredQueue(true, true, false, snapshotAgeMs = 30_000L))
        assertFalse(shouldPlayRestoredQueue(true, true, false, snapshotAgeMs = 3 * 60 * 60 * 1000L))
        // A play press still wins.
        assertTrue(shouldPlayRestoredQueue(true, true, true, snapshotAgeMs = 3 * 60 * 60 * 1000L))
    }
}

package com.theveloper.pixelplay.data.service.cast

import androidx.media3.common.Player
import com.google.android.gms.cast.MediaStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CastAutoplayTriggerTest {

    private val playing = MediaStatus.PLAYER_STATE_PLAYING
    private val buffering = MediaStatus.PLAYER_STATE_BUFFERING
    private val idle = MediaStatus.PLAYER_STATE_IDLE
    private val none = MediaStatus.IDLE_REASON_NONE
    private val finished = MediaStatus.IDLE_REASON_FINISHED

    @Test
    fun `a new current item is a transition, the same one again is nothing`() {
        val t = CastAutoplayTrigger()
        assertEquals(CastAutoplayTrigger.Event.TRANSITION, t.onStatus(1, buffering, none))
        assertEquals(CastAutoplayTrigger.Event.NONE, t.onStatus(1, playing, none))
        assertEquals(CastAutoplayTrigger.Event.TRANSITION, t.onStatus(2, playing, none))
    }

    @Test
    fun `an item seen while the receiver is idle counts once it buffers`() {
        val t = CastAutoplayTrigger()
        t.onStatus(1, playing, none)
        assertEquals(CastAutoplayTrigger.Event.NONE, t.onStatus(7, idle, none))
        assertEquals(CastAutoplayTrigger.Event.TRANSITION, t.onStatus(7, buffering, none))
    }

    @Test
    fun `the queue played to its end is reported once, until something plays again`() {
        val t = CastAutoplayTrigger()
        t.onStatus(3, playing, none)
        assertEquals(CastAutoplayTrigger.Event.QUEUE_ENDED, t.onStatus(3, idle, finished))
        assertEquals(CastAutoplayTrigger.Event.NONE, t.onStatus(3, idle, finished))
        assertEquals(CastAutoplayTrigger.Event.TRANSITION, t.onStatus(4, playing, none))
        assertEquals(CastAutoplayTrigger.Event.QUEUE_ENDED, t.onStatus(4, idle, finished))
    }

    @Test
    fun `a new session starts from nothing`() {
        val t = CastAutoplayTrigger()
        t.onStatus(5, playing, none)
        t.reset()
        assertEquals(CastAutoplayTrigger.Event.TRANSITION, t.onStatus(5, playing, none))
    }

    @Test
    fun `current is found by item id, and after the end it is the last known item`() {
        val ids = intArrayOf(10, 11, 12)
        assertEquals(1, CastQueueIndex.resolve(ids, 11, ended = false, lastKnownIndex = 0))
        assertEquals(-1, CastQueueIndex.resolve(ids, 0, ended = false, lastKnownIndex = 1))
        assertEquals(2, CastQueueIndex.resolve(ids, 0, ended = true, lastKnownIndex = 2))
        assertEquals(2, CastQueueIndex.resolve(ids, 0, ended = true, lastKnownIndex = 9))
        assertEquals(-1, CastQueueIndex.resolve(IntArray(0), 0, ended = true, lastKnownIndex = 0))
    }

    @Test
    fun `cast repeat modes map to the player's`() {
        assertEquals(Player.REPEAT_MODE_OFF, CastQueueIndex.playerRepeatMode(MediaStatus.REPEAT_MODE_REPEAT_OFF))
        assertEquals(Player.REPEAT_MODE_ONE, CastQueueIndex.playerRepeatMode(MediaStatus.REPEAT_MODE_REPEAT_SINGLE))
        assertEquals(Player.REPEAT_MODE_ALL, CastQueueIndex.playerRepeatMode(MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE))
    }
}

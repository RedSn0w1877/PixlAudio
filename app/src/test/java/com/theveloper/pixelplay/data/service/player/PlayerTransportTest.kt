package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every song is a Spotify stream now: between a tap and the first audio the player is BUFFERING,
 * and after a failed resolve it is IDLE. The transport commands must still do something visible.
 */
class PlayerTransportTest {

    private class FakePlayer(
        var state: Int,
        var playWhenReady: Boolean,
        var itemCount: Int = 3,
        var positionMs: Long = 0L,
        var seekable: Boolean = true,
        var mediaId: String = "song",
    ) {
        val player: Player = mockk(relaxed = true) {
            every { playbackState } answers { state }
            every { this@mockk.playWhenReady } answers { this@FakePlayer.playWhenReady }
            every { mediaItemCount } answers { itemCount }
            every { currentPosition } answers { positionMs }
            every { currentMediaItem } answers { MediaItem.Builder().setMediaId(mediaId).build() }
            every { isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) } answers { seekable }
        }
    }

    // ---- play / pause -------------------------------------------------------------------

    @Test
    fun `pause works while the song is still loading`() {
        val fake = FakePlayer(Player.STATE_BUFFERING, playWhenReady = true)

        val nowPlaying = PlayerTransport.togglePlayPause(fake.player)

        assertFalse(nowPlaying)
        verify(exactly = 1) { fake.player.pause() }
        verify(exactly = 0) { fake.player.play() }
    }

    @Test
    fun `pause works while playing`() {
        val fake = FakePlayer(Player.STATE_READY, playWhenReady = true)

        PlayerTransport.togglePlayPause(fake.player)

        verify(exactly = 1) { fake.player.pause() }
    }

    @Test
    fun `play after a pause just plays`() {
        val fake = FakePlayer(Player.STATE_READY, playWhenReady = false)

        val nowPlaying = PlayerTransport.togglePlayPause(fake.player)

        assertTrue(nowPlaying)
        verify(exactly = 0) { fake.player.prepare() }
        verify(exactly = 1) { fake.player.play() }
    }

    @Test
    fun `play on a player that stopped after a failed song prepares it first`() {
        val fake = FakePlayer(Player.STATE_IDLE, playWhenReady = true)

        PlayerTransport.togglePlayPause(fake.player)

        // IDLE counts as "not playing" even though playWhenReady is still set: retry, never pause.
        verifyOrder {
            fake.player.prepare()
            fake.player.play()
        }
        verify(exactly = 0) { fake.player.pause() }
    }

    @Test
    fun `play at the end of the queue restarts from the beginning of the current song`() {
        val fake = FakePlayer(Player.STATE_ENDED, playWhenReady = true)

        PlayerTransport.togglePlayPause(fake.player)

        verifyOrder {
            fake.player.seekToDefaultPosition()
            fake.player.play()
        }
    }

    @Test
    fun `an empty idle player is not prepared`() {
        val fake = FakePlayer(Player.STATE_IDLE, playWhenReady = false, itemCount = 0)

        PlayerTransport.play(fake.player)

        verify(exactly = 0) { fake.player.prepare() }
    }

    @Test
    fun `play intent covers loading and playing only`() {
        assertTrue(PlayerTransport.isPlayIntent(FakePlayer(Player.STATE_BUFFERING, true).player))
        assertTrue(PlayerTransport.isPlayIntent(FakePlayer(Player.STATE_READY, true).player))
        assertFalse(PlayerTransport.isPlayIntent(FakePlayer(Player.STATE_READY, false).player))
        assertFalse(PlayerTransport.isPlayIntent(FakePlayer(Player.STATE_IDLE, true).player))
        assertFalse(PlayerTransport.isPlayIntent(FakePlayer(Player.STATE_ENDED, true).player))
    }

    // ---- skipping -----------------------------------------------------------------------

    @Test
    fun `next on an idle player prepares it so the next song actually plays`() {
        val fake = FakePlayer(Player.STATE_IDLE, playWhenReady = true)

        PlayerTransport.skipNext(fake.player)

        verifyOrder {
            fake.player.prepare()
            fake.player.seekToNext()
        }
    }

    @Test
    fun `previous restarts the song after ten seconds and goes back before that`() {
        val late = FakePlayer(Player.STATE_READY, playWhenReady = true, positionMs = 30_000L)
        PlayerTransport.skipPrevious(late.player)
        verify(exactly = 1) { late.player.seekTo(0L) }
        verify(exactly = 0) { late.player.seekToPrevious() }

        val early = FakePlayer(Player.STATE_READY, playWhenReady = true, positionMs = 4_000L)
        PlayerTransport.skipPrevious(early.player)
        verify(exactly = 1) { early.player.seekToPrevious() }
        verify(exactly = 0) { early.player.seekTo(any<Long>()) }
    }

    // ---- seeking ------------------------------------------------------------------------

    @Test
    fun `seek on a ready song is applied at once`() {
        val fake = FakePlayer(Player.STATE_READY, playWhenReady = true, seekable = true)
        val pending = PendingSeek()

        val applied = PlayerTransport.seekInCurrent(fake.player, 42_000L, pending)

        assertTrue(applied)
        assertFalse(pending.isHeld)
        verify(exactly = 1) { fake.player.seekTo(42_000L) }
    }

    @Test
    fun `seek on a song that is still loading is held and lands when it is ready`() {
        val fake = FakePlayer(Player.STATE_BUFFERING, playWhenReady = true, seekable = false)
        val pending = PendingSeek()

        val applied = PlayerTransport.seekInCurrent(fake.player, 90_000L, pending)

        assertFalse(applied)
        assertTrue(pending.isHeld)
        verify(exactly = 0) { fake.player.seekTo(any<Long>()) }

        // Still buffering: nothing yet.
        assertFalse(pending.applyIfReady(fake.player))

        // The stream became seekable and ready.
        fake.state = Player.STATE_READY
        fake.seekable = true
        assertTrue(pending.applyIfReady(fake.player))
        assertFalse(pending.isHeld)
        verify(exactly = 1) { fake.player.seekTo(90_000L) }
    }

    @Test
    fun `a held seek is dropped when the song changed meanwhile`() {
        val fake = FakePlayer(Player.STATE_BUFFERING, playWhenReady = true, seekable = false, mediaId = "a")
        val pending = PendingSeek()
        PlayerTransport.seekInCurrent(fake.player, 10_000L, pending)

        fake.mediaId = "b"
        fake.state = Player.STATE_READY
        fake.seekable = true

        assertFalse(pending.applyIfReady(fake.player))
        assertFalse(pending.isHeld)
        verify(exactly = 0) { fake.player.seekTo(any<Long>()) }
    }

    @Test
    fun `seek on an idle player prepares it and is held until seekable`() {
        val fake = FakePlayer(Player.STATE_IDLE, playWhenReady = true, seekable = false)
        val pending = PendingSeek()

        PlayerTransport.seekInCurrent(fake.player, 5_000L, pending)

        verify(exactly = 1) { fake.player.prepare() }
        assertTrue(pending.isHeld)
    }

    @Test
    fun `a negative seek target is clamped to the start`() {
        val fake = FakePlayer(Player.STATE_READY, playWhenReady = true)

        PlayerTransport.seekInCurrent(fake.player, -500L)

        verify(exactly = 1) { fake.player.seekTo(0L) }
    }
}

class PlaybackErrorSkipPolicyTest {

    @Test
    fun `a song that never started is skipped`() {
        val policy = PlaybackErrorSkipPolicy()

        assertEquals(
            PlaybackErrorSkipPolicy.Decision.SKIP_TO_NEXT,
            policy.onError(nowMs = 1_000L, hasNext = true, mediaId = "a", positionMs = 0L)
        )
    }

    @Test
    fun `the last song of the queue stops instead of skipping`() {
        val policy = PlaybackErrorSkipPolicy()

        assertEquals(
            PlaybackErrorSkipPolicy.Decision.STOP,
            policy.onError(nowMs = 1_000L, hasNext = false, mediaId = "a")
        )
    }

    @Test
    fun `three failures in a row are skipped and the fourth stops`() {
        val policy = PlaybackErrorSkipPolicy(maxConsecutiveSkips = 3)

        repeat(3) { index ->
            assertEquals(
                PlaybackErrorSkipPolicy.Decision.SKIP_TO_NEXT,
                policy.onError(nowMs = 1_000L + index, hasNext = true, mediaId = "s$index")
            )
        }
        assertEquals(
            PlaybackErrorSkipPolicy.Decision.STOP,
            policy.onError(nowMs = 1_010L, hasNext = true, mediaId = "s3")
        )
    }

    @Test
    fun `audio playing again resets the count`() {
        val policy = PlaybackErrorSkipPolicy(maxConsecutiveSkips = 1)
        assertEquals(PlaybackErrorSkipPolicy.Decision.SKIP_TO_NEXT, policy.onError(1_000L, true, "a"))

        policy.onPlaybackStarted()

        assertEquals(PlaybackErrorSkipPolicy.Decision.SKIP_TO_NEXT, policy.onError(2_000L, true, "b"))
    }

    @Test
    fun `old failures fall out of the window`() {
        val policy = PlaybackErrorSkipPolicy(maxConsecutiveSkips = 1, windowMs = 60_000L)
        assertEquals(PlaybackErrorSkipPolicy.Decision.SKIP_TO_NEXT, policy.onError(0L, true, "a"))

        assertEquals(PlaybackErrorSkipPolicy.Decision.SKIP_TO_NEXT, policy.onError(120_000L, true, "b"))
    }

    @Test
    fun `a song that played for a while is retried in place once, then skipped`() {
        val policy = PlaybackErrorSkipPolicy()

        assertEquals(
            PlaybackErrorSkipPolicy.Decision.RETRY_CURRENT,
            policy.onError(nowMs = 1_000L, hasNext = true, mediaId = "a", positionMs = 47_000L)
        )
        assertEquals(
            PlaybackErrorSkipPolicy.Decision.SKIP_TO_NEXT,
            policy.onError(nowMs = 2_000L, hasNext = true, mediaId = "a", positionMs = 48_000L)
        )
    }

    @Test
    fun `a different song gets its own in-place retry`() {
        val policy = PlaybackErrorSkipPolicy()
        policy.onError(1_000L, true, "a", 47_000L)

        assertEquals(
            PlaybackErrorSkipPolicy.Decision.RETRY_CURRENT,
            policy.onError(2_000L, true, "b", 30_000L)
        )
    }
}

class QueueAttachGateTest {

    @Test
    fun `skips are not held when no queue is being attached`() {
        val gate = QueueAttachGate()

        assertFalse(gate.deferIfAttaching(QueueAttachGate.Skip.NEXT))
    }

    @Test
    fun `skips tapped while the queue attaches are replayed in order once it is there`() {
        val gate = QueueAttachGate()
        val token = gate.begin()

        assertTrue(gate.deferIfAttaching(QueueAttachGate.Skip.NEXT))
        assertTrue(gate.deferIfAttaching(QueueAttachGate.Skip.NEXT))
        assertTrue(gate.deferIfAttaching(QueueAttachGate.Skip.PREVIOUS))

        assertEquals(
            listOf(QueueAttachGate.Skip.NEXT, QueueAttachGate.Skip.NEXT, QueueAttachGate.Skip.PREVIOUS),
            gate.end(token)
        )
        assertFalse(gate.isAttaching)
        assertFalse(gate.deferIfAttaching(QueueAttachGate.Skip.NEXT))
    }

    @Test
    fun `a superseded attach cannot release or clear the newer one`() {
        val gate = QueueAttachGate()
        val stale = gate.begin()
        val current = gate.begin()
        gate.deferIfAttaching(QueueAttachGate.Skip.NEXT)

        assertEquals(emptyList<QueueAttachGate.Skip>(), gate.end(stale))
        gate.abandon(stale)

        assertTrue(gate.isAttaching)
        assertEquals(listOf(QueueAttachGate.Skip.NEXT), gate.end(current))
    }

    @Test
    fun `an abandoned attach forgets the held skips`() {
        val gate = QueueAttachGate()
        val token = gate.begin()
        gate.deferIfAttaching(QueueAttachGate.Skip.NEXT)

        gate.abandon(token)

        assertFalse(gate.isAttaching)
        assertEquals(emptyList<QueueAttachGate.Skip>(), gate.end(token))
    }

    @Test
    fun `only a handful of skips are held`() {
        val gate = QueueAttachGate(maxDeferred = 5)
        val token = gate.begin()

        repeat(20) { gate.deferIfAttaching(QueueAttachGate.Skip.NEXT) }

        assertEquals(5, gate.end(token).size)
    }
}

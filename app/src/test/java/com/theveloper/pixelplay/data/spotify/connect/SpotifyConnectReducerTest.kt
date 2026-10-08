package com.theveloper.pixelplay.data.spotify.connect

import com.theveloper.pixelplay.data.network.spotify.SpotifyDeviceDto
import com.theveloper.pixelplay.data.network.spotify.SpotifyLinkedFromDto
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlaybackStateDto
import com.theveloper.pixelplay.data.network.spotify.SpotifyPlayerItemDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The session reducer (ported from iOS `SpotifyConnectReducerTests`). */
class SpotifyConnectReducerTest {
    private val window = SpotifyConnectWindow(
        listOf("spotify:track:a", "spotify:track:b", "spotify:track:c"),
        listOf(3, 5, 6)
    )

    private fun state(
        position: Int = 0,
        playing: Boolean = true,
        progress: Long = 10_000,
        anchor: Long = 100_000,
        hasMore: Boolean = false
    ) = SpotifyConnectSessionState(
        deviceId = "echo", deviceName = "Kitchen Echo", deviceType = "Speaker", window = window,
        windowPosition = position, isPlaying = playing, progressMs = progress, anchorMs = anchor,
        durationMs = 200_000, volumePercent = 40, supportsVolume = true, hasMoreAfterWindow = hasMore
    )

    private fun poll(
        uri: String?,
        device: String = "echo",
        playing: Boolean = true,
        progress: Long = 0,
        duration: Long = 200_000,
        volume: Int = 40
    ) = SpotifyPlaybackSnapshot(
        device = SpotifyConnectDevice(device, if (device == "echo") "Kitchen Echo" else "Desk", "Speaker",
            isActive = true, volumePercent = volume, supportsVolume = true),
        isPlaying = playing, progressMs = progress, itemUri = uri, itemDurationMs = duration
    )

    private val none = SpotifyConnectPollOutcome.Change.None
    private val updated = SpotifyConnectPollOutcome.Change.Updated

    @Test fun `interpolates and writes nothing when nothing changed`() {
        var s = state()
        assertEquals(11_500, s.positionAt(101_500))
        assertEquals(200_000, s.positionAt(999_999))
        // 1.2 s later the device reports 11.1 s: within the tolerance of the interpolated 11.2 s.
        val quiet = SpotifyConnectReducer.apply(poll("spotify:track:a", progress = 11_100), s, 101_200)
        assertEquals(SpotifyConnectPollOutcome(none), quiet.outcome)
        assertSame(s, quiet.state)
        // A scrub on the device itself is a change.
        var r = SpotifyConnectReducer.apply(poll("spotify:track:a", progress = 90_000), s, 101_300)
        assertEquals(updated, r.outcome.change)
        s = r.state
        assertEquals(90_000, s.progressMs)
        assertEquals(101_300, s.anchorMs)
        r = SpotifyConnectReducer.apply(poll("spotify:track:a", playing = false, progress = 90_000), s, 101_400)
        assertEquals(updated, r.outcome.change)
        s = r.state
        assertFalse(s.isPlaying)
        assertEquals(90_000, s.positionAt(200_000))
        r = SpotifyConnectReducer.apply(poll("spotify:track:a", playing = false, progress = 90_000, volume = 55), s, 101_500)
        assertEquals(updated, r.outcome.change)
        assertEquals(55, r.state.volumePercent)
    }

    @Test fun `follows track changes to queue indices`() {
        val r = SpotifyConnectReducer.apply(poll("spotify:track:b", progress = 400, duration = 150_000), state(), 200_000)
        assertEquals(SpotifyConnectPollOutcome.Change.TrackChanged(5), r.outcome.change)
        assertEquals(1, r.state.windowPosition)
        assertEquals(150_000, r.state.durationMs)
        assertEquals(400, r.state.progressMs)
    }

    @Test fun `takeovers end the session`() {
        val s = state()
        assertEquals(
            SpotifyConnectPollOutcome.Change.TakenOver(SpotifyConnectTakeover.OtherDevice("Desk")),
            SpotifyConnectReducer.apply(poll("spotify:track:a", device = "desk"), s, 200_000).outcome.change
        )
        assertEquals(
            SpotifyConnectPollOutcome.Change.TakenOver(SpotifyConnectTakeover.OtherContent),
            SpotifyConnectReducer.apply(poll("spotify:track:zzz"), s, 200_000).outcome.change
        )
        assertEquals(
            SpotifyConnectPollOutcome.Change.TakenOver(SpotifyConnectTakeover.Stopped),
            SpotifyConnectReducer.apply(null, s, 200_000).outcome.change
        )
        assertEquals("Playback moved to Desk", SpotifyConnectTakeover.OtherDevice("Desk").message("Kitchen Echo"))
        assertEquals("Playback stopped on Kitchen Echo", SpotifyConnectTakeover.Stopped.message("Kitchen Echo"))
        assertEquals(
            "Spotify started playing something else on Kitchen Echo",
            SpotifyConnectTakeover.OtherContent.message("Kitchen Echo")
        )
    }

    @Test fun `grace ignores stale polls`() {
        var s = SpotifyConnectReducer.sent(
            SpotifyConnectWindow(listOf("spotify:track:b", "spotify:track:c"), listOf(5, 6)),
            positionMs = 0, durationMs = 150_000, hasMore = false, state = state(), nowMs = 300_000
        )
        // The device still shows the old track, another device, or nothing: all ignored during the grace period.
        assertEquals(none, SpotifyConnectReducer.apply(poll("spotify:track:a"), s, 300_500).outcome.change)
        assertEquals(none, SpotifyConnectReducer.apply(poll("spotify:track:c"), s, 300_600).outcome.change)
        assertEquals(none, SpotifyConnectReducer.apply(poll("spotify:track:a", device = "desk"), s, 300_650).outcome.change)
        val r = SpotifyConnectReducer.apply(null, s, 300_700)
        assertEquals(none, r.outcome.change)
        s = r.state
        assertEquals(0, s.windowPosition)
        assertEquals(5, s.queueIndex)
        // After it, the same stale poll is a takeover.
        assertEquals(
            SpotifyConnectPollOutcome.Change.TakenOver(SpotifyConnectTakeover.OtherContent),
            SpotifyConnectReducer.apply(poll("spotify:track:a"), s, 300_000 + SpotifyConnectReducer.COMMAND_GRACE_MS).outcome.change
        )
    }

    @Test fun `end of queue and next window`() {
        // Autoplay after the last entry is the end, not a takeover.
        assertEquals(
            SpotifyConnectPollOutcome.Change.ReachedEnd,
            SpotifyConnectReducer.apply(poll("spotify:track:other"), state(position = 2), 200_000).outcome.change
        )
        // The device paused itself at the last entry's start.
        assertEquals(
            SpotifyConnectPollOutcome.Change.ReachedEnd,
            SpotifyConnectReducer.apply(poll("spotify:track:c", playing = false, progress = 0), state(position = 2), 200_000).outcome.change
        )
        // More queue after the window: ask for the next window once, when the last entry starts.
        val first = SpotifyConnectReducer.apply(poll("spotify:track:c", progress = 300), state(position = 1, hasMore = true), 200_000)
        assertEquals(SpotifyConnectPollOutcome.Change.TrackChanged(6), first.outcome.change)
        assertTrue(first.outcome.needsNextWindow)
        val again = SpotifyConnectReducer.apply(poll("spotify:track:c", progress = 1_300), first.state, 201_000)
        assertFalse(again.outcome.needsNextWindow)
    }

    @Test fun `transport decisions`() {
        val slots = listOf(
            SpotifyConnectSlot.Uri("x"), SpotifyConnectSlot.Skipped, SpotifyConnectSlot.Skipped,
            SpotifyConnectSlot.Uri("spotify:track:a"), SpotifyConnectSlot.Skipped,
            SpotifyConnectSlot.Uri("spotify:track:b"), SpotifyConnectSlot.Uri("spotify:track:c"),
            SpotifyConnectSlot.Skipped, SpotifyConnectSlot.Uri("spotify:track:d")
        )
        assertEquals(SpotifyConnectReducer.Skip.Next, SpotifyConnectReducer.next(state(position = 0), slots, repeatAll = false))
        assertEquals(SpotifyConnectReducer.Skip.Play(8), SpotifyConnectReducer.next(state(position = 2), slots, repeatAll = false))
        val end = slots.take(7)
        assertEquals(SpotifyConnectReducer.Skip.None, SpotifyConnectReducer.next(state(position = 2), end, repeatAll = false))
        assertEquals(SpotifyConnectReducer.Skip.Play(0), SpotifyConnectReducer.next(state(position = 2), end, repeatAll = true))

        assertEquals(SpotifyConnectReducer.Skip.Restart, SpotifyConnectReducer.previous(state(position = 1, progress = 5_000), slots, 100_000))
        assertEquals(SpotifyConnectReducer.Skip.Play(3), SpotifyConnectReducer.previous(state(position = 1, progress = 1_000), slots, 100_000))
        assertEquals(SpotifyConnectReducer.Skip.Play(0), SpotifyConnectReducer.previous(state(position = 0, progress = 1_000), slots, 100_000))
        assertEquals("track", SpotifyConnectReducer.remoteRepeat(repeatOne = true))
        assertEquals("off", SpotifyConnectReducer.remoteRepeat(repeatOne = false))
    }

    @Test fun `optimistic commands`() {
        var s = SpotifyConnectReducer.setPlaying(false, state(), 102_000)
        assertFalse(s.isPlaying)
        assertEquals(12_000, s.progressMs)
        assertEquals(102_000 + SpotifyConnectReducer.COMMAND_GRACE_MS, s.graceUntilMs)
        s = SpotifyConnectReducer.seek(50_000, s, 103_000)
        assertEquals(50_000, s.positionAt(110_000))
        s = SpotifyConnectReducer.advance(s, 150_000, 104_000)
        assertEquals(1, s.windowPosition)
        assertEquals(5, s.queueIndex)
        assertTrue(s.isPlaying)
        assertEquals(0, s.progressMs)
    }

    @Test fun `a relinked track is matched by the URI that was sent`() {
        val dto = SpotifyPlaybackStateDto(
            device = SpotifyDeviceDto(id = "echo", isActive = true, name = "Kitchen Echo", type = "Speaker",
                volumePercent = 40, supportsVolume = true),
            isPlaying = true,
            progressMs = 400,
            item = SpotifyPlayerItemDto(
                id = "relinked", uri = "spotify:track:relinked", durationMs = 150_000,
                linkedFrom = SpotifyLinkedFromDto(id = "b", uri = "spotify:track:b")
            ),
            currentlyPlayingType = "track"
        )
        val snapshot = SpotifyPlaybackSnapshot.from(dto)
        assertEquals("spotify:track:b", snapshot.itemUri)
        assertEquals(
            SpotifyConnectPollOutcome.Change.TrackChanged(5),
            SpotifyConnectReducer.apply(snapshot, state(), 200_000).outcome.change
        )
    }

    @Test fun `polls keep a volume set here for the hold, then follow the device`() {
        var s = SpotifyConnectReducer.setVolume(130, state(), nowMs = 100_000)
        assertEquals(100, s.volumePercent)
        assertEquals(100_000 + SpotifyConnectVolume.HOLD_MS, s.volumeHoldUntilMs)
        // A poll from before the PUT landed, inside the hold: nothing changes.
        val held = SpotifyConnectReducer.apply(poll("spotify:track:a", progress = 10_100, volume = 40), s, 100_100)
        assertEquals(none, held.outcome.change)
        assertEquals(100, held.state.volumePercent)
        // The device accepted it: the hold is extended, never shortened.
        s = SpotifyConnectReducer.volumeSent(s, nowMs = 102_000)
        assertEquals(102_000 + SpotifyConnectVolume.HOLD_AFTER_SEND_MS, s.volumeHoldUntilMs)
        s = SpotifyConnectReducer.volumeSent(s, nowMs = 100_200)
        assertEquals(102_000 + SpotifyConnectVolume.HOLD_AFTER_SEND_MS, s.volumeHoldUntilMs)
        // After the hold the device's own value applies.
        val after = SpotifyConnectReducer.apply(poll("spotify:track:a", progress = 13_600, volume = 40), s, 103_600)
        assertEquals(updated, after.outcome.change)
        assertEquals(40, after.state.volumePercent)
        // States built without a hold follow polls at once.
        assertEquals(0, state().volumeHoldUntilMs)
    }

    @Test fun `a refused volume stays off whatever polls and device lists say`() {
        // Without a refusal, a poll reporting supports_volume turns volume control on.
        val off = state().copy(supportsVolume = false)
        val on = SpotifyConnectReducer.apply(poll("spotify:track:a", progress = 10_000), off, 100_000)
        assertEquals(updated, on.outcome.change)
        assertTrue(on.state.supportsVolume)
        // The device refused a volume command (VOLUME_CONTROL_DISALLOW): off for the rest of the
        // session, so polls don't offer it again and every press doesn't fail (and toast) again.
        val refused = SpotifyConnectReducer.refuseVolume(state())
        assertFalse(refused.supportsVolume)
        assertTrue(refused.volumeRefused)
        val polled = SpotifyConnectReducer.apply(poll("spotify:track:a", progress = 10_000), refused, 100_000)
        assertEquals(none, polled.outcome.change)
        assertFalse(polled.state.supportsVolume)
        // The device list says the same: refused stays refused; nothing refused yet follows the device.
        assertFalse(SpotifyConnectReducer.supportsVolume(true, refused))
        assertTrue(SpotifyConnectReducer.supportsVolume(true, state()))
        assertTrue(SpotifyConnectReducer.supportsVolume(true, null))
        assertFalse(SpotifyConnectReducer.supportsVolume(false, state()))
        // A new session starts without the refusal.
        assertFalse(state().volumeRefused)
    }
}

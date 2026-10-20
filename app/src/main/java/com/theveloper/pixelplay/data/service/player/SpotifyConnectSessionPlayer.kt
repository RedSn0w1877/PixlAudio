package com.theveloper.pixelplay.data.service.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectController
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectSessionState
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The MediaSession's player while a Spotify Connect session runs. It wraps the local engine player
 * (the queue's single source of truth) and is swapped in by MusicService only for the length of
 * the session, so the notification, lock screen, the app's MediaController UI, volume keys, Wear
 * and widgets all drive the Connect device without knowing it.
 *
 * - Transport (play/pause/seek/next/previous/pick an entry, repeat) goes to [SpotifyConnectController];
 *   the local player is never told to play.
 * - Queue edits are applied to the local player as usual, then reported so the controller can re-send.
 * - The reported state is the local queue and current entry, with the device's play state, an
 *   interpolated position and a remote volume on top: [DeviceInfo.PLAYBACK_TYPE_REMOTE] on a 0…20
 *   scale, one unit per volume key press (5 %, [SpotifyConnectVolume]). A Smartphone/Tablet target
 *   keeps the local player's DeviceInfo, so the keys keep changing this phone's media volume.
 * - Invariant: whether the keys can change the volume must always change the DeviceInfo too (max
 *   20 vs 0, or remote vs local). Media3 (checked in 1.10.1 and 1.11.1) rebuilds the platform
 *   VolumeProvider and its FIXED/ABSOLUTE control type only on a DeviceInfo change or a player
 *   swap, never on an available-commands change.
 * - The app also plays songs straight on the engine (bypassing the session); a listener on the
 *   local player catches those attempts and turns them into a play on the device.
 */
@UnstableApi
class SpotifyConnectSessionPlayer(
    private val localPlayer: Player,
    private val controller: SpotifyConnectController
) : ForwardingSimpleBasePlayer(localPlayer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var remote: SpotifyConnectSessionState? = controller.session.value
    private var pendingDiscontinuityMs: Long? = null
    private var released = false

    private val localListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && !controller.isMovingLocalPlayer && controller.isRedirectingLocal) {
                controller.onLocalPlayAttempt()
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !controller.isMovingLocalPlayer) {
                controller.remoteQueueChanged()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!controller.isMovingLocalPlayer && reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller.remoteQueueChanged()
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            controller.remoteRepeatModeChanged(repeatMode)
        }
    }

    init {
        localPlayer.addListener(localListener)
        scope.launch {
            combine(controller.session, controller.uiState) { session, ui -> session to ui.active }
                .distinctUntilChanged()
                .collect { (session, _) ->
                    val previous = remote
                    remote = session
                    if (previous != null && session != null && previous.queueIndex == session.queueIndex) {
                        val now = SystemClock.elapsedRealtime()
                        val jump = abs(previous.positionAt(now) - session.positionAt(now))
                        if (jump > POSITION_JUMP_MS) pendingDiscontinuityMs = session.positionAt(now)
                    }
                    if (!released) invalidateState()
                }
        }
    }

    override fun getState(): State {
        val base = super.getState()
        val session = remote ?: return base
        if (base.timeline.isEmpty) return base
        val builder = base.buildUpon()
            .setPlayWhenReady(session.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(Player.STATE_READY)
            .setIsLoading(false)
            .setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setPlayerError(null)
            .setContentPositionMs { session.positionAt(SystemClock.elapsedRealtime()) }
        val remoteVolume = SpotifyConnectVolume.remoteFor(session.deviceType, session.supportsVolume, session.volumePercent)
        if (remoteVolume != null) {
            builder
                .setDeviceInfo(
                    DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                        .setMinVolume(0)
                        .setMaxVolume(remoteVolume.maxVolume)
                        .build()
                )
                .setDeviceVolume(remoteVolume.volume)
                .setIsDeviceMuted(false)
        }
        val commands = base.availableCommands.buildUpon()
        if (remoteVolume?.adjustable == true) {
            commands.addAll(
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS
            )
        } else {
            commands.removeAll(
                Player.COMMAND_SET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS
            )
        }
        builder.setAvailableCommands(commands.build())
        pendingDiscontinuityMs?.let {
            pendingDiscontinuityMs = null
            if (!base.hasPositionDiscontinuity) builder.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, it)
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.remotePlay() else controller.remotePause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.remotePause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val current = localPlayer.currentMediaItemIndex
        val position = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.remoteSkipToNext()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                if (mediaItemIndex == current) controller.remoteSeek(position) else controller.remoteSkipToPrevious()
            else ->
                if (mediaItemIndex == current || mediaItemIndex == C.INDEX_UNSET) {
                    controller.remoteSeek(position)
                } else {
                    controller.remoteSkip(controller.queueIndexForLocal(mediaItemIndex))
                }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val result = super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        controller.remoteQueueChanged()
        return result
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val result = super.handleAddMediaItems(index, mediaItems)
        controller.remoteQueueChanged()
        return result
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val result = super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
        controller.remoteQueueChanged()
        return result
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val result = super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
        controller.remoteQueueChanged()
        return result
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val result = super.handleRemoveMediaItems(fromIndex, toIndex)
        controller.remoteQueueChanged()
        return result
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        // The system panel's slider: 0…20 units of 5 %.
        controller.setVolume(SpotifyConnectVolume.stepsToPercent(deviceVolume))
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        controller.adjustVolume(SpotifyConnectController.VOLUME_STEP)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        controller.adjustVolume(-SpotifyConnectController.VOLUME_STEP)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        if (muted) controller.setVolume(0)
        return Futures.immediateVoidFuture()
    }

    /** Never releases the local player: it outlives the session. */
    override fun handleRelease(): ListenableFuture<*> {
        detach()
        return Futures.immediateVoidFuture()
    }

    private fun detach() {
        if (released) return
        released = true
        localPlayer.removeListener(localListener)
        scope.cancel()
    }

    companion object {
        /** A remote position change beyond this (same song) is reported as a seek. */
        private const val POSITION_JUMP_MS = 2_000L
    }
}

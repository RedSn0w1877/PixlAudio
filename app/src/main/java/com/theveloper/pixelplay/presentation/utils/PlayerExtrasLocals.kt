package com.theveloper.pixelplay.presentation.utils

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import com.theveloper.pixelplay.data.blocking.BlockedArtistIndex
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.PlayerGestureSettings
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The blocked-artists list as the UI sees it, provided once at the app root so any song row,
 * menu or sheet can dim a blocked song or offer "Block artist" without threading parameters
 * through 16 call sites.
 */
@Immutable
class BlockedArtistsUi(
    val index: BlockedArtistIndex,
    /** "Play anyway": lets this one song through the playback guard. */
    val allowOnce: (songId: String) -> Unit,
    val block: (artistId: Long, name: String) -> Unit,
    val unblock: (artistId: Long, name: String) -> Unit,
)

val LocalBlockedArtistsUi = compositionLocalOf<BlockedArtistsUi?> { null }

/** The per-gesture switches from Settings > Playback > Gestures. */
val LocalPlayerGestures = compositionLocalOf { PlayerGestureSettings() }

/**
 * Actions the gesture layers need from the player, provided at the app root.
 * [songOptionsRequests] is collected by the player sheet, which opens the song's options sheet
 * (share, add to playlist, go to artist, block artist) for the long-press "quick actions".
 */
@Immutable
class PlayerGestureHost(
    val songOptionsRequests: MutableSharedFlow<Song>,
    val toggleFavoriteCurrent: () -> Unit,
    val skipNext: () -> Unit,
    val skipPrevious: () -> Unit,
    /** Adds [song] to the queue (next, or at the end) unless it is already there; true when added. */
    val queueSongIfAbsent: (song: Song, playNext: Boolean) -> Boolean,
    val showToast: (String) -> Unit,
    /**
     * Rows of the song options sheet that act on the full player (Spatial audio, Sing). The sheet
     * lives in the overlay layer, the Spatial sheet and the lyrics view in the full player, so the
     * sheet asks and the full player (which collects this) opens them.
     */
    val playerMenuRequests: MutableSharedFlow<PlayerMenuRequest> = MutableSharedFlow(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ),
) {
    fun requestSongOptions(song: Song) { songOptionsRequests.tryEmit(song) }

    fun requestPlayerMenuAction(request: PlayerMenuRequest) { playerMenuRequests.tryEmit(request) }
}

/** What a player row of the song options sheet asks the full player to open. */
enum class PlayerMenuRequest {
    /** The Spatial audio sheet, exactly as the old player button opened it. */
    SPATIAL_AUDIO,

    /** Sing (vocals off / back on), then the lyrics view when it was switched on. */
    SING,
}

val LocalPlayerGestureHost = compositionLocalOf<PlayerGestureHost?> { null }

package com.theveloper.pixelplay.data.spatial

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The cloud side of Spatial audio and Enhanced audio as the rest of the app sees it (§3, §6.2, §6.4): the local store
 * of verified renders, and the way to get one (the shared library first, then a cloud job).
 *
 * Every `videoId` here is the matched YouTube video id (§2, `CloudStreamSecurity.validateYouTubeVideoId`), never a
 * Spotify id: `SpotifyDao.getMatchedVideoId(spotifyId)` has it. A local render counts only for the current
 * [SpatialContract.LIB_V].
 *
 * The player calls [localRender], [localEnhanced], [request] and [markPlayed] and listens to [events]; the UI reads
 * [status] and [stats] and calls [removeAll], through
 * [com.theveloper.pixelplay.presentation.viewmodel.SpatialAudioStateHolder]. The background queue of §6.4 lives
 * behind this interface: nobody asks for it from outside.
 *
 * Bound in [SpatialLibraryModule]. Until the cloud side lands it is [NoOpSpatialLibrary]: there is never a render.
 */
interface SpatialLibrary {
    /**
     * §6.2 step 1, the local store hit: the verified render of [profile] for [videoId], or null. Called on the
     * player's load path for every song that starts (and for preloads), so it must not touch the network and must
     * answer from memory or with a file check; safe from any thread.
     */
    suspend fun localRender(videoId: String, profile: SpatialProfile): SpatialRender?

    /**
     * §6.3 "Enhanced": the verified Enhanced file of [videoId], or null (none was made, it is not on this phone, or
     * the song needed no restoring). Same rules as [localRender]: no network, quick, any thread.
     */
    suspend fun localEnhanced(videoId: String): EnhancedAudio?

    /**
     * §6.2 steps 2-4: the song [songId] (the library's `Song.id`), which plays YouTube video [videoId], is playing
     * and has no local render of [profile]. The library fetches the shared render when one exists, else sends a
     * cloud job, and adds the Enhanced version when `enhanced.enabled` and none is known yet. A null [profile] asks
     * for the Enhanced version only (Spatial audio is off or not audible).
     *
     * Returns at once and never throws. The library decides whether anything happens: nothing while `spatial.cloud`
     * is off, and inside the caps and network rules otherwise (§6.1, §6.2). Calling it again for the same video and
     * profile while one is on its way, ready, or waiting out a failure's backoff changes nothing. Progress shows in
     * [status]; the result arrives as [SpatialLibraryEvent.RenderReady] or [SpatialLibraryEvent.EnhancedReady].
     */
    fun request(songId: String, videoId: String, profile: SpatialProfile?)

    /**
     * §6.1's state of one song for one profile, as it changes: the current value at once, then every change.
     * [SpatialRenditionStatus.NONE] for anything never asked for.
     */
    fun status(videoId: String, profile: SpatialProfile): Flow<SpatialRenditionStatus>

    /**
     * What changes in the local store, for the player (§6.3): a render or an Enhanced file arrived, or files were
     * removed. Hot and without replay.
     */
    val events: SharedFlow<SpatialLibraryEvent>

    /** §6.5: "Songs ready: N · X GB on this phone", kept current as files arrive and leave. */
    val stats: StateFlow<SpatialLibraryStats>

    /**
     * The player started playing a file from the local store: the render of [profile], or the Enhanced file when
     * [profile] is null. The storage budget removes the least-recently-played first (§6.1 `cloudAudio.storageMB`),
     * and never the one played last.
     */
    fun markPlayed(videoId: String, profile: SpatialProfile?)

    /**
     * §6.5 "Remove downloaded renders": deletes every file of the local store on this phone, never anything in the
     * shared library (§6.2 step 4). Emits [SpatialLibraryEvent.Removed] with a null video.
     */
    suspend fun removeAll()
}

/** The library before the cloud side exists: no render is ever there, nothing is ever sent. */
object NoOpSpatialLibrary : SpatialLibrary {
    private val noEvents = MutableSharedFlow<SpatialLibraryEvent>()
    private val noStats = MutableStateFlow(SpatialLibraryStats())

    override suspend fun localRender(videoId: String, profile: SpatialProfile): SpatialRender? = null

    override suspend fun localEnhanced(videoId: String): EnhancedAudio? = null

    override fun request(songId: String, videoId: String, profile: SpatialProfile?) = Unit

    override fun status(videoId: String, profile: SpatialProfile): Flow<SpatialRenditionStatus> =
        flowOf(SpatialRenditionStatus.NONE)

    override val events: SharedFlow<SpatialLibraryEvent> = noEvents.asSharedFlow()

    override val stats: StateFlow<SpatialLibraryStats> = noStats.asStateFlow()

    override fun markPlayed(videoId: String, profile: SpatialProfile?) = Unit

    override suspend fun removeAll() = Unit
}

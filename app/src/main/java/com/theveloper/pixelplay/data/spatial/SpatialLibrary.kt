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
 * The cloud side of Spatial audio as the rest of the app sees it (§3, §6.2, §6.4): the local store of verified
 * renders, and the way to get one (the shared library first, then a cloud job).
 *
 * Every `videoId` here is the matched YouTube video id (§2, `CloudStreamSecurity.validateYouTubeVideoId`), never a
 * Spotify id: `SpotifyDao.getMatchedVideoId(spotifyId)` has it. A local render counts only for the current
 * [SpatialContract.LIB_V].
 *
 * The player calls [localRender], [request] and [markPlayed] and listens to [events]; the UI reads [status], [stats]
 * and [cloudRendersOn] and calls [removeAll], through
 * [com.theveloper.pixelplay.presentation.viewmodel.SpatialAudioStateHolder]. The background queue of §6.4 lives
 * behind this interface: nobody asks for it from outside.
 *
 * The library reads the settings itself ([SpatialSettingsRepository.settings]) and obeys both switches of §6.1:
 * Cloud Studio's "Process songs in the cloud" is the master, `spatial.cloud` is Spatial's own under it. With either
 * off it looks nothing up, downloads nothing and sends nothing; what is already on the phone still plays. Its jobs
 * have their own monthly sum under `cloudAudio.monthlyCapUSD`, apart from Sing's (§6.1 "Money").
 *
 * Bound in [SpatialLibraryModule]. Until the cloud side lands it is [NoOpSpatialLibrary]: there is never a render.
 */
interface SpatialLibrary {
    /**
     * §6.1 "Two switches": whether cloud renders can happen at all right now, i.e. Cloud Studio's master switch and
     * `spatial.cloud` are both on. The sheet's status line needs it (§6.5: "a profile is on, cloud renders are
     * off"). It says nothing about the network or the caps.
     */
    val cloudRendersOn: StateFlow<Boolean>

    /**
     * §6.2 step 1, the local store hit: the verified render of [profile] for [videoId], or null. Called on the
     * player's load path for every song that starts (and for preloads), so it must not touch the network and must
     * answer from memory or with a file check; safe from any thread.
     */
    suspend fun localRender(videoId: String, profile: SpatialProfile): SpatialRender?

    /**
     * §6.2 steps 2-4: the song [songId] (the library's `Song.id`), which plays YouTube video [videoId], is playing
     * with Spatial audio active (§6.3) and has no local render of [profile]. The library fetches the shared render
     * when one exists, else sends a cloud job with `tasks: ["spatial"]` and one render entry for the profile.
     *
     * Returns at once and never throws. The library decides whether anything happens: nothing while
     * [cloudRendersOn] is false, and inside the cap and the network rules otherwise (§6.1, §6.2). A render that is
     * wanted but cannot start yet stays queued with its reason ([SpatialRenditionStatus.WAITING_FOR_NETWORK],
     * [SpatialRenditionStatus.CAP_REACHED]) and starts when it can. Calling it again for the same video and profile
     * while one is on its way, ready, or waiting out a failure's backoff changes nothing. Progress shows in
     * [status]; the result arrives as [SpatialLibraryEvent.RenderReady].
     */
    fun request(songId: String, videoId: String, profile: SpatialProfile)

    /**
     * The cloud's side of §6.1's state for one song and profile, as it changes: the current value at once, then
     * every change. [SpatialRenditionStatus.NONE] for anything not wanted or not under way; never
     * [SpatialRenditionStatus.PREVIEW], which is the player's fact ([SpatialRenditionStatus.shown]).
     */
    fun status(videoId: String, profile: SpatialProfile): Flow<SpatialRenditionStatus>

    /**
     * What changes in the local store, for the player (§6.3): a render arrived, or files were removed. Hot and
     * without replay.
     */
    val events: SharedFlow<SpatialLibraryEvent>

    /**
     * §6.5: "Songs ready: N · X GB on this phone", kept current as files arrive and leave, with what library jobs
     * used of the month's cap and of the day's count for Developer mode's rows.
     */
    val stats: StateFlow<SpatialLibraryStats>

    /**
     * The player started playing the render of [profile] from the local store. The storage budget removes the
     * least-recently-played first (§6.1 `cloudAudio.storageMB`), and never the one played last.
     */
    fun markPlayed(videoId: String, profile: SpatialProfile)

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
    private val neverOn = MutableStateFlow(false)

    override val cloudRendersOn: StateFlow<Boolean> = neverOn.asStateFlow()

    override suspend fun localRender(videoId: String, profile: SpatialProfile): SpatialRender? = null

    override fun request(songId: String, videoId: String, profile: SpatialProfile) = Unit

    override fun status(videoId: String, profile: SpatialProfile): Flow<SpatialRenditionStatus> =
        flowOf(SpatialRenditionStatus.NONE)

    override val events: SharedFlow<SpatialLibraryEvent> = noEvents.asSharedFlow()

    override val stats: StateFlow<SpatialLibraryStats> = noStats.asStateFlow()

    override fun markPlayed(videoId: String, profile: SpatialProfile) = Unit

    override suspend fun removeAll() = Unit
}

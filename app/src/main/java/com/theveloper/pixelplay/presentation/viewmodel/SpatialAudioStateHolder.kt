package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.spatial.SpatialAudible
import com.theveloper.pixelplay.data.spatial.SpatialLibrary
import com.theveloper.pixelplay.data.spatial.SpatialLibraryStats
import com.theveloper.pixelplay.data.spatial.SpatialPlayback
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import com.theveloper.pixelplay.data.spatial.SpatialRendition
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus
import com.theveloper.pixelplay.data.spatial.SpatialRouteMode
import com.theveloper.pixelplay.data.spatial.SpatialSettings
import com.theveloper.pixelplay.data.spatial.SpatialSettingsRepository
import com.theveloper.pixelplay.data.spatial.SpatialStatusLine
import com.theveloper.pixelplay.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * What the UI of Spatial audio reads and calls (cloud-audio-spec §6.5): the full player's sheet (the profile list,
 * Off, the status line) and Settings' Sound group (profile, cloud, prepare ahead, "Songs ready", "Remove downloaded
 * renders", and Developer mode's rows). Process-scoped like the other state holders, so the sheet and Settings
 * share one state and nothing is lost when a screen goes away.
 *
 * It holds no rule of its own: the settings live in [SpatialSettingsRepository], what is audible comes from
 * [SpatialPlayback], renders and their status from [SpatialLibrary]. Every setter only writes the setting; the
 * playback service and the cloud side react to the stored value, so a change works the same with or without a UI.
 * Nothing here runs on the main thread, and every flow only emits when its value really changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SpatialAudioStateHolder @Inject constructor(
    private val repository: SpatialSettingsRepository,
    private val library: SpatialLibrary,
    private val playback: SpatialPlayback,
    @AppScope appScope: CoroutineScope,
) {
    // A child of the app's scope with its own handler: a failed write (a full disk) is logged, never a crash.
    private val scope = CoroutineScope(
        appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, error -> Timber.w("Spatial audio action failed: %s", error.javaClass.simpleName) }
    )

    /**
     * The settings of §6.1: the selected profile (null is Off), the cloud switch, prepare ahead, and Developer
     * mode's route mode, storage budget and caps. Starts on the defaults until the store has been read.
     */
    val settings: StateFlow<SpatialSettings> =
        repository.settings.stateIn(scope, SharingStarted.Eagerly, repository.defaults)

    /**
     * UI-only: [settings] once the store has been read, null until then. The holder is created when the first
     * screen asks for it, so for a frame or two [settings] still holds the defaults; the full player's button waits
     * on this instead, so a stored profile never "grows in" from Off when the player opens.
     */
    val storedSettings: StateFlow<SpatialSettings?> =
        repository.settings.stateIn(scope, SharingStarted.Eagerly, null)

    /** What the current item sounds like, and what holds a selected profile back (§6.3). */
    val audible: StateFlow<SpatialAudible> get() = playback.audible

    /**
     * §6.5: "Songs ready: N · X GB on this phone", and what library jobs used of the month's cap and of the day's
     * count for Developer mode's rows.
     */
    val stats: StateFlow<SpatialLibraryStats> get() = library.stats

    /**
     * §6.1 "Two switches": cloud renders can happen, i.e. Cloud Studio's master switch and `spatial.cloud` are both
     * on. Settings' cloud toggle of Spatial audio is only worth something while the master is on.
     */
    val cloudRendersOn: StateFlow<Boolean> get() = library.cloudRendersOn

    /**
     * The sheet's "This song" switch: the song playing and whether Spatial audio is turned off for it. Null while
     * nothing plays, or the item is not a Spotify track (a switch would have nothing to remember it by).
     */
    val currentSong: StateFlow<SpatialSongChoice?> by lazy {
        combine(settings, songWhileOn) { current, spotifyId ->
            spotifyId?.let { SpatialSongChoice(it, off = current.isOffFor(it)) }
        }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    /**
     * The playing song's Spotify id, followed only while a profile is selected: with Spatial audio off nothing of the
     * player is followed (see below), and there is no "This song" switch to show.
     */
    private val songWhileOn: Flow<String?>
        get() = settings.map { it.profile != null }.distinctUntilChanged().flatMapLatest { on ->
            if (on) playback.audible.map { it.spotifyId }.distinctUntilChanged() else flowOf(null)
        }

    /** The profile the playing song plays with: null for Off, and while Spatial audio is turned off for this song. */
    private val selectedProfile: Flow<SpatialProfile?>
        get() = combine(settings, songWhileOn) { current, spotifyId -> current.profileFor(spotifyId) }.distinctUntilChanged()

    // currentStatus and statusLine follow the playing song whether or not anything shows them, for as long as a
    // profile is selected. The sheet starts from the value at hand on its first frame (and Developer mode's rows
    // too), so that value has to be the playing song's: a flow that stopped while the sheet was closed would keep
    // the line of whatever played then. While Spatial audio is off they read nothing but the settings.

    /**
     * The per-song status of §6.1 for the song that is playing, under the selected profile: the library's status,
     * or [SpatialRenditionStatus.PREVIEW] while the on-device stage stands in and the cloud has nothing under way
     * ([SpatialRenditionStatus.shown]). [SpatialRenditionStatus.NONE] while Spatial audio is off.
     */
    val currentStatus: StateFlow<SpatialRenditionStatus> =
        selectedProfile
            .flatMapLatest { profile ->
                if (profile == null) {
                    flowOf(SpatialRenditionStatus.NONE)
                } else {
                    playback.audible
                        .map { heard -> heard.videoId to (heard.rendition == SpatialRendition.ON_DEVICE) }
                        .distinctUntilChanged()
                        .flatMapLatest { (videoId, onDeviceAudible) ->
                            statusOf(videoId, profile).map { SpatialRenditionStatus.shown(it, onDeviceAudible) }
                        }
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, SpatialRenditionStatus.NONE)

    /** The sheet's status line for the current song (§6.5), see [SpatialStatusLine.of]. */
    val statusLine: StateFlow<SpatialStatusLine> =
        selectedProfile
            .map { it != null }
            .distinctUntilChanged()
            .flatMapLatest { on ->
                if (!on) {
                    // What SpatialStatusLine.of says for no profile, without following the player for it.
                    flowOf(SpatialStatusLine.NONE)
                } else {
                    combine(
                        settings,
                        playback.audible,
                        currentStatus,
                        library.cloudRendersOn,
                    ) { current, heard, status, cloudOn ->
                        SpatialStatusLine.of(current.copy(profile = current.profileFor(heard.spotifyId)), heard, status, cloudOn)
                    }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, SpatialStatusLine.NONE)

    /**
     * The current song's local render for the selected profile, once it is ready: Developer mode's per-song
     * diagnostics (§6.5: source codec, stem mode, loudness). Null otherwise.
     */
    val currentRender: StateFlow<SpatialRender?> =
        combine(settings, playback.audible, currentStatus) { current, heard, status ->
            Triple(current.profileFor(heard.spotifyId), heard.videoId, status)
        }
            .distinctUntilChanged()
            .mapLatest { (profile, videoId, status) ->
                if (profile != null && videoId != null && status == SpatialRenditionStatus.READY) {
                    library.localRender(videoId, profile)
                } else {
                    null
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The library's status of §6.1 for any song and profile (a list row, a song's card): never
     * [SpatialRenditionStatus.PREVIEW], which only the playing song has ([currentStatus]).
     * [SpatialRenditionStatus.NONE] when the song has no YouTube video ([videoId] null) or no profile is given.
     */
    fun statusOf(videoId: String?, profile: SpatialProfile?): Flow<SpatialRenditionStatus> =
        if (videoId == null || profile == null) flowOf(SpatialRenditionStatus.NONE) else library.status(videoId, profile)

    /** The sheet's profile list and Off (§6.5): [profile], or null for Off. Picking a profile turns Spatial audio on (§6.1). */
    fun selectProfile(profile: SpatialProfile?) {
        scope.launch { repository.setProfile(profile) }
    }

    /**
     * The sheet's "This song" switch: Spatial audio on ([on]) or off for the song playing. Off plays its original at
     * full quality, from where it is, and is remembered for every later play of the song (`spatial.songsOff`).
     */
    fun setCurrentSongSpatial(on: Boolean) {
        val spotifyId = currentSong.value?.spotifyId ?: return
        scope.launch { repository.setSongOff(spotifyId, off = !on) }
    }

    /**
     * Settings' cloud switch of Spatial audio (§6.1 `spatial.cloud`): ask the cloud for the real render, or stay on
     * the phone. Cloud Studio's own master switch is not touched here ([cloudRendersOn] is the two together).
     */
    fun setCloud(enabled: Boolean) {
        scope.launch { repository.setCloud(enabled) }
    }

    /** Settings' "prepare ahead" switch (§6.1 `spatial.prepareAhead`, §6.4). */
    fun setPrepareAhead(enabled: Boolean) {
        scope.launch { repository.setPrepareAhead(enabled) }
    }

    /** Developer mode's route mode (§6.1 `spatial.route`, §6.5). */
    fun setRouteMode(mode: SpatialRouteMode) {
        scope.launch { repository.setRouteMode(mode) }
    }

    /** Developer mode's storage budget in megabytes (§6.1 `cloudAudio.storageMB`, §6.5). */
    fun setStorageMb(megabytes: Int) {
        scope.launch { repository.setStorageMb(megabytes) }
    }

    /** Developer mode's monthly cap in whole dollars, null for "No limit" (§6.1 `cloudAudio.monthlyCapUSD`, §6.5). */
    fun setMonthlyCapUsd(usd: Int?) {
        scope.launch { repository.setMonthlyCapUsd(usd) }
    }

    /** Developer mode's daily limit of the background queue (§6.1 `cloudAudio.dailyAhead`, §6.5). */
    fun setDailyAhead(songs: Int) {
        scope.launch { repository.setDailyAhead(songs) }
    }

    /** Settings' "Remove downloaded renders" (§6.5): this phone's copies only; the shared library keeps its own. */
    fun removeDownloadedRenders() {
        scope.launch { library.removeAll() }
    }

    private companion object {
        /** [currentRender] stops this long after nobody reads it, so a rotation doesn't restart it. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The song playing, for the sheet's "This song" switch: its Spotify id and whether Spatial audio is turned off for it. */
data class SpatialSongChoice(val spotifyId: String, val off: Boolean)

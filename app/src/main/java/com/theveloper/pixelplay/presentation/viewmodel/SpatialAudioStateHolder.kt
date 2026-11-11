package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.spatial.SpatialAudible
import com.theveloper.pixelplay.data.spatial.SpatialLibrary
import com.theveloper.pixelplay.data.spatial.SpatialLibraryStats
import com.theveloper.pixelplay.data.spatial.SpatialPlayback
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * What the UI of Spatial audio and Enhanced audio reads and calls (cloud-audio-spec §6.5): the full player's sheet
 * (the profile list, Off, the status line) and Settings' Sound group (profile, cloud, prepare ahead, Enhanced,
 * "Songs ready", "Remove downloaded renders", and Developer mode's rows). Process-scoped like the other state
 * holders, so the sheet and Settings share one state and nothing is lost when a screen goes away.
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
     * The settings of §6.1: the selected profile (null is Off), the cloud switch, prepare ahead, Enhanced, and
     * Developer mode's route mode, storage budget and caps. Starts on the defaults until the store has been read.
     */
    val settings: StateFlow<SpatialSettings> =
        repository.settings.stateIn(scope, SharingStarted.Eagerly, repository.defaults)

    /** What the current item sounds like, and what holds a selected profile back (§6.3). */
    val audible: StateFlow<SpatialAudible> get() = playback.audible

    /** §6.5: "Songs ready: N · X GB on this phone". */
    val stats: StateFlow<SpatialLibraryStats> get() = library.stats

    /**
     * The per-song status of §6.1 for the song that is playing, under the selected profile.
     * [SpatialRenditionStatus.NONE] while Spatial audio is off or the song has no YouTube video.
     */
    val currentStatus: StateFlow<SpatialRenditionStatus> =
        combine(settings, playback.audible) { current, heard -> current.profile to heard.videoId }
            .distinctUntilChanged()
            .flatMapLatest { (profile, videoId) -> statusOf(videoId, profile) }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SpatialRenditionStatus.NONE)

    /** The sheet's status line for the current song (§6.5), see [SpatialStatusLine.of]. */
    val statusLine: StateFlow<SpatialStatusLine> =
        combine(settings, playback.audible, currentStatus) { current, heard, status ->
            SpatialStatusLine.of(current, heard, status)
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SpatialStatusLine.NONE)

    /**
     * The current song's local render for the selected profile, once it is ready: Developer mode's per-song
     * diagnostics (§6.5: source codec, cutoff, stem mode, loudness). Null otherwise.
     */
    val currentRender: StateFlow<SpatialRender?> =
        combine(settings, playback.audible, currentStatus) { current, heard, status ->
            Triple(current.profile, heard.videoId, status)
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
     * The status of §6.1 for any song and profile (a list row, a song's card). [SpatialRenditionStatus.NONE] when the
     * song has no YouTube video ([videoId] null) or no profile is given.
     */
    fun statusOf(videoId: String?, profile: SpatialProfile?): Flow<SpatialRenditionStatus> =
        if (videoId == null || profile == null) flowOf(SpatialRenditionStatus.NONE) else library.status(videoId, profile)

    /** The sheet's profile list and Off (§6.5): [profile], or null for Off. Picking a profile turns Spatial audio on (§6.1). */
    fun selectProfile(profile: SpatialProfile?) {
        scope.launch { repository.setProfile(profile) }
    }

    /** Settings' cloud switch (§6.1 `spatial.cloud`): ask the cloud for the real render, or stay on the phone. */
    fun setCloud(enabled: Boolean) {
        scope.launch { repository.setCloud(enabled) }
    }

    /** Settings' "prepare ahead" switch (§6.1 `spatial.prepareAhead`, §6.4). */
    fun setPrepareAhead(enabled: Boolean) {
        scope.launch { repository.setPrepareAhead(enabled) }
    }

    /** Settings' Enhanced audio switch (§6.1 `enhanced.enabled`). */
    fun setEnhanced(enabled: Boolean) {
        scope.launch { repository.setEnhanced(enabled) }
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
        /** A flow nobody reads stops after this long, so a rotation or a sheet reopening doesn't restart it. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

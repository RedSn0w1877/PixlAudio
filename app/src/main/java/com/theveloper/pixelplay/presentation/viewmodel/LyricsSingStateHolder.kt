package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.worker.InstrumentalRenderJob
import com.theveloper.pixelplay.data.worker.InstrumentalRenderJobs
import com.theveloper.pixelplay.data.worker.RenderJobState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The lyrics toolbar's Sing button. */
@Immutable
data class SingUi(
    /** Vocals are off: the song plays through its studio instrumental. */
    val active: Boolean = false,
    /** False while Cast or a Spotify Connect speaker plays: the instrumental swap is local only. */
    val enabled: Boolean = true,
    /** The instrumental is being rendered (the person's job, or quiet automatic work while it runs). */
    val rendering: Boolean = false,
    /** 0..1 while the render runs; null while it is queued (or not rendering). */
    val progress: Float? = null,
)

/** Pure: what the Sing button shows for this state. */
fun singUi(
    available: Boolean,
    active: Boolean,
    job: InstrumentalRenderJob?,
    remoteActive: Boolean,
): SingUi {
    // Quiet automatic work only counts once it actually runs: Automatic Studio queues songs that
    // wait for idle playback and a charged battery, and "Removing vocals…" on every queued song
    // would claim work that isn't happening (a tap starts the person's own render instead).
    val rendering = job != null && !job.state.isFinished && !active && !available &&
        (!job.automatic || job.state == RenderJobState.RUNNING)
    return SingUi(
        active = active,
        enabled = !remoteActive,
        rendering = rendering,
        progress = if (rendering && job?.state == RenderJobState.RUNNING) job.percent.coerceIn(0, 100) / 100f else null,
    )
}

/** What the player should do for a Sing tap; [PlayerViewModel] sends it to MusicService. */
sealed interface SingRequest {
    data class Instrumental(val path: String) : SingRequest
    data object Original : SingRequest
}

/**
 * Sing on the lyrics page: vocals off / on through the song's studio instrumental (TAIS Engine 2's
 * MDX-Net render). It replaces the old floating instrumental pill and owns the instrumental state
 * that used to sit in [PlayerViewModel] (`studioInstrumentalActive` / `Available`).
 *
 * - With a render on disk, a tap swaps vocals off / on.
 * - Without one, a tap starts the render ([InstrumentalRenderJobs.enqueue], which also cancels quiet
 *   automatic work so the person's render runs now) and the button shows its progress. When it
 *   lands, the song switches to the instrumental by itself, once, and only if it is still the
 *   song playing on this phone.
 * - It is per song: every new song starts with vocals, and a pending tap never carries over.
 * - Cast and Spotify Connect play elsewhere, where the local audio swap can't reach: Sing is off.
 */
@Singleton
class LyricsSingStateHolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackStateHolder: PlaybackStateHolder,
    private val castStateHolder: CastStateHolder,
    private val spotifyConnect: SpotifyConnectStateHolder,
    private val renderJobs: InstrumentalRenderJobs,
) {
    private val _active = MutableStateFlow(false)
    /** True while the current song plays through its instrumental (vocals off). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _available = MutableStateFlow(false)
    /** True when a rendered instrumental exists on disk for the current song. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val currentJob = MutableStateFlow<InstrumentalRenderJob?>(null)
    private val remoteActive = MutableStateFlow(false)

    private val _ui = MutableStateFlow(SingUi())
    val ui: StateFlow<SingUi> = _ui.asStateFlow()

    private val _requests = MutableSharedFlow<SingRequest>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val requests: SharedFlow<SingRequest> = _requests.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var scope: CoroutineScope? = null
    private val jobs = ArrayList<Job>(3)

    /** The song whose Sing tap waits for its render. */
    private var pendingSongId: String? = null
    /** Render jobs already answered (switched to, or reported failed), so each answers once. */
    private val answeredJobIds = HashSet<String>()
    /** The current song's jobs as WorkManager last reported them. */
    private var lastJobs: List<InstrumentalRenderJob> = emptyList()

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        jobs.forEach { it.cancel() }
        jobs.clear()
        jobs += coroutineScope.launch {
            remoteFlow().collect { remoteActive.value = it }
        }
        jobs += coroutineScope.launch {
            combine(_available, _active, currentJob, remoteActive) { available, active, job, remote ->
                singUi(available, active, job, remote)
            }
                .distinctUntilChanged()
                .collect { _ui.value = it }
        }
        jobs += coroutineScope.launch {
            playbackStateHolder.stablePlayerState
                .map { it.currentSong?.id }
                .distinctUntilChanged()
                .collectLatest { songId -> followSong(songId) }
        }
    }

    private fun remoteFlow(): Flow<Boolean> = combine(
        castStateHolder.isRemotePlaybackActive,
        castStateHolder.isCastConnecting,
        spotifyConnect.playingOnName,
    ) { casting, connecting, connectDevice -> casting || connecting || connectDevice != null }

    private suspend fun followSong(songId: String?) {
        // Vocals come back with every new song, and a pending tap stays with its own song.
        _active.value = false
        _available.value = false
        currentJob.value = null
        answeredJobIds.clear()
        lastJobs = emptyList()
        if (pendingSongId != songId) pendingSongId = null
        if (songId == null) return
        _available.value = renderJobs.bestAvailablePath(songId) != null
        renderJobs.jobsFor(songId).collect { list -> onJobs(songId, list) }
    }

    internal fun onJobs(songId: String, list: List<InstrumentalRenderJob>) {
        lastJobs = list
        val job = pickJob(list)
        currentJob.value = job
        if (job == null) return
        val path = job.instrumentalPath
        when {
            job.state == RenderJobState.SUCCEEDED && path != null -> {
                // Already switched to, or finished before the latest tap (which found its file
                // gone from disk): nothing new.
                if (job.id in answeredJobIds) return
                _available.value = true
                if (pendingSongId == songId) {
                    answeredJobIds += job.id
                    pendingSongId = null
                    // A render landing while a Cast or Connect speaker plays doesn't touch this phone's player.
                    if (!remoteActive.value) _requests.tryEmit(SingRequest.Instrumental(path))
                }
            }
            // A person's render that failed or was stopped. Quiet automatic work that our own
            // enqueue cancelled is ignored, or the tap that cancelled it would answer itself.
            job.state.isFinished && !job.automatic -> {
                if (pendingSongId == songId && answeredJobIds.add(job.id)) {
                    pendingSongId = null
                    val reason = job.failureReason
                    if (job.state != RenderJobState.CANCELLED && reason != null) {
                        _messages.tryEmit(context.getString(R.string.lyrics_sing_failed, reason))
                    }
                }
            }
        }
    }

    /** The tap on Sing. */
    fun onSingTapped() {
        val scope = scope ?: return
        val song = playbackStateHolder.stablePlayerState.value.currentSong ?: return
        if (remoteActive.value) {
            _messages.tryEmit(context.getString(R.string.lyrics_sing_remote_only))
            return
        }
        if (_active.value) {
            _requests.tryEmit(SingRequest.Original)
            return
        }
        scope.launch {
            val path = renderJobs.bestAvailablePath(song.id)
            if (playbackStateHolder.stablePlayerState.value.currentSong?.id != song.id) return@launch
            if (path != null) {
                _available.value = true
                _requests.tryEmit(SingRequest.Instrumental(path))
                return@launch
            }
            // Nothing on disk, whatever an old finished job says (its file can be gone), so the
            // new render shows its progress.
            _available.value = false
            // Jobs that finished before this tap (yesterday's failure, a render whose file was
            // since deleted) belong to earlier taps: WorkManager can report them again before the
            // new job shows up, and they must not answer this one with a stale failure or a
            // switch to a missing file.
            lastJobs.forEach { if (it.state.isFinished) answeredJobIds += it.id }
            pendingSongId = song.id
            val running = currentJob.value
            // The person's render is already on its way (started here or from Song info): the
            // song switches when it lands. Quiet automatic work is adopted: enqueue replaces it
            // with the person's own job, which playback can't cancel.
            if (running != null && !running.state.isFinished && !running.automatic) return@launch
            renderJobs.enqueue(song)
            _messages.tryEmit(context.getString(R.string.lyrics_sing_queued))
        }
    }

    /** MusicService now plays the instrumental (set by [PlayerViewModel.switchToStudioInstrumental]). */
    fun onInstrumentalPlaying() {
        _active.value = true
        _available.value = true
    }

    /** MusicService is back on the original audio. */
    fun onOriginalPlaying() {
        _active.value = false
    }

    private fun pickJob(list: List<InstrumentalRenderJob>): InstrumentalRenderJob? =
        list.firstOrNull { !it.state.isFinished } ?: list.maxByOrNull { it.createdAtMs }
}

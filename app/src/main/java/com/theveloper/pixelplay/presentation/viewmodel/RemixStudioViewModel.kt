package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import com.theveloper.pixelplay.data.remix.RemixAudioEngine
import com.theveloper.pixelplay.data.remix.RemixStemLoader
import com.theveloper.pixelplay.data.remix.dsp.RemixStemBuffer
import com.theveloper.pixelplay.data.remix.dsp.Resampler
import com.theveloper.pixelplay.data.remix.model.RemixState
import com.theveloper.pixelplay.data.remix.model.StemKind
import com.theveloper.pixelplay.data.remix.pose.DeviceRotationPoseSource
import com.theveloper.pixelplay.data.remix.pose.HeadTrackerPoseSource
import com.theveloper.pixelplay.data.remix.pose.ListenerPoseSource
import com.theveloper.pixelplay.data.remix.pose.ManualPoseSource
import com.theveloper.pixelplay.data.remix.pose.PoseFilter
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.data.worker.RemixStemSeparationWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@Immutable
data class StemUi(
    val index: Int,
    val kind: String,
    /** Stage coordinates, −1..1, with the listener at the origin. */
    val x: Float,
    val y: Float,
    val gainDb: Float = 0f,
    val muted: Boolean = false,
)

@Immutable
data class RemixUiState(
    val songTitle: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val stems: ImmutableList<StemUi> = persistentListOf(),
    /** True when the stems are real separations rather than the mid/side fallback. */
    val separated: Boolean = false,
    val playing: Boolean = false,
    val rate: Float = 1f,
    val loopStartMs: Int = 0,
    val loopLengthMs: Int = 8_000,
    val songDurationMs: Int = 0,
    val cutoffHz: Float = 18_000f,
    val resonance: Float = 0.707f,
    val filterMode: String = "lp",
    val bits: Int = 16,
    val decim: Int = 1,
    val reverbMix: Float = 0.15f,
    val rt60: Float = 1.8f,
    val decayMs: Int = 0,
    val poseSourceId: String = ListenerPoseSource.ID_DEVICE,
    val headTrackerAvailable: Boolean = false,
    val waveform: ImmutableList<Float> = persistentListOf(),
    val separating: Boolean = false,
    val separationDetail: String? = null,
    val separationPercent: Int = 0,
    val canSeparate: Boolean = false,
    val backendUrl: String = "",
    /** Never expose the token itself to the UI — only whether one is stored. */
    val backendTokenSet: Boolean = false,
    val uploadConsent: Boolean = false,
)

/**
 * Drives Remix Studio: picks the best stems available for a song, loads the loop region, and
 * translates gestures into engine parameters.
 *
 * The engine is **injected**, not constructed here, so it survives rotation and a ViewModel
 * rebuild without tearing the audio down — the opposite of how the old DJ decks worked.
 */
@HiltViewModel
class RemixStudioViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val engine: RemixAudioEngine,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val audioCacheManager: AudioCacheManager,
    private val spotifyStreamProxy: SpotifyStreamProxy,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemixUiState())
    val uiState: StateFlow<RemixUiState> = _uiState.asStateFlow()

    val engineState: StateFlow<RemixAudioEngine.State> = engine.state

    /**
     * Live meters. Read on the UI frame clock rather than exposed as a Flow, because these are
     * written by the audio thread every block and a Flow emission per block would be both wasteful
     * and a needless cross-thread coupling.
     */
    val stemPeaks: FloatArray get() = engine.stemPeaks
    val playheadFraction: Float get() = engine.playheadFraction
    val listenerYaw: Float get() = engine.params.yaw

    private val poseFilter = PoseFilter()
    private val headTracker = HeadTrackerPoseSource(context)
    private val deviceRotation = DeviceRotationPoseSource(context)
    private val manualPose = ManualPoseSource()
    private var activePose: ListenerPoseSource? = null

    private var currentSong: Song? = null
    private val workManager = WorkManager.getInstance(context)
    private var separationWatch: Job? = null

    init {
        _uiState.value = _uiState.value.copy(headTrackerAvailable = headTracker.isAvailable())
        viewModelScope.launch {
            // Backend settings live here rather than in the settings screen, whose UI state is
            // built from a positional indexed combine that shifts every later index when a flow
            // is inserted. Fewer places to get that wrong.
            combine(
                userPreferencesRepository.remixBackendUrlFlow,
                userPreferencesRepository.remixBackendTokenFlow,
                userPreferencesRepository.remixUploadConsentFlow,
            ) { url, token, consent -> Triple(url, token, consent) }
                .collect { (url, token, consent) ->
                    _uiState.value = _uiState.value.copy(
                        backendUrl = url,
                        backendTokenSet = token.isNotBlank(),
                        uploadConsent = consent,
                    )
                }
        }
    }

    fun setBackendUrl(url: String) {
        viewModelScope.launch { userPreferencesRepository.setRemixBackendUrl(url) }
    }

    fun setBackendToken(token: String) {
        viewModelScope.launch { userPreferencesRepository.setRemixBackendToken(token) }
    }

    fun setUploadConsent(granted: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setRemixUploadConsent(granted) }
    }

    /**
     * Sends the track to the GPU separation server, which replaces the two mid/side pucks with
     * four real parts. Needs a local file: a Spotify-sourced track that has never been downloaded
     * has nothing to upload.
     */
    fun separateIntoStems() {
        val song = currentSong ?: return
        viewModelScope.launch {
            // The server is sent a file, so a streamed cloud track has to land on disk first.
            val source = withContext(Dispatchers.IO) { localCopyOf(song) }
            if (source == null) {
                _uiState.value = _uiState.value.copy(
                    separationDetail = "Download this song first, then try again.",
                )
                return@launch
            }
            // Only the loop region is sent: on an on-demand endpoint the bill is proportional to
            // audio length, and it is the region the studio actually plays.
            RemixStemSeparationWorker.enqueue(
                context = context,
                songId = song.id,
                sourcePath = source.absolutePath,
                startMs = _uiState.value.loopStartMs,
                durationMs = _uiState.value.loopLengthMs,
            )
            watchSeparation(song.id)
        }
    }

    /** A split takes about a minute and bills by the second; leaving is not the only way out. */
    fun cancelSeparation() {
        val songId = currentSong?.id ?: return
        workManager.cancelUniqueWork(RemixStemSeparationWorker.uniqueWorkName(songId))
        separationWatch?.cancel()
        _uiState.value = _uiState.value.copy(
            separating = false,
            separationPercent = 0,
            separationDetail = "Cancelled.",
        )
    }

    private fun watchSeparation(songId: String) {
        separationWatch?.cancel()
        separationWatch = viewModelScope.launch {
            workManager
                .getWorkInfosForUniqueWorkFlow(RemixStemSeparationWorker.uniqueWorkName(songId))
                .collect { infos ->
                    val info = infos.lastOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                            _uiState.value = _uiState.value.copy(
                                separating = true,
                                separationPercent = info.progress.getInt(RemixStemSeparationWorker.PROGRESS_PERCENT, 0),
                                separationDetail = info.progress.getString(RemixStemSeparationWorker.PROGRESS_DETAIL)
                                    ?: "Waiting for the separation server…",
                            )
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            _uiState.value = _uiState.value.copy(
                                separating = false,
                                separationDetail = "Four parts ready.",
                            )
                            // Reload so the stage picks up the new stems instead of mid/side.
                            currentSong?.let { song ->
                                currentSong = null
                                load(song)
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            _uiState.value = _uiState.value.copy(
                                separating = false,
                                separationDetail = info.outputData
                                    .getString(RemixStemSeparationWorker.OUTPUT_FAILURE_REASON)
                                    ?: "Separation failed.",
                            )
                        }
                        else -> _uiState.value = _uiState.value.copy(separating = false)
                    }
                }
        }
    }

    /**
     * @param preserve when non-null, keep these stems' positions, mutes and gains instead of
     *   resetting to [defaultPosition]. Moving the loop window re-decodes the region, but that is
     *   an implementation detail — it is not a reason to throw away an arrangement the user spent
     *   minutes building.
     * @param resumePlaying whether to start the engine once loaded. A reload triggered by nudging
     *   the loop must not turn audio back on after the user deliberately stopped it.
     */
    fun load(
        song: Song,
        preserve: List<StemUi>? = null,
        resumePlaying: Boolean = true,
    ) {
        if (currentSong?.id == song.id && _uiState.value.stems.isNotEmpty() && preserve == null) return
        currentSong = song
        _uiState.value = _uiState.value.copy(
            songTitle = song.title,
            loading = true,
            error = null,
            songDurationMs = song.duration.toInt(),
        )

        viewModelScope.launch {
            val xfadeFrames = (DEFAULT_XFADE_MS / 1000f * engine.sampleRate).toInt()
            val guard = engine.guardFramesFor(xfadeFrames)
            val startMs = _uiState.value.loopStartMs
            val lengthMs = _uiState.value.loopLengthMs

            val loaded = withContext(Dispatchers.Default) {
                // Every derivation (mid/side, mix − instrumental) happens at the source rate, where
                // it is sample-exact; conversion to the device rate is the very last step, and it
                // happens here, off the audio thread, with a real anti-imaging filter. Left to the
                // real-time path, a 44.1 kHz stem through a 48 kHz track was resampled by the tape
                // interpolator, and its images landed only ~33 dB down across the whole top end.
                withContext(Dispatchers.IO) { loadStems(song, startMs, lengthMs, guard) }
                    ?.map { LoadedStem(it.kind, Resampler.toRate(it.buffer, engine.sampleRate)) }
            }

            if (loaded == null || loaded.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = if (song.contentUriString.startsWith("spotify://")) {
                        "Couldn't get this song's audio. It streams from the internet, so check " +
                            "your connection — or download it and open Remix again."
                    } else {
                        "Couldn't read this song's audio file."
                    },
                )
                return@launch
            }

            engine.setStems(loaded.map { it.kind to it.buffer }, xfadeFrames)
            val stems = loaded.mapIndexed { index, item ->
                // Match by kind, not index: a reload can change how many stems there are (a
                // separation finishing turns two pucks into four), and carrying "Drums" over to
                // whatever now sits at index 1 would move the wrong thing.
                val kept = preserve?.firstOrNull { it.kind == item.kind }
                val x = kept?.x ?: defaultPosition(index, loaded.size).first
                val y = kept?.y ?: defaultPosition(index, loaded.size).second
                val muted = kept?.muted ?: false
                val gainDb = kept?.gainDb ?: 0f
                engine.params.setStemPosition(index, x * STAGE_RADIUS_M, 0f, y * STAGE_RADIUS_M)
                engine.params.setStemGain(index, if (muted) 0f else decibelsToLinear(gainDb))
                engine.params.setStemSend(index, 0.2f)
                StemUi(index = index, kind = item.kind, x = x, y = y, gainDb = gainDb, muted = muted)
            }
            if (preserve == null) {
                // Only a genuinely new song resets the rack. A loop nudge must leave Speed and
                // the loop fade exactly where the user put them.
                engine.params.decayFrames = 0
                engine.params.rate = 1f
            }

            val fourStems = loaded.size == FOUR_STEM_KINDS.size &&
                loaded.all { FOUR_STEM_KINDS.contains(it.kind) }
            _uiState.value = _uiState.value.copy(
                loading = false,
                stems = stems.toImmutableList(),
                separated = loaded.any { it.kind != StemKind.CENTER && it.kind != StemKind.SIDES },
                waveform = loaded.first().buffer.toWaveform().toImmutableList(),
                canSeparate = !fourStems && withContext(Dispatchers.IO) { localCopyOf(song) } != null,
                rate = if (preserve == null) 1f else _uiState.value.rate,
                decayMs = if (preserve == null) 0 else _uiState.value.decayMs,
            )
            startPose(_uiState.value.poseSourceId)
            if (resumePlaying) play() else stop()
        }
    }

    private class LoadedStem(val kind: String, val buffer: RemixStemBuffer)

    /**
     * A URI the decoder can actually open.
     *
     * Library tracks are already `content://`. Cloud tracks are `spotify://<id>`, which is a
     * *label*, not an address — no decoder can open it, and handing it to `MediaExtractor` was
     * why every cloud track failed to load. The resolution order matches
     * `DualPlayerEngine.resolveCloudUri` so the studio hears exactly what the player would:
     * a downloaded copy first (instant, works offline), otherwise the app's local streaming proxy.
     */
    private suspend fun playableUri(song: Song): Uri? {
        val raw = song.contentUriString
        if (!raw.startsWith("spotify://")) {
            return runCatching { Uri.parse(raw) }.getOrNull()
        }
        val spotifyId = raw.removePrefix("spotify://").substringBefore('/')
        localCopyOf(song)?.let { return Uri.fromFile(it) }

        // Not downloaded: stream it, and queue a copy so the next visit is instant.
        audioCacheManager.maybeAutoCache(spotifyId)
        if (!spotifyStreamProxy.ensureReady()) {
            Timber.w("Remix: the streaming proxy did not start in time")
            return null
        }
        return spotifyStreamProxy.resolveUri(raw)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    /** A real file on disk for this song, if one exists — what the separator needs to upload. */
    private suspend fun localCopyOf(song: Song): File? {
        val raw = song.contentUriString
        if (raw.startsWith("spotify://")) {
            val spotifyId = raw.removePrefix("spotify://").substringBefore('/')
            return audioCacheManager.getPlayableFile(spotifyId)
        }
        return song.path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf(File::exists)
    }

    /**
     * Stem precedence: real four-stem separation, then an existing instrumental (with the vocal
     * recovered by subtraction), then mid/side — which needs no model at all and is why the studio
     * opens instantly on any song.
     */
    private suspend fun loadStems(song: Song, startMs: Int, lengthMs: Int, guard: Int): List<LoadedStem>? {
        val directory = TaisInstrumentalIndex.stemsDirectory(context)
        val canonical = TaisInstrumentalIndex.canonicalSongId(song.id)

        // Separated stems cover a region, not the whole song, so find a set whose region contains
        // the loop being asked for and read at the right offset inside it.
        val wanted = startMs until (startMs + lengthMs)
        val covering = directory.listFiles()
            ?.filter { it.name.startsWith("${canonical}_stem_") && it.name.endsWith("_vocals.wav") }
            ?.firstOrNull { file ->
                val region = RemixStemSeparationWorker.regionOf(file.name)
                region != null && wanted.first >= region.first && wanted.last <= region.last
            }

        if (covering != null) {
            val region = RemixStemSeparationWorker.regionOf(covering.name)!!
            val prefix = covering.name.removeSuffix("vocals.wav")
            val sampleRate = 44_100
            val offsetFrames = ((startMs - region.first) / 1000f * sampleRate).toInt()
            val regionFrames = (lengthMs / 1000f * sampleRate).toInt()
            val buffers = FOUR_STEM_KINDS.mapNotNull { kind ->
                val file = File(directory, "$prefix$kind.wav")
                if (!file.exists()) null
                else RemixStemLoader.loadWavRegion(file, offsetFrames, regionFrames, guard)
                    ?.let { LoadedStem(kind, it) }
            }
            if (buffers.size == FOUR_STEM_KINDS.size) return buffers
            Timber.w("Remix: stem set for ${covering.name} is incomplete; falling back")
        }

        val uri = playableUri(song) ?: return null
        val midSide = RemixStemLoader.loadMidSideRegion(context, uri, startMs, lengthMs, guard) ?: return null

        val instrumental = TaisInstrumentalIndex.bestAvailableFile(context, song.id)
        if (instrumental != null) {
            val mix = midSide.first
            val instrumentalBuffer = RemixStemLoader.loadWavRegion(
                instrumental,
                (startMs / 1000f * mix.sampleRate).toInt(),
                mix.regionFrames,
                mix.guardFrames,
            )
            // Subtraction is only meaningful if both were sampled at the same rate; a 48 kHz
            // source against a 44.1 kHz render would cancel nothing and sound like phasing.
            if (instrumentalBuffer != null && instrumentalBuffer.sampleRate == mix.sampleRate) {
                val vocals = RemixStemLoader.deriveVocals(mix, instrumentalBuffer)
                if (vocals != null) {
                    return listOf(
                        LoadedStem(StemKind.VOCALS, vocals),
                        LoadedStem(StemKind.INSTRUMENTAL, instrumentalBuffer),
                    )
                }
            }
        }

        val sides = midSide.second
        val hasStereo = sides.samples.any { abs(it) > 1e-4f }
        return if (hasStereo) {
            listOf(LoadedStem(StemKind.CENTER, midSide.first), LoadedStem(StemKind.SIDES, sides))
        } else {
            listOf(LoadedStem(StemKind.CENTER, midSide.first))
        }
    }

    // ------------------------------------------------------------ playback

    fun play() {
        engine.start()
        _uiState.value = _uiState.value.copy(playing = true)
    }

    fun stop() {
        engine.stop()
        activePose?.stop()
        _uiState.value = _uiState.value.copy(playing = false)
    }

    // ------------------------------------------------------------- gestures

    /**
     * [x] and [y] are stage coordinates, −1..1, y negative meaning "in front of the listener".
     *
     * Writes **only** the engine parameter. A drag fires this at touch rate, and rebuilding the
     * immutable stem list on every move would recompose the screen sixty times a second next to
     * a live audio thread. The visible puck follows [RemixStageMotion], not this state; the UI
     * state is committed once, in [onStemDragEnd].
     */
    fun onStemMoved(index: Int, x: Float, y: Float) {
        val clampedX = x.coerceIn(-1f, 1f)
        val clampedY = y.coerceIn(-1f, 1f)
        engine.params.setStemPosition(index, clampedX * STAGE_RADIUS_M, 0f, clampedY * STAGE_RADIUS_M)
    }

    /** Commit a finished drag to UI state, once, so the position survives recomposition. */
    fun onStemDragEnd(index: Int, x: Float, y: Float) {
        val clampedX = x.coerceIn(-1f, 1f)
        val clampedY = y.coerceIn(-1f, 1f)
        _uiState.value = _uiState.value.copy(
            stems = _uiState.value.stems.map {
                if (it.index == index) it.copy(x = clampedX, y = clampedY) else it
            }.toImmutableList(),
        )
    }

    /** Nudge a stem by a fixed step — the screen-reader and D-pad path to spatial placement. */
    fun nudgeStem(index: Int, dx: Float, dy: Float) {
        val stem = _uiState.value.stems.firstOrNull { it.index == index } ?: return
        val nextX = (stem.x + dx).coerceIn(-1f, 1f)
        val nextY = (stem.y + dy).coerceIn(-1f, 1f)
        onStemMoved(index, nextX, nextY)
        onStemDragEnd(index, nextX, nextY)
    }

    /** Mute everything else, or restore everything if this stem is already alone. */
    fun onStemSoloToggled(index: Int) {
        val stems = _uiState.value.stems
        if (stems.isEmpty()) return
        val alreadySolo = stems.all { (it.index == index) != it.muted }
        val next = stems.map { stem ->
            val muted = if (alreadySolo) false else stem.index != index
            engine.params.setStemGain(stem.index, if (muted) 0f else decibelsToLinear(stem.gainDb))
            stem.copy(muted = muted)
        }
        _uiState.value = _uiState.value.copy(stems = next.toImmutableList())
    }

    fun onStemMuteToggled(index: Int) {
        val stems = _uiState.value.stems.map { stem ->
            if (stem.index != index) stem else {
                val muted = !stem.muted
                engine.params.setStemGain(index, if (muted) 0f else decibelsToLinear(stem.gainDb))
                stem.copy(muted = muted)
            }
        }
        _uiState.value = _uiState.value.copy(stems = stems.toImmutableList())
    }

    fun setRate(rate: Float) {
        val clamped = rate.coerceIn(RemixState.MIN_RATE, RemixState.MAX_RATE)
        engine.params.rate = clamped
        _uiState.value = _uiState.value.copy(rate = clamped)
    }

    fun setFilter(cutoffHz: Float, resonance: Float, mode: String) {
        engine.params.cutoffHz = cutoffHz.coerceIn(200f, 18_000f)
        engine.params.resonance = resonance.coerceIn(0.3f, 12f)
        engine.params.filterMode = when (mode) {
            "bp" -> com.theveloper.pixelplay.data.remix.RemixParamsSnapshot.FILTER_BAND_PASS
            "hp" -> com.theveloper.pixelplay.data.remix.RemixParamsSnapshot.FILTER_HIGH_PASS
            else -> com.theveloper.pixelplay.data.remix.RemixParamsSnapshot.FILTER_LOW_PASS
        }
        _uiState.value = _uiState.value.copy(cutoffHz = cutoffHz, resonance = resonance, filterMode = mode)
    }

    fun setLoFi(bits: Int, decim: Int) {
        engine.params.bits = bits.coerceIn(4, 16)
        engine.params.decim = decim.coerceIn(1, 16)
        _uiState.value = _uiState.value.copy(bits = bits, decim = decim)
    }

    fun setReverb(mix: Float, rt60: Float) {
        engine.params.reverbMix = mix.coerceIn(0f, 1f)
        engine.params.rt60 = rt60.coerceIn(0.3f, 6f)
        _uiState.value = _uiState.value.copy(reverbMix = mix, rt60 = rt60)
    }

    fun setDecay(decayMs: Int) {
        engine.params.decayFrames = (decayMs / 1000f * engine.sampleRate).toInt()
        _uiState.value = _uiState.value.copy(decayMs = decayMs)
    }

    /**
     * Moves the loop window; the region is reloaded because residency is what makes it seamless.
     *
     * The reload keeps the arrangement. This used to be the most destructive control on the
     * screen — the jump buttons and the length chips silently reset every puck to a default
     * circle, put Speed back to 1.00×, switched the loop fade off, and started playback even if
     * the user had just stopped it.
     */
    fun setLoop(startMs: Int, lengthMs: Int) {
        val song = currentSong ?: return
        val clampedLength = lengthMs.coerceIn(RemixState.MIN_LOOP_MS, RemixState.MAX_LOOP_MS)
        val clampedStart = startMs.coerceAtLeast(0)
        val state = _uiState.value
        if (state.loopStartMs == clampedStart && state.loopLengthMs == clampedLength) return
        val keep = state.stems
        val wasPlaying = state.playing
        _uiState.value = state.copy(loopStartMs = clampedStart, loopLengthMs = clampedLength)
        currentSong = null // force load() to rebuild
        load(song, preserve = keep, resumePlaying = wasPlaying)
    }

    // ----------------------------------------------------------------- pose

    fun setPoseSource(id: String) {
        _uiState.value = _uiState.value.copy(poseSourceId = id)
        startPose(id)
    }

    fun setManualHeading(radians: Float) {
        manualPose.setYaw(radians)
    }

    fun recentrePose() {
        poseFilter.reset()
        deviceRotation.recentre()
        engine.params.yaw = 0f
        engine.params.pitch = 0f
    }

    private fun startPose(id: String) {
        activePose?.stop()
        val source = when (id) {
            ListenerPoseSource.ID_HEADSET -> headTracker.takeIf { it.isAvailable() } ?: deviceRotation
            ListenerPoseSource.ID_MANUAL -> manualPose
            else -> deviceRotation.takeIf { it.isAvailable() } ?: manualPose
        }
        activePose = source
        poseFilter.reset()
        source.start { yaw, pitch, roll ->
            poseFilter.accept(yaw, pitch, roll)
            engine.params.yaw = poseFilter.yaw
            engine.params.pitch = poseFilter.pitch
        }
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    private fun decibelsToLinear(db: Float): Float = 10f.pow(db / 20f)

    /** Evenly spaced around the listener, front-biased: the first stem sits dead ahead. */
    private fun defaultPosition(index: Int, count: Int): Pair<Float, Float> {
        if (count <= 1) return 0f to -0.6f
        val angle = (2.0 * Math.PI * index / count).toFloat()
        return (sin(angle) * 0.62f) to (-cos(angle) * 0.62f)
    }

    private fun RemixStemBuffer.toWaveform(buckets: Int = 240): List<Float> {
        val start = guardFrames
        val end = guardFrames + regionFrames
        val size = (end - start).coerceAtLeast(1)
        val step = (size / buckets).coerceAtLeast(1)
        val peaks = ArrayList<Float>(buckets)
        var i = start
        while (i < end && peaks.size < buckets) {
            var peak = 0f
            var j = i
            val limit = minOf(i + step, end)
            while (j < limit) {
                val magnitude = abs(samples[j])
                if (magnitude > peak) peak = magnitude
                j++
            }
            peaks.add(peak)
            i += step
        }
        return peaks
    }

    private companion object {
        const val DEFAULT_XFADE_MS = 40
        /** How far a puck at the edge of the stage sits from the listener. */
        const val STAGE_RADIUS_M = 4f
        val FOUR_STEM_KINDS = listOf(StemKind.VOCALS, StemKind.DRUMS, StemKind.BASS, StemKind.OTHER)
    }
}

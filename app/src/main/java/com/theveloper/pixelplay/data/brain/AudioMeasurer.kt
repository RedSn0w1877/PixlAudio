package com.theveloper.pixelplay.data.brain

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.MediaItem
import com.theveloper.pixelplay.brain.AudioFeatures
import com.theveloper.pixelplay.data.brain.store.AudioMeasureEntity
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import com.theveloper.pixelplay.data.stream.PROXY_PURPOSE_DOWNLOAD
import com.theveloper.pixelplay.data.stream.PROXY_PURPOSE_HEADER
import com.theveloper.pixelplay.data.stream.StreamNetworkConditions
import java.nio.ByteOrder
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Stage 5 (docs/brain-spec.md 3.3): measures tempo and loudness of the tracks the engine plays or has prepared next, from a short window
 * of their decoded audio (40 s from 20% in), with the pure [AudioFeatures.Estimator]. The result goes to `audio_measure`; the DNA and
 * the flow ordering read it from there.
 *
 * Rules:
 *  - One track at a time, on one background-priority thread; nothing on the main thread but [onTrackChanged] and [onStall].
 *  - It waits [SETTLE_MS] after a track starts (the player's own first buffering, and a skip-through costs nothing), and never decodes
 *    while playback is stalled: a stall pauses the decode, and a stall that lasts aborts it without counting as a failure.
 *  - A track already on disk (offline or auto cache) is decoded from the file, on any network. Otherwise the window is read through
 *    the local stream proxy, on an unmetered network without Data Saver only (about 1 MB per track).
 *  - A track is measured once per estimator version; a failure is retried after a day, and given up for a month after three.
 */
@Singleton
class AudioMeasurer @Inject constructor(
    private val store: AudioMeasureStore,
    private val catalog: TrackCatalog,
    private val identity: TrackResolver,
    private val clock: BrainClock,
    private val brainSwitch: BrainSwitch,
    private val networkConditions: StreamNetworkConditions,
    private val audioCache: dagger.Lazy<AudioCacheManager>,
    private val streamProxy: dagger.Lazy<SpotifyStreamProxy>,
) {
    /** What the developer diagnostics row shows for the song that is playing. */
    data class Status(
        val mediaId: String,
        val state: Int,
        val result: AudioFeatures.Result? = null,
        /** The tempo the DNA keeps for it and where it came from (`TrackDna.TEMPO_*`), when known. */
        val keptTempoX10: Int = 0,
        val keptTempoSource: Int = 0,
        val fromFile: Boolean = false,
        val tookMs: Long = 0L,
    )

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "brain-audio-measure").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var worker: Job? = null

    @Volatile
    private var stalled = false

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private class Target(val mediaId: String, val uri: String?, val durationMs: Long)

    /** Main thread: the track that is playing changed; [items] is it first, then what the prefetcher prepares next. */
    fun onTrackChanged(items: List<MediaItem>) {
        worker?.cancel()
        val targets = items.take(MAX_TARGETS).map {
            Target(it.mediaId, it.localConfiguration?.uri?.toString(), it.mediaMetadata.durationMs ?: 0L)
        }
        val current = targets.firstOrNull()
        if (current == null) {
            _status.value = null
            return
        }
        _status.value = Status(current.mediaId, STATE_WAITING)
        worker = scope.launch {
            delay(SETTLE_MS)
            for ((i, t) in targets.withIndex()) {
                ensureActive()
                measure(t, isCurrent = i == 0)
            }
        }
    }

    /** Main thread: playback is stalled (buffering while it should play), or no longer. */
    fun onStall(isStalled: Boolean) {
        stalled = isStalled
    }

    private fun publish(t: Target, isCurrent: Boolean, s: Status) {
        if (isCurrent || _status.value?.mediaId == t.mediaId) _status.value = s
    }

    private suspend fun measure(t: Target, isCurrent: Boolean) {
        if (brainSwitch.engineFlow.first() != BrainEngineChoice.BRAIN) {
            publish(t, isCurrent, Status(t.mediaId, STATE_OFF))
            return
        }
        val facts = try { catalog.factsFor(t.mediaId, null) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (facts == null) {
            publish(t, isCurrent, Status(t.mediaId, STATE_UNKNOWN_TRACK))
            return
        }
        val durationMs = if (facts.durationMs > 0) facts.durationMs else t.durationMs
        val rid = identity.resolveTrack(facts.keys, durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        if (rid == 0L) {
            publish(t, isCurrent, Status(t.mediaId, STATE_UNKNOWN_TRACK))
            return
        }
        val now = clock.nowMs()
        if (!store.wants(rid, now)) {
            val row = store.row(rid)
            publish(t, isCurrent, statusOf(t.mediaId, row?.result(), if (row?.result() != null) STATE_DONE else STATE_GAVE_UP))
            return
        }
        // A downloaded or auto-cached track is read from the disk; anything else only on an unmetered network.
        val file = try { audioCache.get().getPlayableFile(t.mediaId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val source: String
        if (file != null) {
            source = file.absolutePath
        } else {
            val conditions = networkConditions.current()
            if (conditions.metered || conditions.dataSaver) {
                publish(t, isCurrent, Status(t.mediaId, STATE_WAITING_FOR_WIFI))
                return
            }
            val uri = t.uri ?: return
            val proxy = streamProxy.get()
            if (!proxy.ensureReady()) return
            source = proxy.resolveUri(uri)?.takeIf { it.isNotEmpty() } ?: run {
                publish(t, isCurrent, Status(t.mediaId, STATE_UNKNOWN_TRACK))
                return
            }
        }
        publish(t, isCurrent, Status(t.mediaId, STATE_MEASURING, fromFile = file != null))
        val started = SystemClock.elapsedRealtime()
        val startMs = AudioFeatures.windowStartMs(durationMs)
        val outcome = try {
            decode(source, isFile = file != null, startMs = startMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "brain: measuring %s failed", t.mediaId)
            null
        }
        val took = SystemClock.elapsedRealtime() - started
        when {
            outcome === STALLED -> publish(t, isCurrent, Status(t.mediaId, STATE_WAITING))
            outcome == null || outcome.analysedMs < AudioFeatures.MIN_LOUDNESS_MS -> {
                store.failed(rid, durationMs, clock.nowMs())
                publish(t, isCurrent, Status(t.mediaId, STATE_FAILED, tookMs = took))
            }
            else -> {
                store.save(
                    AudioMeasureEntity(
                        rid = rid, version = outcome.version, bpmX10 = outcome.bpmX10, tempoConf = outcome.tempoConf, lufsX10 = outcome.lufsX10,
                        onsetX1000 = outcome.onsetX1000, analysedMs = outcome.analysedMs, windowStartMs = startMs, durationMs = durationMs,
                        measuredAtMs = clock.nowMs(), failures = 0,
                    )
                )
                Timber.d("brain: measured %s: %d.%d bpm (conf %d), %d.%d LUFS in %d ms", t.mediaId, outcome.bpmX10 / 10, outcome.bpmX10 % 10,
                    outcome.tempoConf, outcome.lufsX10 / 10, kotlin.math.abs(outcome.lufsX10 % 10), took)
                publish(t, isCurrent, statusOf(t.mediaId, outcome, STATE_DONE, fromFile = file != null, tookMs = took))
            }
        }
    }

    private fun statusOf(mediaId: String, r: AudioFeatures.Result?, state: Int, fromFile: Boolean = false, tookMs: Long = 0L): Status {
        val kept = if (r != null) AudioFeatures.chooseTempo(0, r) else intArrayOf(0, 0)
        return Status(mediaId, state, r, kept[0], kept[1], fromFile, tookMs)
    }

    /** The reusable sample buffers of the decode loop (one decode at a time, on the one thread). */
    private var shorts = ShortArray(0)
    private var floats = FloatArray(0)

    /**
     * Decodes [WINDOW_MS] from [startMs] and runs the estimator. Returns [STALLED] when playback stalled for longer than
     * [STALL_GIVE_UP_MS] meanwhile, null when the file could not be decoded.
     */
    private suspend fun decode(source: String, isFile: Boolean, startMs: Long): AudioFeatures.Result? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (isFile) extractor.setDataSource(source) else extractor.setDataSource(source, mapOf(PROXY_PURPOSE_HEADER to PROXY_PURPOSE_DOWNLOAD))
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) { track = i; break }
            }
            if (track < 0) return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            if (startMs > 0L) extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val endUs = (startMs + AudioFeatures.WINDOW_MS + 2_000L) * 1000L
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var estimator: AudioFeatures.Estimator? = null
            var floatPcm = false
            var channels = 1
            var inputDone = false
            val deadline = SystemClock.elapsedRealtime() + DEADLINE_MS
            var stallSince = 0L
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() > deadline) break
                if (stalled) {
                    // The listener's playback comes first: wait it out, and give the window up if it lasts.
                    if (stallSince == 0L) stallSince = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - stallSince > STALL_GIVE_UP_MS) return STALLED
                    delay(250L)
                    continue
                }
                stallSince = 0L
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val buffer = c.getInputBuffer(inIndex) ?: return null
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > endUs) {
                            c.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = c.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = c.outputFormat
                        if (estimator == null) {
                            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            estimator = AudioFeatures.Estimator(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels)
                        }
                    }
                    outIndex >= 0 -> {
                        val est = estimator ?: AudioFeatures.Estimator(c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1).also { channels = it }).also { estimator = it }
                        val buffer = c.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            if (floatPcm) {
                                val n = info.size / 4
                                if (floats.size < n) floats = FloatArray(n)
                                buffer.asFloatBuffer().get(floats, 0, n)
                                est.feed(floats, 0, n / channels)
                            } else {
                                val n = info.size / 2
                                if (shorts.size < n) shorts = ShortArray(n)
                                buffer.asShortBuffer().get(shorts, 0, n)
                                est.feed(shorts, 0, n / channels)
                            }
                        }
                        c.releaseOutputBuffer(outIndex, false)
                        if (est.full || (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
            return estimator?.finish()
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    companion object {
        const val STATE_WAITING = 0
        const val STATE_MEASURING = 1
        const val STATE_DONE = 2
        const val STATE_FAILED = 3
        const val STATE_WAITING_FOR_WIFI = 4
        const val STATE_OFF = 5
        const val STATE_UNKNOWN_TRACK = 6
        const val STATE_GAVE_UP = 7

        /** The track that plays and the next one. */
        const val MAX_TARGETS = 2
        const val SETTLE_MS = 10_000L
        const val DEADLINE_MS = 60_000L
        const val STALL_GIVE_UP_MS = 8_000L

        /** A marker result: the decode stopped because playback stalled. */
        private val STALLED = AudioFeatures.Result(0, 0, AudioFeatures.LOUDNESS_UNKNOWN, 0)
    }
}

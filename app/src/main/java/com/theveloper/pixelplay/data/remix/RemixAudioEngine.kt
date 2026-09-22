package com.theveloper.pixelplay.data.remix

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import com.theveloper.pixelplay.data.remix.dsp.BinauralPanner
import com.theveloper.pixelplay.data.remix.dsp.LoopReader
import com.theveloper.pixelplay.data.remix.dsp.RemixStemBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Plays the remix: one `AudioTrack`, one DSP thread, everything mixed in-process.
 *
 * **Why not Media3.** Per-stem spatialization needs four sources sample-aligned to well under a
 * millisecond (the inter-aural delay tops out at 29 frames). Every mixing path Media3 offers ends
 * in N `AudioTrack`s summed by the platform — `DualPlayerEngine.performOverlapTransition` lands
 * within ±125 ms and `InstrumentalCrossfadeController` resyncs on a 200 ms tolerance. That is
 * fine for a crossfade between songs and useless for stems of the same song, which would comb
 * filter. A synthesizing `MediaSource` would keep the MediaSession but buffers ~250 ms ahead,
 * so a dragged puck would move long after the finger.
 *
 * **What it costs.** No background playback, no notification, no lock-screen or Android Auto
 * control while the studio is open — the same trade the DJ decks already made. A remix with no
 * screen to drag on is not a thing anyone wants; exporting a remix to a file (phase 2) is how it
 * becomes an ordinary, backgroundable track.
 *
 * This class owns lifecycle and I/O only. All DSP lives in [RemixGraph], which has no Android in
 * it and is therefore unit-testable and reusable for offline rendering.
 */
@Singleton
class RemixAudioEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    enum class State { IDLE, PLAYING, PAUSED }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Written by the UI thread, read once per block by the DSP thread. */
    val params = RemixParams()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _loadedStems = MutableStateFlow<List<String>>(emptyList())
    val loadedStems: StateFlow<List<String>> = _loadedStems.asStateFlow()

    val sampleRate: Int = audioManager
        ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        ?.takeIf { it > 0 } ?: 48_000

    private val framesPerBurst: Int = audioManager
        ?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        ?.takeIf { it > 0 } ?: 256

    private val blockSize = framesPerBurst.coerceIn(96, 512)

    private val graph = RemixGraph(sampleRate)
    private val snapshot = RemixParamsSnapshot()

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var bufferSampleRate = sampleRate

    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var duckGain = 1f

    /** Peaks and playhead for the UI; read on the frame clock, never as a Flow from the DSP thread. */
    val stemPeaks: FloatArray get() = graph.stemPeaks
    val playheadFrames: Float get() = graph.playheadFrames
    val clipping: Boolean get() = graph.clipping

    /** 0..1 through the loop region; 0 when nothing is loaded. */
    val playheadFraction: Float
        get() {
            val frames = graph.regionFrames
            return if (frames <= 0) 0f else (graph.playheadFrames / frames).coerceIn(0f, 1f)
        }

    /** Frames of guard each loaded region needs: the crossfade plus the worst-case ITD. */
    fun guardFramesFor(xfadeFrames: Int): Int =
        xfadeFrames + BinauralPanner.maxItdFrames(sampleRate) + LoopReader.HERMITE_MARGIN + 8

    /**
     * Installs the stems to play. Call off the audio thread; buffers are swapped in whole, so a
     * reload never leaves the graph reading a half-written array.
     */
    fun setStems(stems: List<Pair<String, RemixStemBuffer>>, xfadeFrames: Int) {
        stems.forEachIndexed { index, (_, buffer) ->
            graph.setStem(index, buffer, xfadeFrames)
        }
        for (index in stems.size until MAX_STEMS) graph.setStem(index, null, xfadeFrames)
        bufferSampleRate = stems.firstOrNull()?.second?.sampleRate ?: sampleRate
        params.stemCount = stems.size.coerceAtMost(MAX_STEMS)
        _loadedStems.value = stems.map { it.first }
    }

    fun setCrossfadeFrames(frames: Int) = graph.setCrossfade(frames)

    fun start() {
        if (running) return
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            Timber.w("Remix engine: device rejected a float stereo track at $sampleRate Hz")
            return
        }
        // Three bursts of headroom: one being played, one queued, one being filled.
        val bufferBytes = maxOf(minBytes, blockSize * BYTES_PER_FRAME * 3)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()

        if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
            Timber.w("Remix engine: AudioTrack failed to initialise")
            runCatching { newTrack.release() }
            return
        }

        requestFocus()
        graph.prepare(blockSize)
        track = newTrack
        running = true
        newTrack.play()
        _state.value = State.PLAYING

        thread = Thread({ renderLoop(newTrack) }, "RemixAudioEngine").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        track?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
        abandonFocus()
        _state.value = State.IDLE
    }

    private fun renderLoop(track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // Everything the loop touches is allocated here, before the first write. One GC pause
        // inside a 3 ms budget is an audible dropout.
        val left = FloatArray(blockSize)
        val right = FloatArray(blockSize)
        val interleaved = FloatArray(blockSize * 2)
        var lastUnderrunCheck = System.nanoTime()
        var grewBufferOnce = false

        while (running) {
            params.snapshotInto(snapshot)
            // A 44.1 kHz stem on a 48 kHz device plays back at the wrong speed unless the read
            // rate compensates. Doing it here rather than resampling on load keeps the loader
            // simple and costs nothing: the reader already interpolates.
            snapshot.rate = snapshot.rate * (bufferSampleRate.toFloat() / sampleRate)
            snapshot.masterGain = snapshot.masterGain * duckGain

            graph.process(snapshot, left, right, blockSize)

            var j = 0
            for (i in 0 until blockSize) {
                interleaved[j++] = left[i]
                interleaved[j++] = right[i]
            }

            val written = track.write(interleaved, 0, interleaved.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Timber.w("Remix engine: AudioTrack.write returned $written")
                break
            }

            // Underruns are the one failure that is invisible in a log until someone says "it
            // crackles on my phone". Check cheaply, react once, and say so.
            val now = System.nanoTime()
            if (now - lastUnderrunCheck > 1_000_000_000L) {
                lastUnderrunCheck = now
                val underruns = track.underrunCount
                if (underruns > 0 && !grewBufferOnce) {
                    grewBufferOnce = true
                    Timber.w("Remix engine: $underruns underruns at block $blockSize")
                }
            }
        }
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    // Duck rather than tear down: a notification should not cost the user their
                    // engine, their loaded stems or their place in the loop.
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duckGain = 0.2f
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> duckGain = 0f
                    AudioManager.AUDIOFOCUS_GAIN -> duckGain = 1f
                    AudioManager.AUDIOFOCUS_LOSS -> stop()
                }
            }
            .build()
        focusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
        duckGain = 1f
    }

    companion object {
        const val MAX_STEMS = 4
        private const val BYTES_PER_FRAME = 2 * 4 // stereo, float
    }
}

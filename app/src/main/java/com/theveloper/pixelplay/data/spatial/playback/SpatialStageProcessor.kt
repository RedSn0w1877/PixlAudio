package com.theveloper.pixelplay.data.spatial.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.data.spatial.SpatialRender
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The on-device spatial stage as a Media3 audio processor (cloud-audio-spec §7): the last processor of each player's
 * chain (after the music-haptics tee, so haptics keep analysing the dry signal).
 *
 * - **Formats**: 44.1 and 48 kHz stereo, 16-bit (what the chain gets with float output off) or float. Anything else is
 *   left out of the pipeline for that stream (§6.3: such a song plays without the stage).
 * - **Per item** (§6.3, integration map 2.3): a flush that follows a `configure` is a new stream; its
 *   [AudioProcessor.StreamMetadata] names the item, and an item that is already a spatial render is bypassed from its
 *   first sample, so it is never spatialized twice. A flush without a `configure` is a seek: the history starts over.
 *   An item boundary at the same rate keeps the history (gapless stays gapless) and ramps any change in 10 ms.
 * - **New playback parameters** (a speed change, skip silence): `DefaultAudioSink` drains the chain (end of stream),
 *   then flushes it with no `configure`, at the position where the audio simply carries on. That is not a seek: the
 *   history and the mix carry on too, so a speed change is not a dropout.
 * - **Live parameters**: [SpatialStageLink.state] is read once per buffer; a new profile or a bypass ramps over 80 ms.
 *
 * No allocation while processing: scratch arrays are made once, the rings once for the whole table, before the first
 * preset is used (the kernel is idle then), so a profile change never replaces a ring the old profile still reads.
 */
@OptIn(UnstableApi::class)
class SpatialStageProcessor internal constructor(
    private val link: SpatialStageLink,
) : BaseAudioProcessor() {

    private val kernel = SpatialStageKernel()
    private val block = kernel.maxBlock
    private val inL = FloatArray(block)
    private val inR = FloatArray(block)
    private val outL = FloatArray(block)
    private val outR = FloatArray(block)
    private val bytes = ByteArray(block * 2 * Float.SIZE_BYTES)

    private val period = Timeline.Period()
    private val window = Timeline.Window()

    /** A `configure` came since the last flush: the next flush starts a new stream (not a seek). */
    private var configuredSinceFlush = false

    /** The rate the kernel's history belongs to (0: none, the next stream starts from a zero state). */
    private var activeRate = 0

    /** This stream is already a spatial render: the stage stays dry for it. */
    private var renderItem = false

    private var lastState: SpatialStageState? = null

    /** Input frames queued since the last flush (where the stream carries on from, with [lastOffsetUs]). */
    private var framesSinceFlush = 0L

    /** End of stream was queued since the last flush: the sink drained everything queued before it. */
    private var drained = false

    /** The last flush's position ([AudioProcessor.StreamMetadata.positionOffsetUs]; [C.TIME_UNSET]: none yet). */
    private var lastOffsetUs = C.TIME_UNSET

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        configuredSinceFlush = true
        val supported = inputAudioFormat.channelCount == 2 &&
            SpatialPreviewTables.supportsRate(inputAudioFormat.sampleRate) &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        return if (supported) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val newStream = configuredSinceFlush
        configuredSinceFlush = false
        val offsetUs = streamMetadata.positionOffsetUs
        // New playback parameters: drained, no configure, and the stream goes on where the queued frames ended. A seek
        // never drains; Media3's own resync after a jump (over 200 ms) drains but lands elsewhere.
        val carriesOn = !newStream && drained && continuesAt(offsetUs)
        lastOffsetUs = offsetUs
        framesSinceFlush = 0
        drained = false
        var identity: String? = null
        var mediaId: String? = null
        var identified = false
        if (newStream) {
            val item = itemOf(streamMetadata)
            if (item != null) {
                identified = true
                mediaId = item.mediaId
                identity = item.localConfiguration?.uri?.toString()
            }
        }
        onStreamStarting(newStream, identified, mediaId, identity, carriesOn)
    }

    override fun onQueueEndOfStream() {
        drained = true
    }

    /**
     * Whether a stream restarting at [offsetUs] picks up where the frames queued since the last flush ended, within
     * [CONTINUITY_TOLERANCE_US] (Media3 trims the encoder delay and padding before this processor, so the count can be
     * a few tens of milliseconds short of the timestamps).
     */
    private fun continuesAt(offsetUs: Long): Boolean {
        val rate = inputAudioFormat.sampleRate
        if (rate <= 0 || offsetUs == C.TIME_UNSET || lastOffsetUs == C.TIME_UNSET) return false
        val expectedUs = lastOffsetUs + framesSinceFlush * C.MICROS_PER_SECOND / rate
        return abs(offsetUs - expectedUs) <= CONTINUITY_TOLERANCE_US
    }

    /**
     * The flush's decision, apart from the timeline so tests can drive it: [newStream] false is a seek, unless the
     * audio [carriesOn] (new playback parameters: history and mix stay); [identified] says whether the stream's item
     * was found (else the previous item's decision stays, integration map 2.3).
     */
    internal fun onStreamStarting(
        newStream: Boolean,
        identified: Boolean,
        mediaId: String?,
        uri: String?,
        carriesOn: Boolean = false,
    ) {
        configuredSinceFlush = false
        val supported = outputAudioFormat != AudioFormat.NOT_SET
        if (newStream && identified) {
            val render = link.renditionOf(uri)
            renderItem = render != null
            link.onStreamStarted(mediaId, render, supported)
        }
        if (!supported) {
            activeRate = 0
            return
        }
        val rate = inputAudioFormat.sampleRate
        kernel.setRampFrames(rate * RAMP_MS / 1000)
        applyState(link.state, rate)
        if (rate != activeRate) {
            // The first stream, or a new rate: the history belongs to another rate.
            kernel.reset()
            activeRate = rate
        } else if (newStream) {
            kernel.boundary(rate * BOUNDARY_RAMP_MS / 1000)
        } else if (!carriesOn) {
            // A seek: the history starts over.
            kernel.reset()
        }
    }

    private fun applyState(state: SpatialStageState, rate: Int) {
        lastState = state
        val tables = state.tables
        val profile = state.profile
        val preset = if (tables != null && profile != null) tables.preset(profile, rate) else null
        // The rings are sized for the whole table before the first preset is used: the kernel is idle then, so the one
        // allocation is never heard, and a later profile change never swaps out a ring the old preset still reads.
        if (tables != null && preset != null) kernel.ensureCapacity(tables)
        kernel.setTarget(preset, state.wet && !renderItem)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val bytesPerFrame = format.bytesPerFrame
        val frames = remaining / bytesPerFrame
        framesSinceFlush += frames
        drained = false
        val output = replaceOutputBuffer(frames * bytesPerFrame)
        val state = link.state
        if (state !== lastState) applyState(state, format.sampleRate)
        if (kernel.isIdle) {
            // Nobody has selected a profile: a plain copy, the cheapest thing a processor in the chain can do.
            val limit = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + frames * bytesPerFrame)
            output.put(inputBuffer)
            inputBuffer.limit(limit)
            inputBuffer.position(limit)
            output.flip()
            return
        }
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val littleEndian = inputBuffer.order() == ByteOrder.LITTLE_ENDIAN
        var done = 0
        while (done < frames) {
            val n = minOf(block, frames - done)
            if (isFloat) readFloat(inputBuffer, n) else read16(inputBuffer, n, littleEndian)
            kernel.process(inL, inR, outL, outR, n)
            if (isFloat) writeFloat(output, n) else write16(output, n, output.order() == ByteOrder.LITTLE_ENDIAN)
            done += n
        }
        // A partial frame (never sent by the sink) is dropped rather than misread.
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private fun read16(input: ByteBuffer, n: Int, littleEndian: Boolean) {
        val b = bytes
        input.get(b, 0, n * 4)
        for (i in 0 until n) {
            val o = i * 4
            val l: Int
            val r: Int
            if (littleEndian) {
                l = (b[o + 1].toInt() shl 8) or (b[o].toInt() and 0xFF)
                r = (b[o + 3].toInt() shl 8) or (b[o + 2].toInt() and 0xFF)
            } else {
                l = (b[o].toInt() shl 8) or (b[o + 1].toInt() and 0xFF)
                r = (b[o + 2].toInt() shl 8) or (b[o + 3].toInt() and 0xFF)
            }
            inL[i] = l * SHORT_TO_FLOAT
            inR[i] = r * SHORT_TO_FLOAT
        }
    }

    private fun write16(output: ByteBuffer, n: Int, littleEndian: Boolean) {
        val b = bytes
        for (i in 0 until n) {
            val l = toShort(outL[i])
            val r = toShort(outR[i])
            val o = i * 4
            if (littleEndian) {
                b[o] = l.toByte(); b[o + 1] = (l shr 8).toByte()
                b[o + 2] = r.toByte(); b[o + 3] = (r shr 8).toByte()
            } else {
                b[o] = (l shr 8).toByte(); b[o + 1] = l.toByte()
                b[o + 2] = (r shr 8).toByte(); b[o + 3] = r.toByte()
            }
        }
        output.put(b, 0, n * 4)
    }

    private fun readFloat(input: ByteBuffer, n: Int) {
        for (i in 0 until n) {
            inL[i] = input.getFloat()
            inR[i] = input.getFloat()
        }
    }

    private fun writeFloat(output: ByteBuffer, n: Int) {
        for (i in 0 until n) {
            output.putFloat(outL[i].coerceIn(-1f, 1f))
            output.putFloat(outR[i].coerceIn(-1f, 1f))
        }
    }

    override fun onReset() {
        kernel.setTarget(null, false)
        kernel.reset()
        configuredSinceFlush = false
        activeRate = 0
        renderItem = false
        lastState = null
        framesSinceFlush = 0
        drained = false
        lastOffsetUs = C.TIME_UNSET
    }

    private fun itemOf(metadata: AudioProcessor.StreamMetadata): androidx.media3.common.MediaItem? {
        val uid = metadata.periodUid ?: return null
        val timeline = metadata.timeline
        if (timeline.isEmpty) return null
        val index = timeline.getIndexOfPeriod(uid)
        if (index == C.INDEX_UNSET) return null
        return try {
            timeline.getWindow(timeline.getPeriod(index, period).windowIndex, window).mediaItem
        } catch (e: RuntimeException) {
            null
        }
    }

    internal companion object {
        /** §7: a preset change or a bypass ramps over about 80 ms. */
        const val RAMP_MS = 80

        /** An item boundary that changes the decision (a render starts) ramps this fast instead. */
        const val BOUNDARY_RAMP_MS = 10

        /**
         * A drained flush this close to where the queued frames end continues the stream: more than the encoder delay
         * and padding Media3 trims at a stream's start (tens of milliseconds), less than the 200 ms jump Media3 itself
         * treats as a discontinuity.
         */
        const val CONTINUITY_TOLERANCE_US = 100_000L

        private const val SHORT_TO_FLOAT = 1f / 32768f

        /** Saturating float to 16-bit (§7: the only protection after the stage). */
        fun toShort(value: Float): Int {
            val scaled = value * 32768f
            return when {
                scaled >= 32767f -> 32767
                scaled <= -32768f -> -32768
                else -> Math.round(scaled)
            }
        }
    }
}

/** What the stage processors read and report; [SpatialStage] in the app, a fake in tests. Any thread. */
interface SpatialStageLink {
    /** The current parameters (one volatile read per buffer). */
    val state: SpatialStageState

    /** The render a stream plays, by its item's URI (`spotify://<id>` or a baked `file://`), or null for the stream. */
    fun renditionOf(uri: String?): SpatialRender?

    /** A new stream reached the stage (playback thread): its item, the render it is (or null), and whether the stage takes its format. */
    fun onStreamStarted(mediaId: String?, render: SpatialRender?, formatSupported: Boolean)
}

/** The stage's parameters as one immutable value (integration map 2.5: one volatile parameter object). */
class SpatialStageState(
    /** The parsed table (null until it is read, or if it could not be). */
    val tables: SpatialPreviewTables?,
    /** The selected profile (null: Spatial audio is off). */
    val profile: com.theveloper.pixelplay.data.spatial.SpatialProfile?,
    /** Spatial audio is active (§6.3: a profile, the route rule, not Sing, this phone): the stage is heard. */
    val wet: Boolean,
) {
    companion object {
        val OFF = SpatialStageState(tables = null, profile = null, wet = false)
    }
}

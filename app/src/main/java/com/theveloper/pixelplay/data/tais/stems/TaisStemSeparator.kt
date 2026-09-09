package com.theveloper.pixelplay.data.tais.stems

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.theveloper.pixelplay.data.tais.TaisAiEngine
import com.theveloper.pixelplay.data.cache.RetainedAudioFiles
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.data.tais.dsp.Fft
import com.theveloper.pixelplay.data.tais.dsp.openDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * On-device MDX-Net vocal separation using the bundled UVR-MDX-NET-Voc_FT graph.
 *
 * The float32 NCHW input/output contract is [1, 4, 3072, 256], with planes
 * [left-real, left-imaginary, right-real, right-imaginary] at 44.1 kHz. The model
 * predicts vocals from a 6144-point STFT, hop 1024, using a periodic Hann window
 * and reflect padding. Model weights are unchanged by the reconstruction improvements.
 *
 * A stereo-linked, complementary amplitude mask keeps the mix phase and stereo image.
 * A smooth 55–150 Hz crossover protects the sub-bass without a hard frequency boundary.
 * Chunks overlap by 25% and use weighted overlap-add to reduce segment-edge artifacts.
 * Instrumental gain is linear and peak-limited across the whole track; WAV writes stream
 * samples to temporary files and only publish after both complete outputs are validated.
 *
 * Inference runs through TaisAiEngine for measured backend diagnostics and runtime CPU
 * fallback. This is separation, not speech recognition, so lyric availability is irrelevant.
 */
@Singleton
class TaisStemSeparator @Inject constructor(
    private val taisAiEngine: TaisAiEngine,
    @param:ApplicationContext private val context: Context
) {
    data class StemResult(val vocalsFile: File, val instrumentalFile: File)

    // separate() is one of the most memory-hungry things in this app (see class doc: a handful
    // of full-song float arrays, tens of MB each, alive at once). Two calls running concurrently
    // — e.g. the standalone "Run real AI separation" button and "Render Studio Master" both
    // tapped for the same or different songs — roughly doubles that peak and was a confirmed
    // OutOfMemoryError even with a 512MB largeHeap. Serializing every call through this mutex
    // means a second request just waits its turn instead of racing the first for memory.
    private val separationMutex = Mutex()

    /**
     * Decodes the audio at [sourceAudioPath] — a `content://` URI (preferred; PixelPlayer's own
     * playback path always uses this and it works under scoped storage with zero extra
     * permissions) or a plain filesystem path, any format MediaCodec can decode — separates it
     * into vocal and instrumental stems, and writes `<cacheDir>/<songId>_vocals.wav` /
     * `<cacheDir>/<songId>_instrumental.wav`. Always on-device — the higher-quality
     * BS-RoFormer cloud render is a separate, dedicated path (see
     * [com.theveloper.pixelplay.data.worker.BsRoformerRenderWorker] /
     * [BsRoformerApiClient]), not a fallback layered into this one.
     */
    /** [onChunkProgress] reports (chunksDone, totalChunks) as the STFT/model-inference loop advances — each chunk is ~5.9s of audio through the model, the slow part of separation. */
    suspend fun separate(
        songId: String,
        sourceAudioPath: String,
        cacheDir: File,
        onChunkProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): StemResult = taisAiEngine.runExclusive {
        separationMutex.withLock { separateLocked(songId, sourceAudioPath, cacheDir, onChunkProgress) }
    }

    private suspend fun separateLocked(
        songId: String,
        sourceAudioPath: String,
        cacheDir: File,
        onChunkProgress: suspend (Int, Int) -> Unit
    ): StemResult = withContext(Dispatchers.Default) {
        cacheDir.mkdirs()
        val vocalsFile = File(cacheDir, "${songId}_vocals.wav")
        val instrumentalFile = File(cacheDir, "${songId}_instrumental.wav")

        val pendingVocals = File.createTempFile("vocals_", ".part", cacheDir)
        val pendingInstrumental = File.createTempFile("instrumental_", ".part", cacheDir)
        try {
            separateOnDevice(songId, sourceAudioPath, pendingVocals, pendingInstrumental, onChunkProgress)
            currentCoroutineContext().ensureActive()
            check(TaisInstrumentalIndex.isCompleteStem(pendingVocals) && TaisInstrumentalIndex.isCompleteStem(pendingInstrumental)) {
                "Incomplete separation output; previous render was kept"
            }
            RetainedAudioFiles.publish(pendingVocals, vocalsFile)
            RetainedAudioFiles.publish(pendingInstrumental, instrumentalFile)
            StemResult(vocalsFile, instrumentalFile)
        } finally {
            pendingVocals.delete()
            pendingInstrumental.delete()
        }
    }

    private suspend fun separateOnDevice(
        songId: String,
        sourceAudioPath: String,
        vocalsFile: File,
        instrumentalFile: File,
        onChunkProgress: suspend (Int, Int) -> Unit
    ): StemResult {
            val model = taisAiEngine.prepareModel(STEM_SEPARATION_MODEL_ASSET)
            val expectedShape = intArrayOf(1, 4, FREQ_BINS, SEGMENT_FRAMES)
            require(model.inputShape.contentEquals(expectedShape) &&
                model.outputShape.contentEquals(expectedShape)) {
                "The separation model has an incompatible input/output shape"
            }

            val decoded = decodeToStereoFloat(sourceAudioPath)
            val resampled = resampleTo44100(decoded)

            // separateChannelPair allocates its two output arrays itself, right after the STFT/
            // model-inference loop finishes (not before) — on a several-minute song each is
            // 25-35MB, and the loop it would otherwise sit idle through for minutes is exactly
            // where memory is already tightest (padded input copies + OLA accumulator buffers +
            // a loaded TFLite interpreter all resident at once). See class doc for the
            // OutOfMemoryError this was fixed in response to.
            //
            // instrumentalLeft/Right here is `mask * mix` (soft-ratio-masked, mix's own phase —
            // see class doc / applySoftRatioMaskedSpectrum), not the model's raw complex output.
            val (instrumentalLeft, instrumentalRight) = separateChannelPair(resampled.left, resampled.right, onChunkProgress)

            // Level-match the instrumental within available peak headroom.
            val mixRms = rms(resampled.left, resampled.right)
            val instrumentalRms = rms(instrumentalLeft, instrumentalRight)
            val rmsMatchGain = if (instrumentalRms > 1e-6f) (mixRms / instrumentalRms) else 1f
            val flatMakeup = 10f.pow(INSTRUMENTAL_GAIN_DB / 20f)
            val instrumentalGain = StemAudioQuality.peakSafeGain(instrumentalLeft, instrumentalRight, rmsMatchGain * flatMakeup)

            // Stream a linear, peak-safe gain into the WAV. The previous tanh applied nonlinear
            // distortion to every sample and allocated two additional full-song arrays.
            writeStereoWav(instrumentalFile, instrumentalLeft, instrumentalRight, SAMPLE_RATE, instrumentalGain)

            // Vocals = mix - instrumental (pre-gain — the complementary mask, see class doc),
            // streamed sample-by-sample (see writeInstrumentalWav) instead of materializing
            // another two full-song arrays just to hold the residual.
            writeInstrumentalWav(vocalsFile, resampled.left, resampled.right, instrumentalLeft, instrumentalRight, 1f, SAMPLE_RATE)

            return StemResult(vocalsFile, instrumentalFile)
    }

    // ---------------------------------------------------------------------------------------
    // Decode: any container/codec -> interleaved stereo float PCM, via MediaExtractor/MediaCodec
    // (same primitives as utils/AudioDecoder.kt, but this needs the sample rate and channel
    // count AudioDecoder's fixed-sample-count API doesn't expose, and needs primitive-array
    // growth rather than AudioDecoder's boxed-list approach to stay memory-reasonable for a
    // full multi-minute track instead of a short fixed-length clip).
    // ---------------------------------------------------------------------------------------

    private data class DecodedAudio(val left: FloatArray, val right: FloatArray, val sampleRate: Int)

    private suspend fun decodeToStereoFloat(source: String): DecodedAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            openDataSource(context, extractor, source)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track found")
            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            var pcmFormat = sourceFormat
            var sampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(sourceFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(sourceFormat, null, null, 0)
            codec.start()
            val left = GrowableFloatArray(sampleRate * 60)
            val right = GrowableFloatArray(sampleRate * 60)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var lastProgressAt = android.os.SystemClock.elapsedRealtime()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastProgressAt = android.os.SystemClock.elapsedRealtime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        pcmFormat = codec.outputFormat
                        val actualRate = pcmFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        check(left.isEmpty || actualRate == sampleRate) { "Audio sample rate changed during decoding" }
                        sampleRate = actualRate
                        channels = pcmFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        lastProgressAt = android.os.SystemClock.elapsedRealtime()
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(index)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                appendPcm(buffer, pcmFormat, channels, left, right)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgressAt = android.os.SystemClock.elapsedRealtime()
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
                check(android.os.SystemClock.elapsedRealtime() - lastProgressAt < 30_000L) {
                    "Audio decoder stopped making progress"
                }
            }
            check(!left.isEmpty) { "The source contains no decodable audio" }
            return DecodedAudio(left.toFloatArray(), right.toFloatArray(), sampleRate)
        } finally {
            decoder?.let { codec ->
                runCatching { codec.stop() }
                codec.release()
            }
            extractor.release()
        }
    }

    private fun appendPcm(buffer: ByteBuffer, format: MediaFormat, channelCount: Int, left: GrowableFloatArray, right: GrowableFloatArray) {
        require(channelCount in 1..8) { "Unsupported audio channel layout: $channelCount" }
        val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            else -> error("Unsupported decoded PCM format: $encoding")
        }
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val frame = FloatArray(channelCount)
        while (buffer.remaining() >= bytesPerSample * channelCount) {
            for (channel in frame.indices) {
                frame[channel] = when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                    AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val bits = (buffer.get().toInt() and 0xff) or
                            ((buffer.get().toInt() and 0xff) shl 8) or (buffer.get().toInt() shl 16)
                        bits / 8388608f
                    }
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
                    else -> buffer.short / 32768f
                }
                check(frame[channel].isFinite()) { "Decoded audio contains non-finite samples" }
            }
            var l = frame[0]
            var r = frame.getOrElse(1) { l }
            // Consume an entire interleaved frame. Previously surround channels were treated
            // as extra stereo frames, stretching songs and sending invalid audio to the model.
            if (channelCount >= 3 && channelCount != 4) {
                l += frame[2] * 0.7071f
                r += frame[2] * 0.7071f
            }
            when (channelCount) {
                4 -> { l += frame[2] * 0.7071f; r += frame[3] * 0.7071f }
                5 -> { l += frame[3] * 0.7071f; r += frame[4] * 0.7071f }
                6, 7, 8 -> {
                    l += frame[3] * 0.5f + frame[4] * 0.7071f
                    r += frame[3] * 0.5f + frame[5] * 0.7071f
                    if (channelCount == 7) { l += frame[6] * 0.5f; r += frame[6] * 0.5f }
                    if (channelCount == 8) { l += frame[6] * 0.7071f; r += frame[7] * 0.7071f }
                }
            }
            // Leave reconstruction's peak-safe gain to handle headroom without clipping here.
            left.add(l)
            right.add(r)
        }
    }

    /** Cheap linear-interpolation resample — not audiophile-grade, adequate for this auxiliary path. */
    private fun resampleTo44100(decoded: DecodedAudio): DecodedAudio {
        if (decoded.sampleRate == SAMPLE_RATE) return decoded
        val ratio = SAMPLE_RATE.toDouble() / decoded.sampleRate
        val newLength = (decoded.left.size * ratio).toInt()
        val newLeft = FloatArray(newLength)
        val newRight = FloatArray(newLength)
        for (i in 0 until newLength) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt().coerceIn(0, decoded.left.size - 1)
            val i1 = (i0 + 1).coerceIn(0, decoded.left.size - 1)
            val frac = (srcPos - i0).toFloat()
            newLeft[i] = decoded.left[i0] * (1 - frac) + decoded.left[i1] * frac
            newRight[i] = decoded.right[i0] * (1 - frac) + decoded.right[i1] * frac
        }
        return DecodedAudio(newLeft, newRight, SAMPLE_RATE)
    }

    // ---------------------------------------------------------------------------------------
    // STFT -> model inference -> iSTFT, streamed one model-input chunk (256 frames, ~5.9s) at a
    // time and overlap-added directly into song-length accumulator buffers. Memory scales with
    // song duration once (a handful of sample-length float arrays), not with duration times the
    // frequency-bin count.
    // ---------------------------------------------------------------------------------------

    private suspend fun separateChannelPair(
        left: FloatArray,
        right: FloatArray,
        onChunkProgress: suspend (Int, Int) -> Unit
    ): Pair<FloatArray, FloatArray> {
        val window = hannWindowPeriodic(FFT_SIZE)
        val leftPadded = reflectPad(left, FFT_SIZE / 2)
        val rightPadded = reflectPad(right, FFT_SIZE / 2)
        val totalFrames = 1 + (leftPadded.size - FFT_SIZE) / HOP
        val paddedLength = leftPadded.size

        val accumLeft = FloatArray(paddedLength)
        val accumRight = FloatArray(paddedLength)
        val weightSum = FloatArray(paddedLength)

        val chunkStride = (SEGMENT_FRAMES * (1f - CHUNK_OVERLAP)).toInt().coerceAtLeast(1)
        val inputBuffer = ByteBuffer.allocateDirect(4 * 4 * FREQ_BINS * SEGMENT_FRAMES).order(ByteOrder.nativeOrder())
        val outputBuffer = ByteBuffer.allocateDirect(4 * 4 * FREQ_BINS * SEGMENT_FRAMES).order(ByteOrder.nativeOrder())
        val stereoMask = FloatArray(FREQ_BINS)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)

        var chunkStart = 0
        while (chunkStart < totalFrames) {
            currentCoroutineContext().ensureActive()
            val chunkLen = min(SEGMENT_FRAMES, totalFrames - chunkStart)

            inputBuffer.clear()
            writeChunkStft(inputBuffer, leftPadded, window, chunkStart, chunkLen, re, im, planeOffset = 0)
            writeChunkStft(inputBuffer, rightPadded, window, chunkStart, chunkLen, re, im, planeOffset = 2)
            inputBuffer.rewind()

            outputBuffer.clear()
            taisAiEngine.runInference(STEM_SEPARATION_MODEL_ASSET, inputBuffer, outputBuffer)
            outputBuffer.rewind()

            for (t in 0 until chunkLen) {
                val w = triangularWeight(t, chunkLen)
                val sampleStart = (chunkStart + t) * HOP

                applySoftRatioMaskedSpectrum(outputBuffer, inputBuffer, t, modelPlaneOffset = 0, re, im, stereoMask)
                Fft.transform(re, im, inverse = true)
                for (i in 0 until FFT_SIZE) {
                    accumLeft[sampleStart + i] += re[i] * window[i] * w
                }

                applySoftRatioMaskedSpectrum(outputBuffer, inputBuffer, t, modelPlaneOffset = 2, re, im, stereoMask)
                Fft.transform(re, im, inverse = true)
                for (i in 0 until FFT_SIZE) {
                    accumRight[sampleStart + i] += re[i] * window[i] * w
                }

                for (i in 0 until FFT_SIZE) {
                    weightSum[sampleStart + i] += window[i] * window[i] * w
                }
            }

            onChunkProgress((chunkStart + chunkLen).coerceAtMost(totalFrames), totalFrames)

            if (chunkStart + chunkLen >= totalFrames) break
            chunkStart += chunkStride
        }

        for (i in accumLeft.indices) {
            val wsum = weightSum[i].takeIf { it > 1e-8f } ?: 1f
            accumLeft[i] /= wsum
            accumRight[i] /= wsum
        }

        val trimStart = FFT_SIZE / 2
        val available = (accumLeft.size - trimStart).coerceAtLeast(0)
        val outLeft = FloatArray(left.size)
        val outRight = FloatArray(right.size)
        System.arraycopy(accumLeft, trimStart, outLeft, 0, min(outLeft.size, available))
        System.arraycopy(accumRight, trimStart, outRight, 0, min(outRight.size, available))
        return outLeft to outRight
    }

    /** Forward-STFTs [chunkLen] frames of [padded] starting at frame [chunkStart] straight into [buffer]'s NCHW planes. */
    private fun writeChunkStft(
        buffer: ByteBuffer,
        padded: FloatArray,
        window: FloatArray,
        chunkStart: Int,
        chunkLen: Int,
        re: FloatArray,
        im: FloatArray,
        planeOffset: Int
    ) {
        val planeBytes = FREQ_BINS * SEGMENT_FRAMES * 4
        val reBase = planeOffset * planeBytes
        val imBase = (planeOffset + 1) * planeBytes

        for (t in 0 until SEGMENT_FRAMES) {
            if (t < chunkLen) {
                val start = (chunkStart + t) * HOP
                for (i in 0 until FFT_SIZE) {
                    re[i] = padded[start + i] * window[i]
                    im[i] = 0f
                }
                Fft.transform(re, im, inverse = false)
            }
            for (f in 0 until FREQ_BINS) {
                val offset = f * SEGMENT_FRAMES + t
                buffer.putFloat(reBase + offset * 4, if (t < chunkLen) re[f] else 0f)
                buffer.putFloat(imBase + offset * 4, if (t < chunkLen) im[f] else 0f)
            }
        }
    }

    /**
     * Builds the masked instrumental spectrum for frame [t] directly (mirrored, ready to iFFT):
     * reads the model's raw prediction from [outputBuffer] and the original mix spectrum for the
     * exact same frame back out of [inputBuffer] (already computed once by [writeChunkStft] to
     * build the model's input — never recomputed), then applies the soft-ratio mask described in
     * the class doc. `re`/`im` are overwritten with `mask * mix`, not the raw model output.
     */
    private fun applySoftRatioMaskedSpectrum(
        outputBuffer: ByteBuffer,
        inputBuffer: ByteBuffer,
        t: Int,
        modelPlaneOffset: Int,
        re: FloatArray,
        im: FloatArray,
        stereoMask: FloatArray
    ) {
        val planeBytes = FREQ_BINS * SEGMENT_FRAMES * 4
        val reBase = modelPlaneOffset * planeBytes
        val imBase = (modelPlaneOffset + 1) * planeBytes

        for (f in 0 until FREQ_BINS) {
            val offset = f * SEGMENT_FRAMES + t
            val byteOffset = offset * 4
            val mixR = inputBuffer.getFloat(reBase + byteOffset)
            val mixI = inputBuffer.getFloat(imBase + byteOffset)

            // Link channel power before masking so a centered voice does not pull instruments
            // left/right as its level changes. Preserve each channel's original mix phase.
            if (modelPlaneOffset == 0) {
                var mixPower = 0f
                var vocalPower = 0f
                for (plane in 0 until 4) {
                    val mixed = inputBuffer.getFloat(plane * planeBytes + byteOffset)
                    val predicted = outputBuffer.getFloat(plane * planeBytes + byteOffset)
                    mixPower += mixed * mixed
                    vocalPower += predicted * predicted
                }
                stereoMask[f] = StemAudioQuality.linkedInstrumentalMask(mixPower, vocalPower, f * SAMPLE_RATE.toFloat() / FFT_SIZE)
            }
            val mask = stereoMask[f]

            val r = mixR * mask
            val i = mixI * mask
            re[f] = r
            im[f] = i
            if (f > 0) {
                re[FFT_SIZE - f] = r
                im[FFT_SIZE - f] = -i
            }
        }
        re[FREQ_BINS] = 0f // dropped Nyquist bin
        im[FREQ_BINS] = 0f
    }

    /** Single-pass stereo RMS over a whole song's samples — cheap (O(n), no extra allocation), used for the makeup-gain match in [separate]. */
    private fun rms(left: FloatArray, right: FloatArray): Float {
        var sumSq = 0.0
        for (i in left.indices) {
            sumSq += left[i].toDouble() * left[i] + right[i].toDouble() * right[i]
        }
        val meanSq = sumSq / (2.0 * left.size).coerceAtLeast(1.0)
        return kotlin.math.sqrt(meanSq).toFloat()
    }

    private fun triangularWeight(t: Int, chunkLen: Int): Float {
        if (chunkLen <= 1) return 1f
        val mid = (chunkLen - 1) / 2f
        return (1f - kotlin.math.abs(t - mid) / (mid + 1f)).coerceAtLeast(0.05f)
    }

    private fun hannWindowPeriodic(size: Int): FloatArray =
        FloatArray(size) { i -> (0.5 - 0.5 * cos(2.0 * Math.PI * i / size)).toFloat() }

    private fun reflectPad(x: FloatArray, pad: Int): FloatArray {
        val n = x.size
        val out = FloatArray(n + 2 * pad)
        for (i in 0 until pad) {
            out[i] = x[(pad - i).coerceIn(0, n - 1)]
        }
        System.arraycopy(x, 0, out, pad, n)
        for (i in 0 until pad) {
            out[pad + n + i] = x[(n - 2 - i).coerceIn(0, n - 1)]
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // WAV output — 16-bit PCM stereo, no external audio-encoding library needed for this.
    // ---------------------------------------------------------------------------------------

    private fun writeStereoWav(file: File, left: FloatArray, right: FloatArray, sampleRate: Int, gain: Float = 1f) {
        writeStereoWavHeaderAndData(file, left.size, sampleRate) { start, count, slice ->
            for (i in 0 until count) {
                slice.putShort(((left[start + i] * gain).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                slice.putShort(((right[start + i] * gain).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
            }
        }
    }

    /**
     * Writes `(mix - vocals) * gain` as it goes, one slice at a time — the instrumental is never
     * materialized as its own full-song array, just [mixLeft]/[mixRight] (already held by the
     * caller) minus [vocalsLeft]/[vocalsRight] (ditto), computed per-sample. Saves two more
     * full-song-length allocations on top of [writeStereoWav]'s (see [separate]'s doc on why peak
     * memory here is worth trimming every way that doesn't cost correctness).
     */
    private fun writeInstrumentalWav(
        file: File,
        mixLeft: FloatArray,
        mixRight: FloatArray,
        vocalsLeft: FloatArray,
        vocalsRight: FloatArray,
        gain: Float,
        sampleRate: Int
    ) {
        writeStereoWavHeaderAndData(file, mixLeft.size, sampleRate) { start, count, slice ->
            for (i in 0 until count) {
                val idx = start + i
                val left = ((mixLeft[idx] - vocalsLeft[idx]) * gain).coerceIn(-1f, 1f)
                val right = ((mixRight[idx] - vocalsRight[idx]) * gain).coerceIn(-1f, 1f)
                slice.putShort((left * Short.MAX_VALUE).toInt().toShort())
                slice.putShort((right * Short.MAX_VALUE).toInt().toShort())
            }
        }
    }

    private inline fun writeStereoWavHeaderAndData(
        file: File,
        frameCount: Int,
        sampleRate: Int,
        fillSlice: (start: Int, count: Int, slice: ByteBuffer) -> Unit
    ) {
        val dataSize = frameCount * 2 * 2 // stereo, 16-bit
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.writeBytes("RIFF")
            raf.writeIntLE(36 + dataSize)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLE(16)
            raf.writeShortLE(1) // PCM
            raf.writeShortLE(2) // stereo
            raf.writeIntLE(sampleRate)
            raf.writeIntLE(sampleRate * 2 * 2) // byte rate
            raf.writeShortLE(4) // block align
            raf.writeShortLE(16) // bits per sample
            raf.writeBytes("data")
            raf.writeIntLE(dataSize)

            // Written in ~1M-sample slices rather than one giant buffer, to avoid an extra
            // full-length allocation on top of the arrays already in memory.
            val sliceFrames = 1_000_000
            var start = 0
            while (start < frameCount) {
                val count = min(sliceFrames, frameCount - start)
                val slice = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                fillSlice(start, count, slice)
                raf.write(slice.array())
                start += count
            }
            raf.fd.sync()
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private companion object {
        const val TAG = "TaisStemSeparator"
        const val STEM_SEPARATION_MODEL_ASSET = "tais/stem_separation.tflite"
        const val SAMPLE_RATE = 44100
        const val FFT_SIZE = 6144
        const val FREQ_BINS = 3072 // dim_f — one-sided spectrum with the Nyquist bin dropped
        const val HOP = 1024
        const val SEGMENT_FRAMES = 256 // dim_t — ~5.9s per model-input chunk
        const val CHUNK_OVERLAP = 0.25f
        const val TIMEOUT_US = 10_000L
        const val INSTRUMENTAL_GAIN_DB = 1.5f
    }
}

/** Growable primitive float buffer — avoids the boxing cost of an ArrayList&lt;Float&gt; for a full track's samples. */
private class GrowableFloatArray(initialCapacity: Int) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(16))
    private var size = 0
    val isEmpty: Boolean get() = size == 0

    fun add(value: Float) {
        if (size == data.size) {
            data = data.copyOf(data.size * 2)
        }
        data[size++] = value
    }

    fun toFloatArray(): FloatArray = data.copyOf(size)
}

package com.theveloper.pixelplay.data.remix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.theveloper.pixelplay.data.remix.dsp.RemixStemBuffer
import com.theveloper.pixelplay.utils.WavHeader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import timber.log.Timber

/**
 * Loads a loop region into memory, mono, ready for [RemixGraph].
 *
 * Three sources, in the order the studio prefers them:
 *  1. **Separated stems** — `_stem_vocals.wav` and friends, written by the cloud separator. Raw
 *     16-bit PCM, so reading a region is `seek(44 + frame*4)` and a copy.
 *  2. **An existing instrumental** — `_instrumental.wav` / `_hq_roformer_inst.wav`. The vocal is
 *     recovered as `mix − instrumental`, which is sample-exact because that is precisely how the
 *     instrumental was made (`TaisStemSeparator.kt:540-558`).
 *  3. **Nothing at all** — split the track into mid and side. Works on every song, instantly,
 *     with no model, no network and no waiting. This is what makes the feature usable on first
 *     open rather than after a ten-minute GPU job.
 *
 * Everything returns **mono**: the binaural panner synthesizes the stereo image, so keeping the
 * source stereo would double the memory for a signal that is about to be collapsed.
 *
 * Region length is capped ([MAX_REGION_SECONDS]) because the whole point of residency is
 * sample-accurate random access with no disk I/O on the audio thread; a four-minute song at four
 * stems would be 339 MB.
 */
object RemixStemLoader {

    const val MAX_REGION_SECONDS = 30

    /** A stem pair derived from one source, already aligned frame-for-frame. */
    data class StemPair(
        val first: RemixStemBuffer,
        val second: RemixStemBuffer,
        val firstKind: String,
        val secondKind: String,
    )

    // ---------------------------------------------------------------- WAV

    private class WavInfo(
        val dataOffset: Long,
        val dataBytes: Long,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    ) {
        val bytesPerFrame: Int get() = channels * bitsPerSample / 8
        val frameCount: Int get() = (dataBytes / bytesPerFrame).toInt()
    }

    /**
     * Reads the header by walking chunks rather than assuming the canonical 44-byte layout:
     * files that have been through ffmpeg often carry a `LIST` chunk before `data`, and trusting
     * the fixed offset there yields a buffer full of metadata bytes interpreted as audio — loud
     * white noise, and a confusing bug.
     */
    private fun readWavInfo(raf: RandomAccessFile): WavInfo? {
        val header = ByteArray(12)
        raf.seek(0)
        if (raf.read(header) != 12) return null
        if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return null

        var sampleRate = 44_100
        var channels = 2
        var bits = 16
        var position = 12L
        val length = raf.length()
        val chunkHeader = ByteArray(8)

        while (position + 8 <= length) {
            raf.seek(position)
            if (raf.read(chunkHeader) != 8) return null
            val id = String(chunkHeader, 0, 4)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val body = position + 8
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(min(size, 16L).toInt())
                    raf.seek(body)
                    raf.read(fmt)
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    bb.short // audio format; PCM = 1
                    channels = bb.short.toInt().coerceIn(1, 8)
                    sampleRate = bb.int
                    bb.int // byte rate
                    bb.short // block align
                    bits = bb.short.toInt()
                }
                "data" -> return WavInfo(body, min(size, length - body), sampleRate, channels, bits)
            }
            position = body + size + (size and 1L) // chunks are word-aligned
        }
        return null
    }

    /**
     * Loads `[startFrame - guard, startFrame + regionFrames + guard)` as mono floats.
     *
     * The guard is not optional: the loop crossfade reads *behind* the region start and the
     * interpolator reads either side of its position. Out-of-file guard is zero-filled.
     */
    fun loadWavRegion(file: File, startFrame: Int, regionFrames: Int, guardFrames: Int): RemixStemBuffer? {
        if (!file.exists() || regionFrames <= 0) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val info = readWavInfo(raf) ?: return@use null
                if (info.bitsPerSample != 16) {
                    Timber.w("Remix loader: ${file.name} is ${info.bitsPerSample}-bit, expected 16")
                    return@use null
                }
                val total = regionFrames + 2 * guardFrames
                val out = FloatArray(total)
                val firstFrame = startFrame - guardFrames

                val readStart = firstFrame.coerceAtLeast(0)
                val readEnd = (firstFrame + total).coerceAtMost(info.frameCount)
                if (readEnd <= readStart) return@use null

                val frames = readEnd - readStart
                val bytes = ByteArray(frames * info.bytesPerFrame)
                raf.seek(info.dataOffset + readStart.toLong() * info.bytesPerFrame)
                raf.readFully(bytes)

                val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val channels = info.channels
                val offset = readStart - firstFrame
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) sum += shorts.get(i * channels + c) / 32768f
                    out[offset + i] = sum / channels
                }
                RemixStemBuffer(out, guardFrames, regionFrames, info.sampleRate)
            }
        }.onFailure { Timber.w(it, "Remix loader failed on ${file.name}") }.getOrNull()
    }

    /** `mix − instrumental`, sample-aligned. Both buffers must share geometry. */
    fun deriveVocals(mix: RemixStemBuffer, instrumental: RemixStemBuffer): RemixStemBuffer? {
        if (mix.samples.size != instrumental.samples.size) return null
        val out = FloatArray(mix.samples.size)
        for (i in out.indices) out[i] = mix.samples[i] - instrumental.samples[i]
        return RemixStemBuffer(out, mix.guardFrames, mix.regionFrames, mix.sampleRate)
    }

    // ------------------------------------------------------- encoded audio

    /**
     * Decodes a region of an encoded track (mp3, m4a, opus…) into **two** mono buffers: the mid
     * `(L+R)/2` and the side `(L−R)/2`.
     *
     * On most produced music the mid carries lead vocal, bass and kick, while the side carries
     * reverb, backing vocals and wide guitars — so dragging the two apart is immediately musical
     * even though it is nothing like true stem separation. It is the same decomposition
     * `MidSideVocalProcessor.kt:91-92` already uses for "magic instrumentalize".
     *
     * A mono source yields a silent side channel; callers should fall back to a single puck.
     */
    fun loadMidSideRegion(
        context: Context,
        uri: Uri,
        startMs: Int,
        regionMs: Int,
        guardFrames: Int,
    ): StemPair? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            val regionFrames = (regionMs / 1000f * sampleRate).toInt()
                .coerceIn(1, MAX_REGION_SECONDS * sampleRate)
            val total = regionFrames + 2 * guardFrames
            val mid = FloatArray(total)
            val side = FloatArray(total)

            // Seek to a sync sample at or before the guard start; decoded frames before the
            // region are exactly what the crossfade guard wants, so nothing is wasted.
            val guardMs = guardFrames * 1000L / sampleRate
            val seekUs = ((startMs - guardMs).coerceAtLeast(0L)) * 1000L
            extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val firstWantedUs = (startMs.toLong() - guardMs) * 1000L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var written = 0
            var sawOutput = false

            while (written < total) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        sawOutput = true
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = info.size / (2 * channels)
                        // Skip whatever the sync-point seek handed us before the guard start.
                        val chunkStartFrame = ((info.presentationTimeUs - firstWantedUs) * sampleRate / 1_000_000L).toInt()
                        for (f in 0 until frames) {
                            val destination = chunkStartFrame + f
                            if (destination < 0) continue
                            if (destination >= total) break
                            val left = shorts.get(f * channels) / 32768f
                            val right = if (channels > 1) shorts.get(f * channels + 1) / 32768f else left
                            mid[destination] = (left + right) * 0.5f
                            side[destination] = (left - right) * 0.5f
                            if (destination + 1 > written) written = destination + 1
                        }
                    }
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (endOfStream) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && sawOutput) {
                    break
                }
            }

            if (!sawOutput) return null
            StemPair(
                first = RemixStemBuffer(mid, guardFrames, regionFrames, sampleRate),
                second = RemixStemBuffer(side, guardFrames, regionFrames, sampleRate),
                firstKind = com.theveloper.pixelplay.data.remix.model.StemKind.CENTER,
                secondKind = com.theveloper.pixelplay.data.remix.model.StemKind.SIDES,
            )
        } catch (e: Exception) {
            Timber.w(e, "Remix loader: could not decode $uri")
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Writes the loop region as a canonical 16-bit stereo WAV, for upload to the separation
     * server.
     *
     * Sending the region rather than the whole track is what keeps a job cheap and inside the
     * payload limit: 30 seconds is ~5 MB of PCM, where a whole song would be both far more GPU
     * time and too large to post.
     */
    fun exportRegionWav(
        context: Context,
        uri: Uri,
        startMs: Int,
        durationMs: Int,
        target: File,
    ): File? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val frames = (durationMs / 1000f * sampleRate).toInt().coerceIn(1, MAX_REGION_SECONDS * sampleRate)

            extractor.seekTo(startMs.toLong() * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val pcm = ShortArray(frames * 2)
            val firstWantedUs = startMs.toLong() * 1000L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var sawOutput = false
            var highWater = 0

            while (highWater < frames) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        sawOutput = true
                        val shorts = codec.getOutputBuffer(outIndex)!!
                            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val chunkFrames = info.size / (2 * channels)
                        val chunkStart = ((info.presentationTimeUs - firstWantedUs) * sampleRate / 1_000_000L).toInt()
                        for (f in 0 until chunkFrames) {
                            val destination = chunkStart + f
                            if (destination < 0) continue
                            if (destination >= frames) break
                            val left = shorts.get(f * channels)
                            val right = if (channels > 1) shorts.get(f * channels + 1) else left
                            pcm[destination * 2] = left
                            pcm[destination * 2 + 1] = right
                            if (destination + 1 > highWater) highWater = destination + 1
                        }
                    }
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (endOfStream) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && sawOutput) {
                    break
                }
            }

            if (!sawOutput || highWater == 0) return null

            val dataBytes = highWater * 2 * 2
            target.parentFile?.mkdirs()
            target.outputStream().buffered().use { out ->
                out.write(WavHeader(36 + dataBytes, dataBytes, sampleRate, 16, 2).asByteArray())
                val bytes = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until highWater * 2) bytes.putShort(pcm[i])
                out.write(bytes.array())
            }
            target
        } catch (e: Exception) {
            Timber.w(e, "Remix loader: could not export region of $uri")
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private const val TIMEOUT_US = 5_000L
}

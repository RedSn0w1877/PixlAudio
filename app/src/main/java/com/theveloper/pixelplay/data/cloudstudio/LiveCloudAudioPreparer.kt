package com.theveloper.pixelplay.data.cloudstudio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.theveloper.pixelplay.data.tais.dsp.openDataSource
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Prepares a song for Cloud Studio (design §1 step 1, §7.1 `CloudAudioPreparer`), with the same routes as iOS:
 * - an AAC-LC song in an MP4 container with exactly one audio track, no video and a core rate of at least 32 kHz is
 *   uploaded as it is (copied byte for byte into the app's own folder);
 * - everything else (HE-AAC, muxed video, MP3, Opus, WAV, ALAC, FLAC, low rates, raw ADTS) is decoded with the
 *   platform decoder (`MediaExtractor` + `MediaCodec`, the path the on-device TAIS separator uses) and written as
 *   16-bit stereo FLAC of those samples at the song's own rate with [FlacEncoder];
 * - a streamed song's download, and the FLAC redo of a result that didn't line up, are always decoded ([prepare]'s
 *   `forceDecode`): the worker then sees exactly the samples this phone plays.
 *
 * The SHA-256, the duration and the frame count the import checks against are recorded. For an AAC upload the frame
 * count is the phone's own decode of the uploaded bytes with the encoder's priming and padding removed, as the
 * worker's ffmpeg removes them (the MP4 edit list; see [passthroughFrames]). Uploads live in `noBackupFilesDir`, so
 * Android's auto-backup never copies them, and are deleted once the job finishes. Everything here runs on IO.
 */
class LiveCloudAudioPreparer(private val context: Context) : CloudAudioPreparing {
    private val uploadsDir: File get() = File(context.noBackupFilesDir, "cloud_studio/uploads")

    override fun uploadFile(jobKey: String, ext: String): File? {
        if (!CloudKeys.isValidJobKey(jobKey) || ext !in CloudKeys.INPUT_EXTENSIONS) return null
        return File(uploadsDir, "$jobKey.$ext").takeIf { it.isFile }
    }

    override fun removeUpload(jobKey: String) {
        if (!CloudKeys.isValidJobKey(jobKey)) return
        uploadsDir.listFiles()?.filter { it.name.startsWith("$jobKey.") }?.forEach { it.delete() }
    }

    override suspend fun prepare(source: String, jobKey: String, forceDecode: Boolean): CloudPreparedAudio =
        withContext(Dispatchers.IO) {
            if (!CloudKeys.isValidJobKey(jobKey)) throw CloudPrepareException("Bad job key")
            uploadsDir.mkdirs()
            removeUpload(jobKey)
            val facts = inspect(source)
            if (!forceDecode && facts.route == Route.COPY) {
                passthrough(source, jobKey, facts)?.let { return@withContext it }
                // Not an MP4 after all, or this phone can't decode it back: decode it like everything else.
            }
            val target = File(uploadsDir, "$jobKey.flac")
            val (frames, sampleRate) = try {
                decodeToFlac(source, target)
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
            currentCoroutineContext().ensureActive()
            val (sha, bytes) = CloudDigest.file(target)
            CloudPreparedAudio(target, "flac", bytes, sha, frames * 1000 / sampleRate, frames, sampleRate,
                lowQualitySource = facts.lowQuality)
        }

    // ─── Decision ───────────────────────────────────────────────────────────────────────────

    enum class Route { COPY, DECODE }

    /** What the source is, and how it goes up. */
    private class SourceFacts(val route: Route, val lowQuality: Boolean)

    private fun inspect(source: String): SourceFacts {
        val extractor = MediaExtractor()
        try {
            try {
                openDataSource(context, extractor, source)
            } catch (error: Exception) {
                throw CloudPrepareException("The song's audio file couldn't be opened.")
            }
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val audio = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val videoCount = formats.count { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val first = audio.firstOrNull()
                ?: throw CloudPrepareException("This file has no audio track.", isDecodeFailure = true)
            val mime = first.getString(MediaFormat.KEY_MIME)
            val rate = runCatching { first.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull()
            val objectType = if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) aacObjectType(first) else null
            val heAac = objectType == AOT_SBR || objectType == AOT_PS
            val lowQuality = heAac || (rate != null && rate < MIN_PASSTHROUGH_SAMPLE_RATE) || videoCount > 0
            return SourceFacts(route(audio.size, videoCount, mime, objectType, rate), lowQuality)
        } finally {
            extractor.release()
        }
    }

    /** Copies an AAC-LC MP4 into the uploads folder and counts it; null when it has to be decoded after all. */
    private suspend fun passthrough(source: String, jobKey: String, facts: SourceFacts): CloudPreparedAudio? {
        val target = File(uploadsDir, "$jobKey.m4a")
        try {
            openStream(source).use { input -> target.outputStream().use { output -> input.copyTo(output, 1 shl 16) } }
            currentCoroutineContext().ensureActive()
            if (!isIsoMedia(target)) {
                target.delete()
                return null
            }
            // The phone's own decode of exactly the bytes being uploaded.
            var accessUnits = 0L
            var delay = 0L
            var padding = 0L
            var decoded = 0L
            val sampleRate = decodeLoop(
                target.absolutePath,
                onTrack = { format ->
                    delay = format.longOrZero(MediaFormat.KEY_ENCODER_DELAY)
                    padding = format.longOrZero(MediaFormat.KEY_ENCODER_PADDING)
                },
                onAccessUnit = { accessUnits++ },
            ) { _, _, frames, _, _ -> decoded += frames }
            val frames = passthroughFrames(decoded, accessUnits, delay, padding)
            if (frames <= 0 || sampleRate <= 0) {
                target.delete()
                return null
            }
            val (sha, bytes) = CloudDigest.file(target)
            return CloudPreparedAudio(target, "m4a", bytes, sha, frames * 1000 / sampleRate, frames, sampleRate,
                lowQualitySource = facts.lowQuality)
        } catch (error: CancellationException) {
            target.delete()
            throw error
        } catch (error: Exception) {
            target.delete()
            return null
        }
    }

    private fun openStream(source: String): InputStream {
        val uri = runCatching { Uri.parse(source) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        return if (uri != null && scheme != null && scheme != "file") {
            context.contentResolver.openInputStream(uri)
                ?: throw CloudPrepareException("The song's audio file couldn't be opened.")
        } else {
            FileInputStream(if (scheme == "file") uri?.path ?: source else source)
        }
    }

    /** An ISO base media file (MP4 / M4A): the first box is `ftyp`. Raw ADTS `.aac` is decoded instead, as on iOS. */
    private fun isIsoMedia(file: File): Boolean {
        val head = ByteArray(8)
        val read = file.inputStream().use { it.read(head) }
        return read == 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
    }

    // ─── Decoding ───────────────────────────────────────────────────────────────────────────

    /** Decodes [source] into [target]; returns (frames per channel, sample rate). */
    private suspend fun decodeToFlac(source: String, target: File): Pair<Long, Int> {
        var encoder: FlacEncoder? = null
        var scratch = ShortArray(0)
        try {
            val rate = decodeLoop(source) { buffer, encoding, _, channels, sampleRate ->
                val flac = encoder ?: FlacEncoder(target, sampleRate, 2).also { encoder = it }
                scratch = appendStereo(buffer, encoding, channels, scratch, flac)
            }
            val flac = encoder ?: throw CloudPrepareException("The song decoded to no audio.", isDecodeFailure = true)
            val frames = flac.finish()
            if (frames <= 0) throw CloudPrepareException("The song decoded to no audio.", isDecodeFailure = true)
            return frames to rate
        } finally {
            encoder?.close()
        }
    }

    /**
     * The decode loop both routes share: every decoded buffer goes to [sink] as (buffer, PCM encoding, frames,
     * channels, sample rate). Returns the output sample rate. MediaCodec's own failures (a corrupt frame, a codec that
     * gave up) become a decode failure; cancellation stays cancellation.
     */
    private suspend fun decodeLoop(
        source: String,
        onTrack: (MediaFormat) -> Unit = {},
        onAccessUnit: () -> Unit = {},
        sink: (ByteBuffer, Int, Int, Int, Int) -> Unit,
    ): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                openDataSource(context, extractor, source)
            } catch (error: Exception) {
                throw CloudPrepareException("The song's audio file couldn't be opened.")
            }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw CloudPrepareException("This file has no audio track.", isDecodeFailure = true)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            onTrack(format)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw CloudPrepareException("This file has no audio track.", isDecodeFailure = true)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var started = false
            val decoder = try {
                MediaCodec.createDecoderByType(mime).also { it.configure(format, null, null, 0); it.start() }
            } catch (error: Exception) {
                throw CloudPrepareException("This phone can't decode this song's audio ($mime).", isDecodeFailure = true)
            }
            codec = decoder
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex) ?: error("no input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            onAccessUnit()
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (started && rate != sampleRate) {
                            throw CloudPrepareException("The audio's sample rate changed mid-song.", isDecodeFailure = true)
                        }
                        sampleRate = rate
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(outIndex) ?: error("no output buffer")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytesPerSample = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                AudioFormat.ENCODING_PCM_16BIT -> 2
                                else -> throw CloudPrepareException(
                                    "The decoder produced an audio format this app can't read.", isDecodeFailure = true
                                )
                            }
                            val frames = info.size / (bytesPerSample * channels.coerceAtLeast(1))
                            started = true
                            sink(buffer.order(ByteOrder.LITTLE_ENDIAN), pcmEncoding, frames, channels, sampleRate)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (!started) throw CloudPrepareException("The song decoded to no audio.", isDecodeFailure = true)
            return sampleRate
        } catch (error: CloudPrepareException) {
            throw error
        } catch (error: CancellationException) {
            // A CancellationException is an IllegalStateException: never turn a stop into a decode failure.
            throw error
        } catch (error: IllegalStateException) {
            throw CloudPrepareException("The song's audio couldn't be decoded.", isDecodeFailure = true)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /**
     * One decoded buffer as interleaved stereo 16-bit frames into the encoder. Mono is doubled; with more than two
     * channels the first two (front left and right) are kept, as the worker's own `-ac 2` would mostly do.
     */
    private fun appendStereo(
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
        scratch: ShortArray,
        encoder: FlacEncoder,
    ): ShortArray {
        val channelCount = channels.coerceAtLeast(1)
        val frames: Int
        var out = scratch
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                frames = floats.remaining() / channelCount
                if (out.size < frames * 2) out = ShortArray(frames * 2)
                for (f in 0 until frames) {
                    val base = f * channelCount
                    val l = floats.get(base)
                    val r = if (channelCount > 1) floats.get(base + 1) else l
                    out[2 * f] = toPcm16(l)
                    out[2 * f + 1] = toPcm16(r)
                }
            }
            AudioFormat.ENCODING_PCM_16BIT -> {
                val shorts = buffer.asShortBuffer()
                frames = shorts.remaining() / channelCount
                if (out.size < frames * 2) out = ShortArray(frames * 2)
                for (f in 0 until frames) {
                    val base = f * channelCount
                    val l = shorts.get(base)
                    out[2 * f] = l
                    out[2 * f + 1] = if (channelCount > 1) shorts.get(base + 1) else l
                }
            }
            else -> throw CloudPrepareException("The decoder produced an audio format this app can't read.", isDecodeFailure = true)
        }
        if (frames > 0) encoder.write(out, frames)
        return out
    }

    private fun toPcm16(value: Float): Short {
        val scaled = (value * 32767f).let { if (it.isNaN()) 0f else it }
        return scaled.coerceIn(-32768f, 32767f).toInt().toShort()
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        /** AAC below this core rate may be HE-AAC with implicit SBR signalling, which decoders treat differently. */
        const val MIN_PASSTHROUGH_SAMPLE_RATE = 32_000
        /** One AAC-LC access unit, in frames. */
        const val AAC_FRAME = 1024L
        /** MPEG-4 audio object types (ISO 14496-3): LC, SBR (HE-AAC), PS (HE-AAC v2). */
        const val AOT_LC = 2
        const val AOT_SBR = 5
        const val AOT_PS = 29

        /** Copy or decode (pure, unit-tested): the iOS rule; the MP4 container is checked on the copied bytes. */
        fun route(audioTracks: Int, videoTracks: Int, mime: String?, aacObjectType: Int?, sampleRate: Int?): Route {
            if (audioTracks != 1 || videoTracks != 0) return Route.DECODE
            if (mime != "audio/mp4a-latm" || aacObjectType != AOT_LC) return Route.DECODE
            if (sampleRate == null || sampleRate < MIN_PASSTHROUGH_SAMPLE_RATE) return Route.DECODE
            return Route.COPY
        }

        /** The audio object type in an AAC track's AudioSpecificConfig (`csd-0`), or null when there is none. */
        fun aacObjectType(format: MediaFormat): Int? {
            val csd = runCatching { format.getByteBuffer("csd-0") }.getOrNull() ?: return null
            val bytes = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
            return aacObjectType(bytes)
        }

        /** The first 5 bits of an AudioSpecificConfig (31 escapes to 32 + the next 6 bits). */
        fun aacObjectType(config: ByteArray): Int? {
            if (config.isEmpty()) return null
            val first = (config[0].toInt() and 0xFF) ushr 3
            if (first != 31) return first
            if (config.size < 2) return null
            val next = ((config[0].toInt() and 0x07) shl 3) or ((config[1].toInt() and 0xFF) ushr 5)
            return 32 + next
        }

        /**
         * The uploaded AAC's length as the worker's ffmpeg decodes it (edit list honoured: priming and end padding
         * removed). [decoded] is what this phone's decoder produced. Whether a decoder trims the priming itself
         * differs between codecs: one that didn't produced about [accessUnits] × 1024 frames, so the container's
         * [delay] and [padding] come off; one that did is taken as it is. Without delay or padding (no edit list)
         * nothing is removed on either side.
         */
        fun passthroughFrames(decoded: Long, accessUnits: Long, delay: Long, padding: Long): Long {
            val cut = delay.coerceAtLeast(0) + padding.coerceAtLeast(0)
            if (cut == 0L || accessUnits <= 0) return decoded
            val raw = accessUnits * AAC_FRAME
            val trimmed = raw - cut
            return if (kotlin.math.abs(decoded - raw) <= kotlin.math.abs(decoded - trimmed)) decoded - cut else decoded
        }

        private fun MediaFormat.longOrZero(key: String): Long =
            if (containsKey(key)) runCatching { getInteger(key).toLong() }.getOrDefault(0L) else 0L
    }
}

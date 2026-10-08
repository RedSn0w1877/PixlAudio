package com.theveloper.pixelplay.data.cloudstudio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.theveloper.pixelplay.data.tais.dsp.openDataSource
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Prepares a song for Cloud Studio (design §1 step 1, §7.1 `CloudAudioPreparer`): decodes it with the platform
 * decoder (`MediaExtractor` + `MediaCodec`, the same path the on-device TAIS stem separator uses) and writes 16-bit
 * stereo FLAC of those samples at the song's own rate with [FlacEncoder]. Then records the SHA-256, the duration and
 * the frame count the import checks against later.
 *
 * Android always uploads FLAC, where iOS sends an AAC-LC `.m4a` as it is: the worker then decodes exactly the samples
 * this phone decoded, with no encoder priming for the two sides to disagree about (risk R13), at the cost of a
 * bigger upload for local AAC files (about 25–35 MB for 4 minutes). Streamed songs, already downloaded permanently by
 * the host, go up the same way, which is what the owner decision asks for. Uploads live in `noBackupFilesDir`, so
 * Android's auto-backup never copies them, and are deleted once the job finishes.
 */
class LiveCloudAudioPreparer(private val context: Context) : CloudAudioPreparing {
    private val uploadsDir: File get() = File(context.noBackupFilesDir, "cloud_studio/uploads")

    override fun uploadFile(jobKey: String, ext: String): File? = File(uploadsDir, "$jobKey.$ext").takeIf { it.isFile }

    override fun removeUpload(jobKey: String) {
        uploadsDir.listFiles()?.filter { it.name.startsWith("$jobKey.") }?.forEach { it.delete() }
    }

    override suspend fun prepare(source: String, jobKey: String, forceDecode: Boolean): CloudPreparedAudio =
        withContext(Dispatchers.Default) {
            uploadsDir.mkdirs()
            val target = File(uploadsDir, "$jobKey.flac")
            target.delete()
            val (frames, sampleRate) = try {
                decodeToFlac(source, target)
            } catch (error: Exception) {
                target.delete()
                throw error
            }
            val (sha, bytes) = CloudDigest.file(target)
            CloudPreparedAudio(target, "flac", bytes, sha, frames * 1000 / sampleRate, frames, sampleRate)
        }

    /** Decodes [source] into [target]; returns (frames per channel, sample rate). */
    private suspend fun decodeToFlac(source: String, target: File): Pair<Long, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var encoder: FlacEncoder? = null
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
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw CloudPrepareException("This file has no audio track.", isDecodeFailure = true)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val decoder = try {
                MediaCodec.createDecoderByType(mime).also { it.configure(format, null, null, 0); it.start() }
            } catch (error: Exception) {
                throw CloudPrepareException("This phone can't decode this song's audio ($mime).", isDecodeFailure = true)
            }
            codec = decoder
            val info = MediaCodec.BufferInfo()
            var scratch = ShortArray(0)
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
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (encoder != null && rate != sampleRate) {
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
                            val flac = encoder ?: FlacEncoder(target, sampleRate, 2).also { encoder = it }
                            scratch = appendStereo(buffer.order(ByteOrder.LITTLE_ENDIAN), pcmEncoding, channels, scratch, flac)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val flac = encoder ?: throw CloudPrepareException("The song decoded to no audio.", isDecodeFailure = true)
            val frames = flac.finish()
            if (frames <= 0) throw CloudPrepareException("The song decoded to no audio.", isDecodeFailure = true)
            return frames to sampleRate
        } catch (error: CloudPrepareException) {
            throw error
        } catch (error: CancellationException) {
            // A CancellationException is an IllegalStateException: never turn a stop into a decode failure.
            throw error
        } catch (error: IllegalStateException) {
            // MediaCodec's own failures (a corrupt frame, a codec that gave up) surface as IllegalStateException.
            throw CloudPrepareException("The song's audio couldn't be decoded.", isDecodeFailure = true)
        } finally {
            encoder?.close()
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
        buffer: java.nio.ByteBuffer,
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

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}

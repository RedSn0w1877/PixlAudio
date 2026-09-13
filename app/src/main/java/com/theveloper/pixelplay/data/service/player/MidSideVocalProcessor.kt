package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TAIS Engine 2's zero-latency DSP fallback ("Magic Instrumentalize" before/without AI stem
 * separation). Vocals are conventionally mixed dead-center, i.e. equal in the left and right
 * channels, so decomposing stereo audio into mid = (L+R)/2 and side = (L-R)/2 isolates most of
 * the center-panned content (vocals, but also bass/kick) into the mid channel — attenuating mid
 * and reconstructing L' = mid' + side, R' = mid' - side gives an instant, cheap vocal reduction
 * with none of the latency or model-loading cost of real source separation. It's a blunter tool
 * than a trained stem-separation model (it also dulls anything else mixed center), which is why
 * this is the *fallback* — [TaisStemSeparator]'s output should be preferred once available.
 *
 * Only engages for stereo PCM (16-bit or float); passes everything else through unmodified.
 */
@UnstableApi
class MidSideVocalProcessor(
    private val attenuationProvider: () -> Float
) : AudioProcessor {

    private companion object {
        private val NATIVE_ORDER = ByteOrder.nativeOrder()
    }

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var active = false
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // Separate from [outputBuffer] (the pending-result field getOutput() drains and clears) so
    // this one actually persists and grows across calls instead of being reallocated every time.
    private var workBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(NATIVE_ORDER)

    private fun ensureOutputBuffer(requiredCapacity: Int): ByteBuffer {
        if (workBuffer.capacity() < requiredCapacity) {
            workBuffer = ByteBuffer.allocateDirect(requiredCapacity).order(NATIVE_ORDER)
        } else {
            workBuffer.clear()
        }
        return workBuffer
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)

        inputFormat = inputAudioFormat
        active = supported
        return inputAudioFormat
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!active) return
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val attenuation = attenuationProvider().coerceIn(0f, 1f)
        if (attenuation <= 0f) {
            // Skip the per-sample loop when the slider is off, but still COPY into our own
            // buffer — aliasing the input buffer directly (e.g. via ByteBuffer.duplicate()) is
            // wrong here: the audio pipeline reuses/overwrites that buffer's backing memory as
            // soon as queueInput() returns, so by the time getOutput()'s caller (AudioTrack.write
            // via DefaultAudioSink) reads an aliased buffer, the memory it points at may already
            // hold different data or be gone entirely — this crashed with a native SIGSEGV in
            // AudioTrack.write's memcpy the first time this ran.
            val buffer = ensureOutputBuffer(remaining)
            buffer.put(inputBuffer.duplicate())
            buffer.flip()
            outputBuffer = buffer
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val midGain = 1f - attenuation
        val buffer = ensureOutputBuffer(remaining)
        val input = inputBuffer.duplicate().order(NATIVE_ORDER)

        if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val frameCount = remaining / (2 * Float.SIZE_BYTES)
            repeat(frameCount) {
                val l = input.float
                val r = input.float
                val mid = (l + r) * 0.5f * midGain
                val side = (l - r) * 0.5f
                buffer.putFloat((mid + side).coerceIn(-1f, 1f))
                buffer.putFloat((mid - side).coerceIn(-1f, 1f))
            }
        } else {
            val frameCount = remaining / (2 * Short.SIZE_BYTES)
            repeat(frameCount) {
                val l = input.short.toInt()
                val r = input.short.toInt()
                val mid = (l + r) * 0.5f * midGain
                val side = (l - r) * 0.5f
                buffer.putShort((mid + side).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                buffer.putShort((mid - side).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }

        buffer.flip()
        outputBuffer = buffer
        inputBuffer.position(inputBuffer.limit())
    }

    override fun getOutput(): ByteBuffer {
        val pending = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return pending
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() {
        inputEnded = true
    }

    @Deprecated("Media3 AudioProcessor now prefers flush(StreamMetadata); kept for interface compatibility")
    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        inputFormat = AudioFormat.NOT_SET
        active = false
    }
}

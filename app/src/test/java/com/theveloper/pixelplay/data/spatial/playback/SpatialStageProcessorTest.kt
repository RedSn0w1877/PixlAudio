package com.theveloper.pixelplay.data.spatial.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.theveloper.pixelplay.data.spatial.SpatialFixtures
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * The stage as Media3 runs it: 16-bit buffers, formats it leaves alone, and the per-item bypass of §6.3 (an item that
 * is already a spatial render is never spatialized twice; the next stream item is spatialized again).
 */
class SpatialStageProcessorTest {

    private val tables = SpatialPreviewTables.parse(SpatialFixtures.previewTable())

    private class FakeLink(override var state: SpatialStageState) : SpatialStageLink {
        val renders = mutableMapOf<String, SpatialRender>()
        val reports = mutableListOf<Triple<String?, SpatialRender?, Boolean>>()
        override fun renditionOf(uri: String?): SpatialRender? = uri?.let { renders[it] }
        override fun onStreamStarted(mediaId: String?, render: SpatialRender?, formatSupported: Boolean) {
            reports += Triple(mediaId, render, formatSupported)
        }
    }

    private val render = SpatialRender(
        videoId = "J7p4bzqLvCw",
        profile = SpatialProfile.STUDIO,
        file = File("sp-studio.flac"),
        sampleRate = 48_000,
        samples = 9_696_000,
        durationMs = 202_000,
        headroomDb = 4f,
    )

    private fun link(wet: Boolean = true) =
        FakeLink(SpatialStageState(tables, SpatialProfile.STUDIO, wet)).also { it.renders["file:///r.flac"] = render }

    private fun noise(frames: Int, seed: Int): ShortArray {
        val random = Random(seed)
        return ShortArray(frames * 2) { random.nextInt(-8_000, 8_000).toShort() }
    }

    private fun process(processor: SpatialStageProcessor, samples: ShortArray, chunkFrames: Int = 960): ShortArray {
        val out = ShortArray(samples.size)
        var done = 0
        while (done < samples.size) {
            val count = minOf(chunkFrames * 2, samples.size - done)
            val buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until count) buffer.putShort(samples[done + i])
            buffer.flip()
            processor.queueInput(buffer)
            assertFalse(buffer.hasRemaining(), "all input consumed")
            val output = processor.output
            assertEquals(count * 2, output.remaining())
            for (i in 0 until count) out[done + i] = output.short
            done += count
        }
        return out
    }

    private fun start(processor: SpatialStageProcessor, rate: Int = 48_000): AudioFormat {
        val format = processor.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        return format
    }

    @Test
    fun `stereo 44_1 and 48 kHz are taken, other formats are left out`() {
        val processor = SpatialStageProcessor(link())
        assertTrue(processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)) != AudioFormat.NOT_SET)
        assertTrue(processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT)) != AudioFormat.NOT_SET)
        assertEquals(AudioFormat.NOT_SET, processor.configure(AudioFormat(22_050, 2, C.ENCODING_PCM_16BIT)))
        assertEquals(AudioFormat.NOT_SET, processor.configure(AudioFormat(96_000, 2, C.ENCODING_PCM_16BIT)))
        assertEquals(AudioFormat.NOT_SET, processor.configure(AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT)))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertFalse(processor.isActive)
    }

    @Test
    fun `a render item is bypassed from its start and a stream item is spatialized again`() {
        val link = link()
        val processor = SpatialStageProcessor(link)
        start(processor)
        val delay = tables.preset(SpatialProfile.STUDIO, 48_000)!!.lowDelay

        // A stream item: spatialized.
        processor.onStreamStarting(newStream = true, identified = true, mediaId = "s1", uri = "spotify://abc")
        val streamIn = noise(9_600, seed = 1)
        val streamOut = process(processor, streamIn)
        assertTrue(differs(streamIn, streamOut, delay, from = 4_800), "the stream is spatialized")

        // A gapless boundary into a render: dry from its start (after a 10 ms ramp), the input delayed by lowDelay.
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.onStreamStarting(newStream = true, identified = true, mediaId = "r1", uri = "file:///r.flac")
        val renderIn = noise(9_600, seed = 2)
        val renderOut = process(processor, renderIn)
        for (frame in 960 until 9_600) {
            for (ch in 0..1) {
                assertEquals(renderIn[(frame - delay) * 2 + ch], renderOut[frame * 2 + ch], "render frame $frame")
            }
        }

        // The next stream item: spatialized again.
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.onStreamStarting(newStream = true, identified = true, mediaId = "s2", uri = "spotify://def")
        val againIn = noise(9_600, seed = 3)
        val againOut = process(processor, againIn)
        assertTrue(differs(againIn, againOut, delay, from = 4_800), "spatialized again")

        assertEquals(listOf("s1", "r1", "s2"), link.reports.map { it.first })
        assertEquals(listOf(null, render, null), link.reports.map { it.second })
    }

    @Test
    fun `a seek keeps the item's decision`() {
        val link = link()
        val processor = SpatialStageProcessor(link)
        start(processor)
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.onStreamStarting(newStream = true, identified = true, mediaId = "r1", uri = "file:///r.flac")
        // A seek: a flush with no configure before it, and an unknown item.
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val delay = tables.preset(SpatialProfile.STUDIO, 48_000)!!.lowDelay
        val input = noise(4_800, seed = 4)
        val output = process(processor, input)
        for (frame in delay until 4_800) {
            assertEquals(input[(frame - delay) * 2], output[frame * 2], "frame $frame")
        }
    }

    @Test
    fun `nothing selected passes 16-bit audio through unchanged`() {
        val link = FakeLink(SpatialStageState.OFF)
        val processor = SpatialStageProcessor(link)
        start(processor)
        val input = noise(4_000, seed = 5)
        val output = process(processor, input, chunkFrames = 1_024)
        assertTrue(input.contentEquals(output))
    }

    @Test
    fun `16-bit output saturates instead of wrapping`() {
        assertEquals(32_767, SpatialStageProcessor.toShort(1.5f))
        assertEquals(-32_768, SpatialStageProcessor.toShort(-1.5f))
        assertEquals(16_384, SpatialStageProcessor.toShort(0.5f))
    }

    private fun differs(input: ShortArray, output: ShortArray, delay: Int, from: Int): Boolean {
        var different = 0
        for (frame in from until input.size / 2) {
            if (output[frame * 2] != input[(frame - delay) * 2]) different++
        }
        return different > (input.size / 2 - from) / 2
    }
}

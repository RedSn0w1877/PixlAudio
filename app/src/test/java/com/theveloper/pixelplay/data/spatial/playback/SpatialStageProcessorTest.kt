package com.theveloper.pixelplay.data.spatial.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.theveloper.pixelplay.data.spatial.SpatialFixtures
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
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

        /** Answers for items with no URI (a MediaItem built on the JVM has none), in the order they are looked up. */
        val byLookup = ArrayDeque<SpatialRender?>()

        override fun renditionOf(uri: String?): SpatialRender? =
            if (uri == null && byLookup.isNotEmpty()) byLookup.removeFirst() else uri?.let { renders[it] }
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

    /** A new stream at [offsetUs] into its item, as `DefaultAudioSink` starts one: configure, then flush. */
    private fun begin(processor: SpatialStageProcessor, offsetUs: Long, rate: Int = 48_000) {
        processor.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata(offsetUs))
    }

    /** A tone per ear (440 and 660 Hz) at half scale: its own sample-to-sample steps are small, so a click stands out. */
    private fun tone(frames: Int, rate: Int = 48_000): ShortArray = ShortArray(frames * 2) {
        val hz = if (it % 2 == 0) 440.0 else 660.0
        (16_000 * sin(2 * PI * hz * (it / 2) / rate)).toInt().toShort()
    }

    /**
     * What the stage should play: a kernel sized for the whole table from the start, fed [input] in the processor's
     * blocks (feed the processor in [BLOCK]-frame buffers to compare), with [events] (a frame that starts a block ->
     * preset and wet) applied before the block that starts there.
     */
    private fun reference(
        input: ShortArray,
        start: Pair<SpatialPreset?, Boolean>,
        events: Map<Int, Pair<SpatialPreset?, Boolean>>,
        rate: Int = 48_000,
    ): ShortArray {
        val kernel = SpatialStageKernel()
        kernel.ensureCapacity(tables)
        kernel.setRampFrames(rate * SpatialStageProcessor.RAMP_MS / 1000)
        kernel.setTarget(start.first, start.second)
        kernel.reset()
        val inL = FloatArray(BLOCK); val inR = FloatArray(BLOCK); val outL = FloatArray(BLOCK); val outR = FloatArray(BLOCK)
        val out = ShortArray(input.size)
        val frames = input.size / 2
        var done = 0
        while (done < frames) {
            events[done]?.let { (preset, wet) -> kernel.setTarget(preset, wet) }
            val n = minOf(BLOCK, frames - done)
            for (i in 0 until n) {
                inL[i] = input[(done + i) * 2] * (1f / 32768f)
                inR[i] = input[(done + i) * 2 + 1] * (1f / 32768f)
            }
            kernel.process(inL, inR, outL, outR, n)
            for (i in 0 until n) {
                out[(done + i) * 2] = SpatialStageProcessor.toShort(outL[i]).toShort()
                out[(done + i) * 2 + 1] = SpatialStageProcessor.toShort(outR[i]).toShort()
            }
            done += n
        }
        return out
    }

    /** The largest step between two neighbouring samples of one ear, from frame [from] on. */
    private fun maxStep(samples: ShortArray, from: Int = 1): Int {
        var worst = 0
        for (frame in maxOf(from, 1) until samples.size / 2) {
            for (ch in 0..1) worst = maxOf(worst, abs(samples[frame * 2 + ch] - samples[(frame - 1) * 2 + ch]))
        }
        return worst
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
    fun `a profile change mid-song keeps the room the old profile is still fading out`() {
        // Studio's room needs 3,337 samples of history at 48 kHz, Concert Hall's 8,559: the switch must not trade the
        // ring Studio still reads (for its 80 ms fade) for an empty one.
        val rate = 48_000
        val studio = tables.preset(SpatialProfile.STUDIO, rate)!!
        val hall = tables.preset(SpatialProfile.HALL, rate)!!
        val link = FakeLink(SpatialStageState(tables, SpatialProfile.STUDIO, wet = true))
        val processor = SpatialStageProcessor(link)
        start(processor, rate)
        val input = tone(rate)
        val switchAt = 40 * BLOCK
        val before = process(processor, input.copyOfRange(0, switchAt * 2), chunkFrames = BLOCK)
        link.state = SpatialStageState(tables, SpatialProfile.HALL, wet = true)
        val after = process(processor, input.copyOfRange(switchAt * 2, input.size), chunkFrames = BLOCK)
        val output = before + after

        val expected = reference(input, start = studio to true, events = mapOf(switchAt to (hall to true)))
        val worst = maxStep(output, from = switchAt - BLOCK)
        val bound = maxStep(expected, from = switchAt - BLOCK)
        assertTrue(worst <= bound + 2, "a step of $worst where the signal's own largest is $bound: a click")
        assertArrayEquals(expected, output, "played as from rings sized for the whole table")
    }

    @Test
    fun `new playback parameters (a drain, then a flush where the audio carries on) keep the history`() {
        // DefaultAudioSink applies a speed change by draining the chain and flushing it at the position the next buffer
        // starts from, with no configure. Media3 trims the encoder delay and padding before this processor, so the
        // frames counted here can fall a few tens of milliseconds short of that position.
        val rate = 48_000
        val startUs = 30_000_000L
        val input = tone(rate)
        val half = 25 * 960
        val atUs = startUs + half * 1_000_000L / rate
        for (wet in listOf(true, false)) {
            val plain = SpatialStageProcessor(link(wet))
            begin(plain, startUs, rate)
            val expected = process(plain, input)
            for (offsetUs in listOf(atUs, atUs - 45_000L, atUs + 45_000L)) {
                val processor = SpatialStageProcessor(link(wet))
                begin(processor, startUs, rate)
                val first = process(processor, input.copyOfRange(0, half * 2))
                processor.queueEndOfStream()
                processor.flush(AudioProcessor.StreamMetadata(offsetUs))
                val second = process(processor, input.copyOfRange(half * 2, input.size))
                assertArrayEquals(expected, first + second, "wet $wet, flushed at $offsetUs us")
            }
        }
    }

    @Test
    fun `a seek, or a drain followed by a jump, still starts the history over`() {
        val rate = 48_000
        val startUs = 30_000_000L
        val input = tone(rate)
        val half = 25 * 960
        val atUs = startUs + half * 1_000_000L / rate
        val secondHalf = input.copyOfRange(half * 2, input.size)
        val fresh = SpatialStageProcessor(link())
        begin(fresh, atUs, rate)
        val expected = process(fresh, secondHalf)
        // A seek never drains, even to where the audio was; a drain that lands elsewhere is Media3's resync after a
        // jump (or the end of the song and a seek back).
        val cases = listOf(false to atUs, false to 0L, true to atUs + 300_000L, true to atUs - 300_000L, true to 0L)
        for ((drain, offsetUs) in cases) {
            val processor = SpatialStageProcessor(link())
            begin(processor, startUs, rate)
            process(processor, input.copyOfRange(0, half * 2))
            if (drain) processor.queueEndOfStream()
            processor.flush(AudioProcessor.StreamMetadata(offsetUs))
            assertArrayEquals(expected, process(processor, secondHalf), "drained $drain, flushed at $offsetUs us")
        }
    }

    @Test
    fun `a profile picked mid-song after a gapless boundary still fades in over 80 ms`() {
        // Spatial off, gapless into the next song at the same rate: the stage is idle at the boundary. The first
        // profile picked during that song must take the usual 80 ms ramp, not the boundary's 10 ms one.
        val rate = 48_000
        val studio = tables.preset(SpatialProfile.STUDIO, rate)!!
        val link = FakeLink(SpatialStageState.OFF)
        val processor = SpatialStageProcessor(link)
        begin(processor, 0L, rate)
        val firstSong = tone(rate / 4)
        assertArrayEquals(firstSong, process(processor, firstSong, chunkFrames = BLOCK))
        // The boundary as DefaultAudioSink runs it: configure (the next format), drain, flush with the next item.
        processor.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.queueEndOfStream()
        processor.flush(AudioProcessor.StreamMetadata(0L))
        val secondSong = tone(rate)
        val pickAt = 20 * BLOCK
        val before = process(processor, secondSong.copyOfRange(0, pickAt * 2), chunkFrames = BLOCK)
        link.state = SpatialStageState(tables, SpatialProfile.STUDIO, wet = true)
        val after = process(processor, secondSong.copyOfRange(pickAt * 2, secondSong.size), chunkFrames = BLOCK)

        val expected = reference(secondSong, start = null to false, events = mapOf(pickAt to (studio to true)))
        assertArrayEquals(expected, before + after)
    }

    @Test
    fun `an item boundary that needs a new AudioTrack decides for the new item`() {
        // A 48 kHz stream, then a 44.1 kHz render: the AudioTrack cannot be reused, so DefaultAudioSink drains, flushes
        // once with the OLD item's metadata (its own flush, before it takes the new configuration), then with the new
        // item's twice (the second after the new AudioTrack starts), with no configure between the three.
        val items = Items(listOf("s1", "r1"))
        val link = link().also { it.byLookup += listOf(null, null, render) }
        val processor = SpatialStageProcessor(link)
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(at(items, 0))
        process(processor, tone(4_800))
        processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        processor.queueEndOfStream()
        processor.flush(at(items, 0))
        processor.flush(at(items, 1))
        processor.flush(at(items, 1))

        assertEquals(listOf("s1", "s1", "r1"), link.reports.map { it.first })
        assertEquals(render, link.reports.last().second)
        // The render is never spatialized twice: dry from its first sample, the input delayed by lowDelay.
        val delay = tables.preset(SpatialProfile.STUDIO, 44_100)!!.lowDelay
        val input = tone(4_410, rate = 44_100)
        val output = process(processor, input)
        for (frame in 0 until 4_410) {
            for (ch in 0..1) {
                val expected = if (frame >= delay) input[(frame - delay) * 2 + ch] else 0.toShort()
                assertEquals(expected, output[frame * 2 + ch], "frame $frame")
            }
        }
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

    /** Items with one period each, whose uid is its index: enough for the stage to find an item by its period. */
    private class Items(ids: List<String>) : Timeline() {
        private val items = ids.map { MediaItem.Builder().setMediaId(it).build() }
        override fun getWindowCount(): Int = items.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(
                windowIndex, items[windowIndex], null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, true, false, null,
                0L, C.TIME_UNSET, windowIndex, windowIndex, 0L,
            )
        override fun getPeriodCount(): Int = items.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(periodIndex, periodIndex, periodIndex, C.TIME_UNSET, 0L)
        override fun getIndexOfPeriod(uid: Any): Int = if (uid is Int && uid in items.indices) uid else C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }

    /** The metadata of a flush that names item [index] of [timeline]. */
    private fun at(timeline: Timeline, index: Int, offsetUs: Long = 0L): AudioProcessor.StreamMetadata =
        AudioProcessor.StreamMetadata.Builder().setTimeline(timeline).setPeriodUid(index).setPositionOffsetUs(offsetUs).build()

    private companion object {
        /** The processor's block: buffers of this many frames reach the kernel unsplit. */
        const val BLOCK = SpatialStageKernel.DEFAULT_MAX_BLOCK
    }
}

package com.theveloper.pixelplay.data.spatial.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.theveloper.pixelplay.data.spatial.SpatialFixtures
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * A JVM microbenchmark of the on-device stage (cloud-audio-spec §7): nanoseconds per stereo frame for the heaviest
 * preset (Concert Hall, 48 kHz), the DSP alone and through the processor with 16-bit buffers. It prints the numbers;
 * the bound below only catches a stage that became an order of magnitude slower.
 *
 * The task's rule: above about 250 ns per frame on CI, switch the FIRs to partitioned FFT convolution.
 */
class SpatialStageBenchmarkTest {

    private val tables = SpatialPreviewTables.parse(SpatialFixtures.previewTable())
    private val rate = 48_000

    @Test
    fun `the stage costs well under a microsecond per frame`() {
        val kernelNs = kernelNsPerFrame()
        val processorNs = processorNsPerFrame()
        val report = "spatial stage, hall 48 kHz: kernel %.1f ns/frame, processor (16-bit) %.1f ns/frame"
            .format(kernelNs, processorNs)
        println(report)
        assertTrue(kernelNs < LIMIT_NS && processorNs < LIMIT_NS, report)
        if (REPORT_BY_FAILING) throw AssertionError("measurement only: $report")
    }

    private fun kernelNsPerFrame(): Double {
        val preset = tables.preset(SpatialProfile.HALL, rate)!!
        val kernel = SpatialStageKernel()
        kernel.ensureCapacity(tables)
        kernel.setTarget(preset, wet = true)
        kernel.reset()
        val random = Random(7)
        val block = kernel.maxBlock
        val inL = FloatArray(block) { random.nextFloat() - 0.5f }
        val inR = FloatArray(block) { random.nextFloat() - 0.5f }
        val outL = FloatArray(block)
        val outR = FloatArray(block)
        val blocksPerSecond = rate / block
        repeat(WARMUP_SECONDS * blocksPerSecond) { kernel.process(inL, inR, outL, outR, block) }
        var best = Double.MAX_VALUE
        repeat(ROUNDS) {
            val start = System.nanoTime()
            repeat(blocksPerSecond) { kernel.process(inL, inR, outL, outR, block) }
            val ns = (System.nanoTime() - start).toDouble() / (blocksPerSecond * block)
            best = minOf(best, ns)
        }
        return best
    }

    private fun processorNsPerFrame(): Double {
        val link = object : SpatialStageLink {
            override val state = SpatialStageState(tables, SpatialProfile.HALL, wet = true)
            override fun renditionOf(uri: String?): SpatialRender? = null
            override fun onStreamStarted(mediaId: String?, render: SpatialRender?, formatSupported: Boolean) = Unit
        }
        val processor = SpatialStageProcessor(link)
        processor.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val frames = 1_024
        val random = Random(8)
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { input.putShort(random.nextInt(-12_000, 12_000).toShort()) }
        val buffersPerSecond = rate / frames
        fun one() {
            input.rewind()
            processor.queueInput(input)
            processor.output
        }
        repeat(WARMUP_SECONDS * buffersPerSecond) { one() }
        var best = Double.MAX_VALUE
        repeat(ROUNDS) {
            val start = System.nanoTime()
            repeat(buffersPerSecond) { one() }
            best = minOf(best, (System.nanoTime() - start).toDouble() / (buffersPerSecond * frames))
        }
        return best
    }

    private companion object {
        const val WARMUP_SECONDS = 4
        const val ROUNDS = 8
        const val LIMIT_NS = 2_500.0

        /** Set while the numbers are read off CI (a passing test prints nothing there). */
        const val REPORT_BY_FAILING = true
    }
}

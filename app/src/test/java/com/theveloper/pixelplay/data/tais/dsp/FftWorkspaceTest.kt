package com.theveloper.pixelplay.data.tais.dsp

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FftWorkspaceTest {
    @Test fun `reused workspace matches direct complex DFT in both directions`() {
        for (size in listOf(1, 2, 6, 7, 15, 64)) {
            val workspace = Fft.Workspace(size)
            for (frame in 0..2) {
                for (inverse in listOf(false, true)) {
                    val re = signal(size, frame)
                    val im = signal(size, frame + 3)
                    val expected = directDft(re, im, inverse)
                    workspace.transform(re, im, inverse)
                    assertArrayEquals(expected.first, re, 0.0002f)
                    assertArrayEquals(expected.second, im, 0.0002f)
                }
            }
        }
    }

    @Test fun `production size reuse is bit exact against fresh scratch across frames`() {
        val size = 6144
        val workspace = Fft.Workspace(size)
        for (frame in 0..4) {
            for (inverse in listOf(false, true)) {
                val re = when (frame) {
                    1 -> FloatArray(size)
                    3 -> FloatArray(size).apply { this[size - 1] = 1f }
                    else -> signal(size, frame)
                }
                val im = if (frame == 1) FloatArray(size) else signal(size, frame + 5)
                val expectedRe = re.copyOf()
                val expectedIm = im.copyOf()
                Fft.transform(expectedRe, expectedIm, inverse)
                workspace.transform(re, im, inverse)
                assertBitsEqual(expectedRe, re)
                assertBitsEqual(expectedIm, im)
            }
        }
    }

    @Test fun `production size forward inverse roundtrip preserves stereo frame samples`() {
        val workspace = Fft.Workspace(6144)
        repeat(4) { frame ->
            val expectedRe = signal(6144, frame)
            val expectedIm = signal(6144, frame + 7)
            val re = expectedRe.copyOf()
            val im = expectedIm.copyOf()
            workspace.transform(re, im, inverse = false)
            workspace.transform(re, im, inverse = true)
            // The existing Float radix-2 convolution accumulates rounding over 16K points.
            assertArrayEquals(expectedRe, re, 0.004f)
            assertArrayEquals(expectedIm, im, 0.004f)
        }
    }

    @Test fun `silence after dense frame leaves no previous convolution data`() {
        val workspace = Fft.Workspace(6144)
        workspace.transform(signal(6144, 3), signal(6144, 4), inverse = false)
        for (inverse in listOf(false, true)) {
            val re = FloatArray(6144)
            val im = FloatArray(6144)
            workspace.transform(re, im, inverse)
            assertTrue(re.all { it == 0f })
            assertTrue(im.all { it == 0f })
        }
    }

    @Test fun `parallel renders have independent scratch and safely initialize plans`() {
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        try {
            val futures = listOf(6144, 93, 186, 6144).mapIndexed { worker, size ->
                executor.submit(Callable {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    val workspace = Fft.Workspace(size)
                    repeat(3) { frame ->
                        val re = signal(size, worker + frame)
                        val im = signal(size, worker + frame + 9)
                        val expectedRe = re.copyOf()
                        val expectedIm = im.copyOf()
                        Fft.transform(expectedRe, expectedIm, inverse = frame % 2 == 0)
                        workspace.transform(re, im, inverse = frame % 2 == 0)
                        assertBitsEqual(expectedRe, re)
                        assertBitsEqual(expectedIm, im)
                    }
                })
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `accidentally shared workspace serializes concurrent transforms`() {
        val workspace = Fft.Workspace(6144)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0..7).map { frame ->
                executor.submit(Callable {
                    val re = signal(6144, frame)
                    val im = signal(6144, frame + 2)
                    val expectedRe = re.copyOf()
                    val expectedIm = im.copyOf()
                    Fft.transform(expectedRe, expectedIm, inverse = frame % 2 == 0)
                    workspace.transform(re, im, inverse = frame % 2 == 0)
                    assertBitsEqual(expectedRe, re)
                    assertBitsEqual(expectedIm, im)
                })
            }
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `wrong workspace sizes fail before touching input`() {
        assertThrows(IllegalArgumentException::class.java) { Fft.Workspace(0) }
        val workspace = Fft.Workspace(6)
        val re = signal(6, 0)
        val im = signal(5, 1)
        val expectedRe = re.copyOf()
        val expectedIm = im.copyOf()
        assertThrows(IllegalArgumentException::class.java) {
            workspace.transform(re, im, inverse = true)
        }
        assertBitsEqual(expectedRe, re)
        assertBitsEqual(expectedIm, im)
    }

    private fun signal(size: Int, seed: Int) = FloatArray(size) { index ->
        (sin((index + 1) * (seed + 1) * 0.017) * 0.65 +
            cos((index + 3) * (seed + 2) * 0.031) * 0.2).toFloat()
    }

    private fun assertBitsEqual(expected: FloatArray, actual: FloatArray) {
        assertArrayEquals(
            IntArray(expected.size) { expected[it].toRawBits() },
            IntArray(actual.size) { actual[it].toRawBits() }
        )
    }

    /** Independent double-precision definition, deliberately not another FFT implementation. */
    private fun directDft(re: FloatArray, im: FloatArray, inverse: Boolean): Pair<FloatArray, FloatArray> {
        val size = re.size
        val outRe = FloatArray(size)
        val outIm = FloatArray(size)
        for (frequency in 0 until size) {
            var real = 0.0
            var imaginary = 0.0
            for (sample in 0 until size) {
                val angle = (if (inverse) 2.0 else -2.0) * Math.PI * frequency * sample / size
                real += re[sample] * cos(angle) - im[sample] * sin(angle)
                imaginary += re[sample] * sin(angle) + im[sample] * cos(angle)
            }
            outRe[frequency] = (real / if (inverse) size else 1).toFloat()
            outIm[frequency] = (imaginary / if (inverse) size else 1).toFloat()
        }
        return outRe to outIm
    }
}

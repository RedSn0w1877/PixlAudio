package com.theveloper.pixelplay.data.songtap.ring

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.songtap.ring.RingCodePayloads
import com.theveloper.pixelplay.songtap.ring.image.RenderParams
import com.theveloper.pixelplay.songtap.ring.image.RingCodeRenderer
import com.theveloper.pixelplay.songtap.ring.image.RingScanResult
import com.theveloper.pixelplay.songtap.ring.image.RingScanner
import java.nio.ByteBuffer
import org.junit.jupiter.api.Test

/**
 * The camera side of the ring scanner without a camera: a picture from the core's own renderer is laid out the way a phone's
 * YUV_420_888 planes are (padded luma rows, chroma at half size with a pixel stride of 2), turned back into a frame by
 * [RingFrameBuilder] and read by the real scanner.
 */
class RingFrameBuilderTest {
    private val video = "dQw4w9WgXcQ"

    private class Yuv(val y: ByteBuffer, val u: ByteBuffer, val v: ByteBuffer, val yStride: Int, val uvStride: Int, val uvPixel: Int)

    /** Plane buffers for [rendered]: luma with [pad] junk bytes at the end of every row, chroma half size (the start dot's colour where the render says "orange"). */
    private fun yuvOf(rendered: com.theveloper.pixelplay.songtap.ring.image.Rendered, pad: Int, uvPixel: Int): Yuv {
        val img = rendered.image
        val w = img.width
        val h = img.height
        val yStride = w + pad
        val y = ByteArray(yStride * h) { 0x77 }
        for (row in 0 until h) System.arraycopy(img.luma, row * w, y, row * yStride, w)
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val uvStride = cw * uvPixel + 6
        val u = ByteArray(uvStride * ch) { 0x7F }
        val v = ByteArray(uvStride * ch) { 0x7F }
        for (cy in 0 until ch) for (cx in 0 until cw) {
            val orange = (img.orange[(cy * 2).coerceAtMost(h - 1) * w + (cx * 2).coerceAtMost(w - 1)].toInt() and 0xFF) > 128
            u[cy * uvStride + cx * uvPixel] = (if (orange) 75 else 128).toByte()
            v[cy * uvStride + cx * uvPixel] = (if (orange) 205 else 128).toByte()
        }
        return Yuv(ByteBuffer.wrap(y), ByteBuffer.wrap(u), ByteBuffer.wrap(v), yStride, uvStride, uvPixel)
    }

    private fun scanVia(pad: Int, uvPixel: Int, rotation: Double): Pair<RingScanResult, ByteArray> {
        val bits = RingCodePayloads.encodeSlots(video, 12, 77)!!
        val rendered = RingCodeRenderer.render(bits, RenderParams(rotationRad = rotation))
        val yuv = yuvOf(rendered, pad, uvPixel)
        val frame = RingFrameBuilder().build(rendered.image.width, rendered.image.height, yuv.y, yuv.yStride, yuv.u, yuv.v, yuv.uvStride, yuv.uvPixel)
        return RingScanner().scan(frame) to bits
    }

    @Test
    fun `a picture laid out like a phone's planes, with padded rows and interleaved chroma, is read`() {
        val (result, _) = scanVia(pad = 24, uvPixel = 2, rotation = 0.7)
        assertThat(result).isInstanceOf(RingScanResult.Decoded::class.java)
        val payload = (result as RingScanResult.Decoded).payload
        assertThat(payload.videoId).isEqualTo(video)
        assertThat(payload.minuteBucket).isEqualTo(12)
        assertThat(payload.random).isEqualTo(77)
    }

    @Test
    fun `unpadded rows and separate chroma planes are read too`() {
        for (rotation in listOf(0.0, 2.1, 4.4)) {
            val (result, _) = scanVia(pad = 0, uvPixel = 1, rotation = rotation)
            assertThat(result).isInstanceOf(RingScanResult.Decoded::class.java)
        }
    }

    @Test
    fun `the hue map marks the start dot and almost nothing else`() {
        val bits = RingCodePayloads.encodeSlots(video, 3, 5)!!
        val rendered = RingCodeRenderer.render(bits, RenderParams())
        val yuv = yuvOf(rendered, 0, 2)
        val frame = RingFrameBuilder().build(rendered.image.width, rendered.image.height, yuv.y, yuv.yStride, yuv.u, yuv.v, yuv.uvStride, yuv.uvPixel)
        val hue = frame.hue!!
        var strong = 0
        for (i in 0 until frame.hueWidth * frame.hueHeight) if ((hue[i].toInt() and 0xFF) > 100) strong++
        // The start dot is 42 permille of 130 px = about 5 px across: a handful of chroma samples, not hundreds.
        assertThat(strong).isGreaterThan(0)
        assertThat(strong).isLessThan(60)
    }

    @Test
    fun `no chroma planes gives a frame without a hue map`() {
        val frame = RingFrameBuilder().build(64, 64, ByteBuffer.wrap(ByteArray(64 * 64)), 64, null, null, 0, 0)
        assertThat(frame.hue).isNull()
        assertThat(frame.luma.size).isAtLeast(64 * 64)
    }

    @Test
    fun `the buffers are reused from frame to frame and grow only when the picture does`() {
        val builder = RingFrameBuilder()
        val a = builder.build(128, 96, ByteBuffer.wrap(ByteArray(128 * 96)), 128, null, null, 0, 0)
        val b = builder.build(128, 96, ByteBuffer.wrap(ByteArray(128 * 96)), 128, null, null, 0, 0)
        assertThat(b.luma).isSameInstanceAs(a.luma)
        val c = builder.build(256, 192, ByteBuffer.wrap(ByteArray(256 * 192)), 256, null, null, 0, 0)
        assertThat(c.luma.size).isAtLeast(256 * 192)
    }

    @Test
    fun `a chroma plane shorter than its grid is read as not orange, never out of bounds`() {
        val w = 64
        val h = 64
        val frame = RingFrameBuilder().build(w, h, ByteBuffer.wrap(ByteArray(w * h)), w, ByteBuffer.wrap(ByteArray(10)), ByteBuffer.wrap(ByteArray(10)), 32, 1)
        assertThat(frame.hue).isNotNull()
    }

    // ── The pacer ────────────────────────────────────────────────────────────────────

    @Test
    fun `the pacer keeps a gap, waits longer after a slow scan, and calls a slow phone slow`() {
        val pacer = RingScanPacer(minGapMs = 100, load = 2, tooSlowMs = 450, window = 4)
        assertThat(pacer.shouldScan(0)).isTrue()
        pacer.recordScan(20_000, nowMs = 1_000) // 20 ms
        assertThat(pacer.shouldScan(1_050)).isFalse()
        assertThat(pacer.shouldScan(1_100)).isTrue()
        pacer.recordScan(200_000, nowMs = 2_000) // 200 ms: wait twice that
        assertThat(pacer.shouldScan(2_300)).isFalse()
        assertThat(pacer.shouldScan(2_400)).isTrue()
        assertThat(pacer.tooSlow()).isFalse()
        repeat(4) { pacer.recordScan(600_000, nowMs = 3_000L + it * 2_000) }
        assertThat(pacer.averageMs()).isEqualTo(600)
        assertThat(pacer.tooSlow()).isTrue()
        pacer.reset()
        assertThat(pacer.tooSlow()).isFalse()
        assertThat(pacer.shouldScan(0)).isTrue()
    }
}

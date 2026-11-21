package com.theveloper.pixelplay.data.songtap.ring

import com.theveloper.pixelplay.songtap.ring.image.RingFrame
import com.theveloper.pixelplay.songtap.ring.image.RingHue
import java.nio.ByteBuffer

/**
 * Turns a camera's YUV_420_888 planes into the scanner's [RingFrame]: the luma plane copied without its row padding,
 * and, from the U and V planes, the coarse "orangeness" map the scanner uses to tell the start dot from an ordinary dark
 * dot. Both arrays are reused from frame to frame (they grow only when the picture gets bigger), so a frame allocates only
 * the small [RingFrame] wrapper. One instance belongs to one analysis thread.
 *
 * No Android type here (plain [ByteBuffer]s) so it runs in unit tests.
 */
class RingFrameBuilder {
    private var luma = ByteArray(0)
    private var hue = ByteArray(0)

    /**
     * @param yRowStride bytes from one luma row to the next (at least [width]).
     * @param uvRowStride / [uvPixelStride] the chroma planes' row and sample spacing (a half-size grid: one sample per 2 x 2 pixels).
     * Pass a null [u] or [v] to leave the hue map out.
     */
    fun build(
        width: Int,
        height: Int,
        y: ByteBuffer,
        yRowStride: Int,
        u: ByteBuffer?,
        v: ByteBuffer?,
        uvRowStride: Int,
        uvPixelStride: Int,
    ): RingFrame {
        val need = width * height
        if (luma.size < need) luma = ByteArray(need)
        y.position(0)
        if (yRowStride == width && y.limit() - y.position() >= need) {
            y.get(luma, 0, need)
        } else {
            for (row in 0 until height) {
                y.position(row * yRowStride)
                y.get(luma, row * width, width)
            }
        }
        if (u == null || v == null) return RingFrame(width, height, luma)
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        if (hue.size < cw * ch) hue = ByteArray(cw * ch)
        val uLimit = u.limit()
        val vLimit = v.limit()
        for (cy in 0 until ch) {
            val rowBase = cy * uvRowStride
            val out = cy * cw
            for (cx in 0 until cw) {
                val at = rowBase + cx * uvPixelStride
                hue[out + cx] = if (at < uLimit && at < vLimit) {
                    RingHue.orangeness(u.get(at).toInt() and 0xFF, v.get(at).toInt() and 0xFF).toByte()
                } else {
                    0
                }
            }
        }
        return RingFrame(width, height, luma, hue, cw, ch)
    }
}

/**
 * How often the scanner may look, so it never starves the screen. It looks at most every [minGapMs]; when a scan takes long it waits
 * [load] times that long before the next (so the scanner uses about one part in [load] + 1 of the analysis thread); and when
 * the average scan is longer than [tooSlowMs] the phone cannot use the ring scanner usefully and the screen says so.
 */
class RingScanPacer(
    private val minGapMs: Long = 110L,
    private val load: Int = 2,
    private val tooSlowMs: Long = 450L,
    private val window: Int = 8,
) {
    private val recent = LongArray(window)
    private var count = 0
    private var next = 0
    private var nextAllowedMs = 0L

    fun shouldScan(nowMs: Long): Boolean = nowMs >= nextAllowedMs

    /** A scan that took [micros] has just finished at [nowMs]. */
    fun recordScan(micros: Long, nowMs: Long) {
        recent[next] = micros
        next = (next + 1) % window
        if (count < window) count++
        nextAllowedMs = nowMs + maxOf(minGapMs, micros / 1000L * load)
    }

    fun averageMs(): Long {
        if (count == 0) return 0L
        var sum = 0L
        for (i in 0 until count) sum += recent[i]
        return sum / count / 1000L
    }

    /** True after a full window of scans whose average is over the limit. */
    fun tooSlow(): Boolean = count >= window && averageMs() > tooSlowMs

    fun reset() {
        count = 0
        next = 0
        nextAllowedMs = 0L
    }
}

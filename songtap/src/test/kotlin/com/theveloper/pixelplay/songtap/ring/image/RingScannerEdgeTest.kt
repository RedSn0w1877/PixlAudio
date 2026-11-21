package com.theveloper.pixelplay.songtap.ring.image

import com.theveloper.pixelplay.songtap.SplitMix64
import com.theveloper.pixelplay.songtap.ring.RingCodeLayout
import com.theveloper.pixelplay.songtap.ring.RingCodePayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

private const val DEG2 = PI / 180.0

class RingScannerEdgeTest {
    private val scanner = RingScanner()

    // ── the renderer ────────────────────────────────────────────────────────────

    @Test
    fun `the renderer is deterministic`() {
        val c = ScanHarness.randomCase(SplitMix64(1))
        val p = RenderParams(rotationRad = 0.7, tiltRad = 0.3, noiseSigma = 6.0, noiseSeed = 9, blurSigmaPx = 0.8)
        val a = RingCodeRenderer.render(c.bits, p).image
        val b = RingCodeRenderer.render(c.bits, p).image
        assertTrue(a.luma.contentEquals(b.luma))
        assertTrue(a.orange.contentEquals(b.orange))
    }

    @Test
    fun `big dots carry four times the dark mass of small ones`() {
        val p = RenderParams(supersample = 3)
        val allBig = RingCodeRenderer.render(ByteArray(RingCodeLayout.SLOTS) { 1 }, p).image
        val allSmall = RingCodeRenderer.render(ByteArray(RingCodeLayout.SLOTS), p).image
        fun dark(img: LumaImage): Long {
            var n = 0L
            for (i in img.luma.indices) n += 255 - (img.luma[i].toInt() and 0xFF)
            return n
        }
        // the art disc, outline and start dot are the same in both, so compare what differs
        val diff = dark(allBig) - dark(allSmall)
        assertTrue(diff > 0)
        val plateLuma = p.plateLuma
        val dotLuma = p.dotLuma
        val perPixel = (plateLuma - dotLuma).toDouble()
        val scale = p.plateHalfPx / 1000.0
        val extraArea = RingCodeLayout.SLOTS * PI * (RingCodeLayout.BIG_RADIUS * RingCodeLayout.BIG_RADIUS - RingCodeLayout.SMALL_RADIUS * RingCodeLayout.SMALL_RADIUS) * scale * scale
        assertEquals(extraArea * perPixel, diff.toDouble(), extraArea * perPixel * 0.04)
    }

    @Test
    fun `the plate matrix puts the start dot where the geometry says`() {
        val c = ScanHarness.randomCase(SplitMix64(2))
        val p = RenderParams(rotationRad = 40 * DEG2, tiltRad = 20 * DEG2, tiltAxisRad = 70 * DEG2)
        val r = RingCodeRenderer.render(c.bits, p)
        val h = r.plateToImage
        val qx = 0.0
        val qy = -RingCodeLayout.MARKER_RADIUS_AT.toDouble()
        val w = h[6] * qx + h[7] * qy + h[8]
        val px = ((h[0] * qx + h[1] * qy + h[2]) / w).toInt()
        val py = ((h[3] * qx + h[4] * qy + h[5]) / w).toInt()
        assertTrue((r.image.orange[py * r.image.width + px].toInt() and 0xFF) > 200, "orange map at the start dot")
        assertEquals(
            RingCodeStyle.lumaOf(RingCodeLayout.MARKER_ARGB).toDouble(), p.markerLuma.toDouble(), 6.0,
            "the renderer's start dot luma is the one a camera would see",
        )
    }

    @Test
    fun `tilt squeezes the plate`() {
        val c = ScanHarness.randomCase(SplitMix64(3))
        fun width(tilt: Double): Int {
            val r = RingCodeRenderer.render(c.bits, RenderParams(tiltRad = tilt, tiltAxisRad = 0.0))
            // axis 0 tilts about the x axis: the y extent shrinks
            var lo = r.image.height
            var hi = 0
            for (y in 0 until r.image.height) for (x in 0 until r.image.width) {
                if ((r.image.luma[y * r.image.width + x].toInt() and 0xFF) > 220) { if (y < lo) lo = y; if (y > hi) hi = y }
            }
            return hi - lo
        }
        assertTrue(width(60 * DEG2) < width(0.0) * 0.75)
    }

    @Test
    fun `a smudge sized for a fraction covers about that many slots`() {
        val c = ScanHarness.randomCase(SplitMix64(4))
        val p = RenderParams()
        for (fraction in listOf(0.1, 0.2)) {
            val s = RingCodeRenderer.smudgeCovering(c.bits, p, 77, fraction, 200)
            var inside = 0
            val h = RingCodeRenderer.homography(p)
            for (slot in 0 until RingCodeLayout.SLOTS) {
                val ring = RingCodeLayout.ringOf(slot)
                val a = RingCodeLayout.angleMicroTurns(slot) / 1_000_000.0 * 2 * PI
                val qx = RingCodeLayout.RING_RADIUS[ring] * kotlin.math.sin(a)
                val qy = -RingCodeLayout.RING_RADIUS[ring] * kotlin.math.cos(a)
                val px = h[0] * qx + h[1] * qy + h[2]
                val py = h[3] * qx + h[4] * qy + h[5]
                if ((px - s.cx) * (px - s.cx) + (py - s.cy) * (py - s.cy) <= s.radius * s.radius) inside++
            }
            assertEquals(fraction * RingCodeLayout.SLOTS, inside.toDouble(), 4.0)
        }
    }

    // ── the scanner ─────────────────────────────────────────────────────────────

    @Test
    fun `a code on a device colour palette reads, dark or pale`() {
        val rng = SplitMix64(81)
        var good = 0
        var total = 0
        for (i in 0 until 40) {
            val device = (0xFF shl 24) or rng.nextInt(1 shl 24)
            val pal = RingCodeStyle.palette(device)
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(
                dotLuma = RingCodeStyle.lumaOf(pal.dot), plateLuma = RingCodeStyle.lumaOf(pal.plate), markerLuma = RingCodeStyle.lumaOf(pal.marker),
                rotationRad = rng.nextInt(360) * DEG2, tiltRad = rng.nextInt(25) * DEG2, tiltAxisRad = rng.nextInt(360) * DEG2, blurSigmaPx = 0.7, noiseSigma = 3.0, noiseSeed = rng.nextLong(),
            )
            total++
            if (ScanHarness.ok(ScanHarness.scan(scanner, c, p), c)) good++
        }
        println("device-colour palettes: $good / $total read")
        assertTrue(good >= total - 1, "$good / $total")
    }

    @Test
    fun `low contrast and a flat dim camera still read`() {
        val rng = SplitMix64(91)
        var good = 0
        var total = 0
        for (contrast in listOf(0.5, 0.35)) repeat(8) {
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(contrast = contrast, rotationRad = rng.nextInt(360) * DEG2, tiltRad = rng.nextInt(20) * DEG2, tiltAxisRad = rng.nextInt(360) * DEG2, noiseSigma = 3.0, noiseSeed = rng.nextLong(), blurSigmaPx = 0.6)
            total++
            if (ScanHarness.ok(ScanHarness.scan(scanner, c, p), c)) good++
        }
        println("low contrast (0.5 and 0.35 of full): $good / $total read")
        assertTrue(good >= total - 2, "$good / $total")
    }

    @Test
    fun `the start dot can be hidden and the code still reads`() {
        val rng = SplitMix64(101)
        var good = 0
        for (i in 0 until 24) {
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(marker = false, rotationRad = rng.nextInt(360) * DEG2, tiltRad = rng.nextInt(20) * DEG2, tiltAxisRad = rng.nextInt(360) * DEG2, blurSigmaPx = 0.6)
            val res = ScanHarness.scan(scanner, c, p, withHue = false)
            if (ScanHarness.ok(res, c)) good++
            assertFalse(res is RingScanResult.Decoded && !ScanHarness.ok(res, c), "never a wrong code")
        }
        println("no start dot: $good / 24 read (the eight-fold symmetry of the dot positions leaves eight turns to try)")
        assertTrue(good >= 20, "$good / 24")
    }

    @Test
    fun `the orange hue map is a hint, not a requirement`() {
        val rng = SplitMix64(111)
        repeat(12) {
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(rotationRad = rng.nextInt(360) * DEG2, tiltRad = rng.nextInt(20) * DEG2, tiltAxisRad = rng.nextInt(360) * DEG2, artMode = ArtMode.ORANGE_TEXTURE, artSeed = rng.nextInt(99))
            val withHue = ScanHarness.scan(scanner, c, p, withHue = true)
            val without = ScanHarness.scan(scanner, c, p, withHue = false)
            assertTrue(ScanHarness.ok(withHue, c), "with the hue map: ${ScanHarness.describe(withHue, scanner)}")
            assertTrue(ScanHarness.ok(without, c), "without it: ${ScanHarness.describe(without, scanner)}")
        }
    }

    @Test
    fun `chroma to orangeness`() {
        // the start dot (0xD9480F) is about U 75, V 205
        assertTrue(RingHue.orangeness(75, 205) > 200)
        assertEquals(0, RingHue.orangeness(128, 128))
        assertEquals(0, RingHue.orangeness(200, 100)) // blue
        assertEquals(0, RingHue.orangeness(60, 130)) // cyan-green
        assertEquals(0, RingHue.orangeness(128, 200)) // magenta-red, no U shift
    }

    @Test
    fun `diagnostics describe what was seen`() {
        val c = ScanHarness.randomCase(SplitMix64(121))
        val r = RingCodeRenderer.render(c.bits, RenderParams(rotationRad = 1.0, tiltRad = 0.3, noiseSigma = 5.0, blurSigmaPx = 0.8))
        var t = 0L
        val res = scanner.scan(ScanHarness.frameOf(r)) { t += 1_000_000L; t }
        assertTrue(res is RingScanResult.Decoded)
        val d = scanner.diagnostics
        assertTrue(d.found && d.markerFound)
        assertEquals(RingCodeLayout.SLOTS, d.slotConfidence.size)
        assertTrue(d.slotConfidence.count { it >= 60 } >= 180, "confident slots: ${d.slotConfidence.count { it >= 60 }}")
        assertEquals(RingCodeLayout.SLOTS, d.slotBits.size)
        for (s in 0 until RingCodeLayout.SLOTS) assertEquals(c.bits[s], d.slotBits[s], "slot $s")
        assertTrue(d.scanMicros > 0)
        assertEquals(1, d.scale)
        // the reported plate centre is the real one
        assertEquals(r.plateToImage[2], d.centreX, 2.0)
        assertEquals(r.plateToImage[5], d.centreY, 2.0)
    }

    @Test
    fun `a big frame is downscaled and still reads`() {
        val rng = SplitMix64(131)
        val c = ScanHarness.randomCase(rng)
        val p = RenderParams(width = 1280, height = 960, centerX = 600.0, centerY = 470.0, plateHalfPx = 380.0, rotationRad = 2.0, tiltRad = 0.35, tiltAxisRad = 1.0, blurSigmaPx = 2.0, noiseSigma = 4.0, noiseSeed = 5, clutter = true, supersample = 1)
        val r = RingCodeRenderer.render(c.bits, p)
        val res = scanner.scan(ScanHarness.frameOf(r))
        assertTrue(ScanHarness.ok(res, c), ScanHarness.describe(res, scanner))
        assertEquals(2, scanner.diagnostics.scale)
        assertEquals(p.centerX, scanner.diagnostics.centreX, 3.0)
    }

    @Test
    fun `scan time on this machine`() {
        val rng = SplitMix64(141)
        val c = ScanHarness.randomCase(rng)
        val small = RingCodeRenderer.render(c.bits, RenderParams(rotationRad = 1.2, tiltRad = 0.3, noiseSigma = 4.0))
        val big = RingCodeRenderer.render(c.bits, RenderParams(width = 1280, height = 720, centerX = 640.0, centerY = 360.0, plateHalfPx = 330.0, rotationRad = 1.2, tiltRad = 0.3, noiseSigma = 4.0, supersample = 1))
        fun time(img: Rendered): Pair<Long, Long> {
            val frame = ScanHarness.frameOf(img)
            repeat(10) { scanner.scan(frame) }
            val times = LongArray(40)
            for (i in times.indices) {
                val t0 = System.nanoTime()
                scanner.scan(frame)
                times[i] = (System.nanoTime() - t0) / 1000
            }
            times.sort()
            return times[times.size / 2] to times.last()
        }
        val (m1, w1) = time(small)
        val (m2, w2) = time(big)
        println("scan time (JVM, warm, one core): 360x360 median $m1 us worst $w1 us; 1280x720 median $m2 us worst $w2 us")
        assertTrue(m1 < 200_000 && m2 < 400_000, "scan far too slow: $m1 us and $m2 us")
    }

    // ── the accumulator ─────────────────────────────────────────────────────────

    private fun decoded(id: String, random: Int = 7): RingScanResult = RingScanResult.Decoded(RingCodePayload(id, 5, random), 0, 0)
    private fun failed(): RingScanResult = RingScanResult.Failed(RingScanFailure.UNREADABLE)

    @Test
    fun `three consistent decodes in a row accept`() {
        val acc = RingScanAccumulator()
        assertNull(acc.offer(decoded("dQw4w9WgXcQ"), 0))
        assertNull(acc.offer(decoded("dQw4w9WgXcQ"), 100))
        val accepted = acc.offer(decoded("dQw4w9WgXcQ"), 200)
        assertNotNull(accepted)
        assertEquals("dQw4w9WgXcQ", accepted!!.videoId)
    }

    @Test
    fun `a different decode in between starts again`() {
        val acc = RingScanAccumulator()
        acc.offer(decoded("dQw4w9WgXcQ"), 0)
        acc.offer(decoded("dQw4w9WgXcQ"), 100)
        assertNull(acc.offer(decoded("aaaaaaaaaaa"), 200)) // another code: the run restarts at one
        assertNull(acc.offer(decoded("dQw4w9WgXcQ"), 300))
        assertNull(acc.offer(decoded("dQw4w9WgXcQ"), 400))
        assertNotNull(acc.offer(decoded("dQw4w9WgXcQ"), 500))
        // the same video with another tag is another code too
        val acc2 = RingScanAccumulator()
        acc2.offer(decoded("dQw4w9WgXcQ", 1), 0)
        acc2.offer(decoded("dQw4w9WgXcQ", 1), 10)
        assertNull(acc2.offer(decoded("dQw4w9WgXcQ", 2), 20))
    }

    @Test
    fun `a failed frame neither counts nor breaks the run, but a long gap does`() {
        val acc = RingScanAccumulator()
        acc.offer(decoded("dQw4w9WgXcQ"), 0)
        assertNull(acc.offer(failed(), 100))
        assertNull(acc.offer(decoded("dQw4w9WgXcQ"), 200))
        assertNotNull(acc.offer(decoded("dQw4w9WgXcQ"), 300))
        val stale = RingScanAccumulator()
        stale.offer(decoded("dQw4w9WgXcQ"), 0)
        stale.offer(decoded("dQw4w9WgXcQ"), 100)
        assertNull(stale.offer(failed(), 5_000))
        assertNull(stale.offer(decoded("dQw4w9WgXcQ"), 5_100)) // the old run is gone: this is the first again
        assertNull(stale.offer(decoded("dQw4w9WgXcQ"), 5_200))
        assertNotNull(stale.offer(decoded("dQw4w9WgXcQ"), 5_300))
    }

    @Test
    fun `reset forgets the run`() {
        val acc = RingScanAccumulator()
        acc.offer(decoded("dQw4w9WgXcQ"), 0)
        acc.offer(decoded("dQw4w9WgXcQ"), 10)
        acc.reset()
        assertNull(acc.offer(decoded("dQw4w9WgXcQ"), 20))
    }

    @Test
    fun `three real frames of a moving camera accept`() {
        val rng = SplitMix64(151)
        val c = ScanHarness.randomCase(rng)
        val acc = RingScanAccumulator()
        var accepted: RingCodePayload? = null
        for (i in 0 until 5) {
            val p = RenderParams(rotationRad = (20 + i * 9) * DEG2, tiltRad = (8 + i) * DEG2, tiltAxisRad = 1.0, centerX = 175.0 + i * 3, centerY = 183.0 - i * 2, noiseSigma = 5.0, noiseSeed = i.toLong(), blurSigmaPx = 0.7)
            val res = ScanHarness.scan(scanner, c, p)
            accepted = acc.offer(res, i * 66L) ?: accepted
        }
        assertEquals(c.payload, accepted)
    }
}

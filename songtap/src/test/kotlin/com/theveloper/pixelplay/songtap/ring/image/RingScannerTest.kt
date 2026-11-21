package com.theveloper.pixelplay.songtap.ring.image

import com.theveloper.pixelplay.songtap.SplitMix64
import com.theveloper.pixelplay.songtap.ring.RingCodePayload
import com.theveloper.pixelplay.songtap.ring.image.ScanHarness.describe
import com.theveloper.pixelplay.songtap.ring.image.ScanHarness.ok
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

private const val DEG = PI / 180.0

/** Counts how many scenes of a sweep were read, remembers the first failures, and prints one line per sweep. */
private class Sweep(val name: String) {
    var total = 0
    var good = 0
    var wrong = 0
    val failures = ArrayList<String>()

    fun record(result: RingScanResult, c: ScanHarness.Case, label: String, scanner: RingScanner) {
        total++
        if (ok(result, c)) good++ else {
            if (result is RingScanResult.Decoded) wrong++
            if (failures.size < 6) failures += "$label: ${describe(result, scanner)}"
        }
    }

    fun line(): String = "$name: $good / $total read (${"%.1f".format(100.0 * good / total)} percent), $wrong wrong"
}

class RingScannerBasicTest {
    private val scanner = RingScanner()

    @Test
    fun `a clean upright code reads`() {
        val c = ScanHarness.randomCase(SplitMix64(11))
        val r = RingCodeRenderer.render(c.bits, RenderParams())
        ScanHarness.savePng("clean", r.image)
        val res = scanner.scan(ScanHarness.frameOf(r))
        assertTrue(ok(res, c), describe(res, scanner))
        val d = scanner.diagnostics
        assertTrue(d.markerFound && d.found)
        assertTrue(d.matched >= 190, "matched ${d.matched}")
        assertTrue(d.residualPx < 0.6, "residual ${d.residualPx}")
    }

    @Test
    fun `rotation 0 to 360 degrees`() {
        val rng = SplitMix64(21)
        val sweep = Sweep("rotation every 3 degrees")
        var deg = 0
        while (deg < 360) {
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(rotationRad = deg * DEG)
            sweep.record(ScanHarness.scan(scanner, c, p), c, "rot $deg", scanner)
            deg += 3
        }
        println(sweep.line()); sweep.failures.forEach { println("  $it") }
        assertTrue(sweep.good == sweep.total, sweep.line() + "\n" + sweep.failures.joinToString("\n"))
    }

    @Test
    fun `tilt up to 30 degrees about every axis`() {
        val rng = SplitMix64(31)
        val sweep = Sweep("tilt 0..30 degrees, 8 axes, random rotation")
        for (tilt in listOf(5, 10, 15, 20, 25, 30)) for (axis in 0 until 360 step 45) {
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(rotationRad = rng.nextInt(360) * DEG, tiltRad = tilt * DEG, tiltAxisRad = axis * DEG)
            sweep.record(ScanHarness.scan(scanner, c, p), c, "tilt $tilt axis $axis", scanner)
        }
        println(sweep.line()); sweep.failures.forEach { println("  $it") }
        assertTrue(sweep.good >= sweep.total - 2, sweep.line() + "\n" + sweep.failures.joinToString("\n"))
    }

    @Test
    fun `blur and noise`() {
        val rng = SplitMix64(41)
        // The plate is 260 px across here: a dot is 4 to 8 px wide, the outline 3 px. Blur of 2 px is far past what a
        // focused phone camera gives at this size (reported, not asserted); the large-plate test below goes further.
        val blur = Sweep("blur 0.5..1.5 px at 260 px plate")
        for (sigma in listOf(0.5, 1.0, 1.5)) repeat(12) { i ->
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(rotationRad = rng.nextInt(360) * DEG, tiltRad = rng.nextInt(20) * DEG, tiltAxisRad = rng.nextInt(360) * DEG, blurSigmaPx = sigma)
            blur.record(ScanHarness.scan(scanner, c, p), c, "blur $sigma #$i", scanner)
        }
        println(blur.line()); blur.failures.forEach { println("  $it") }
        val heavy = Sweep("blur 2.0 px at 260 px plate (past the limit)")
        repeat(10) { i ->
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(rotationRad = rng.nextInt(360) * DEG, blurSigmaPx = 2.0)
            heavy.record(ScanHarness.scan(scanner, c, p), c, "blur 2.0 #$i", scanner)
        }
        println(heavy.line())
        val noise = Sweep("noise sigma 4..16 levels")
        for (sigma in listOf(4.0, 8.0, 12.0, 16.0)) repeat(10) { i ->
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(rotationRad = rng.nextInt(360) * DEG, tiltRad = rng.nextInt(20) * DEG, tiltAxisRad = rng.nextInt(360) * DEG, noiseSigma = sigma, noiseSeed = rng.nextLong(), blurSigmaPx = 0.7)
            noise.record(ScanHarness.scan(scanner, c, p), c, "noise $sigma #$i", scanner)
        }
        println(noise.line()); noise.failures.forEach { println("  $it") }
        assertTrue(blur.good >= blur.total - 1, blur.line() + "\n" + blur.failures.joinToString("\n"))
        assertTrue(noise.good >= noise.total - 4, noise.line())
    }

    @Test
    fun `a larger plate takes proportionally more blur`() {
        // 640 x 640 frame, plate 520 px across: the same relative blur as above, twice the pixels. A real frame is bigger still.
        val rng = SplitMix64(43)
        val sweep = Sweep("blur 1..3 px at 520 px plate")
        for (sigma in listOf(1.0, 2.0, 3.0)) repeat(8) { i ->
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(width = 640, height = 640, plateHalfPx = 260.0, rotationRad = rng.nextInt(360) * DEG, tiltRad = rng.nextInt(20) * DEG, tiltAxisRad = rng.nextInt(360) * DEG, blurSigmaPx = sigma)
            sweep.record(ScanHarness.scan(scanner, c, p), c, "big blur $sigma #$i", scanner)
        }
        println(sweep.line()); sweep.failures.forEach { println("  $it") }
        assertTrue(sweep.good >= sweep.total - 1, sweep.line() + "\n" + sweep.failures.joinToString("\n"))
        // the cliff: past about 1.5 percent of the plate's half-size the thin outline ring melts away and no plate is found
        val past = Sweep("blur 4 px at 520 px plate (past the limit)")
        repeat(6) { i ->
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(width = 640, height = 640, plateHalfPx = 260.0, rotationRad = rng.nextInt(360) * DEG, blurSigmaPx = 4.0)
            past.record(ScanHarness.scan(scanner, c, p), c, "past $i", scanner)
        }
        println(past.line())
    }

    @Test
    fun `smudges over up to 20 percent of the slots`() {
        val rng = SplitMix64(51)
        for (fraction in listOf(0.10, 0.15, 0.20)) {
            val sweep = Sweep("smudge ${(fraction * 100).toInt()} percent of the slots")
            repeat(40) { i ->
                val c = ScanHarness.randomCase(rng)
                val base = RenderParams(rotationRad = rng.nextInt(360) * DEG, tiltRad = rng.nextInt(20) * DEG, tiltAxisRad = rng.nextInt(360) * DEG, blurSigmaPx = 0.6)
                val smudge = RingCodeRenderer.smudgeCovering(c.bits, base, rng.nextInt(200), fraction, luma = if (i % 2 == 0) 205 else 135)
                val p = RenderParams(
                    rotationRad = base.rotationRad, tiltRad = base.tiltRad, tiltAxisRad = base.tiltAxisRad, blurSigmaPx = 0.6, smudges = listOf(smudge),
                )
                sweep.record(ScanHarness.scan(scanner, c, p), c, "smudge #$i", scanner)
            }
            println(sweep.line()); sweep.failures.forEach { println("  $it") }
            assertTrue(sweep.good >= sweep.total - 2, sweep.line() + "\n" + sweep.failures.joinToString("\n"))
        }
    }

    @Test
    fun `glare patches`() {
        val rng = SplitMix64(61)
        val sweep = Sweep("one to two glare patches, 25..55 px")
        repeat(60) { i ->
            val c = ScanHarness.randomCase(rng)
            val glares = ArrayList<Glare>()
            repeat(1 + rng.nextInt(2)) {
                glares += Glare(110.0 + rng.nextInt(140), 110.0 + rng.nextInt(140), 25.0 + rng.nextInt(30), 15.0 + rng.nextInt(30), rng.nextInt(180) * DEG, 200.0 + rng.nextInt(100))
            }
            val p = RenderParams(rotationRad = rng.nextInt(360) * DEG, tiltRad = rng.nextInt(20) * DEG, tiltAxisRad = rng.nextInt(360) * DEG, blurSigmaPx = 0.6, glares = glares)
            sweep.record(ScanHarness.scan(scanner, c, p), c, "glare #$i", scanner)
        }
        println(sweep.line()); sweep.failures.forEach { println("  $it") }
        assertTrue(sweep.good >= sweep.total * 4 / 5, sweep.line() + "\n" + sweep.failures.joinToString("\n"))
    }

    @Test
    fun `clutter and every kind of artwork`() {
        val rng = SplitMix64(71)
        val sweep = Sweep("clutter + art modes + off-centre plate")
        for (mode in ArtMode.entries) repeat(16) { i ->
            val c = ScanHarness.randomCase(rng)
            val p = RenderParams(
                width = 480, height = 360, centerX = 190.0 + rng.nextInt(100), centerY = 160.0 + rng.nextInt(40), plateHalfPx = 120.0 + rng.nextInt(30),
                rotationRad = rng.nextInt(360) * DEG, tiltRad = rng.nextInt(25) * DEG, tiltAxisRad = rng.nextInt(360) * DEG,
                clutter = true, artMode = mode, artSeed = rng.nextInt(1000), blurSigmaPx = 0.6, noiseSigma = 4.0, noiseSeed = rng.nextLong(),
            )
            val res = ScanHarness.scan(scanner, c, p, withHue = i % 2 == 0)
            sweep.record(res, c, "$mode #$i", scanner)
        }
        println(sweep.line()); sweep.failures.forEach { println("  $it") }
        assertTrue(sweep.good >= sweep.total - 4, sweep.line() + "\n" + sweep.failures.joinToString("\n"))
    }
}

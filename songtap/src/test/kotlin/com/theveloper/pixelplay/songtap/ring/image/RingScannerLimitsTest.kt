package com.theveloper.pixelplay.songtap.ring.image

import com.theveloper.pixelplay.songtap.SplitMix64
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

/**
 * Pushes one impairment at a time until the scanner stops reading, and prints where the cliff is. The asserts are
 * loose floors (the sweeps in RingScannerBasicTest hold the real promises); the printed lines are what the
 * handoff reports as "the worst case that still read". Synthetic images only: a real camera will differ.
 */
class RingScannerLimitsTest {
    private val scanner = RingScanner()
    private val deg = PI / 180.0

    private fun rate(label: String, steps: List<Double>, trials: Int = 10, build: (Double, SplitMix64, Int) -> RenderParams): List<Pair<Double, Int>> {
        val out = ArrayList<Pair<Double, Int>>()
        val rng = SplitMix64(0x51)
        for (step in steps) {
            var good = 0
            repeat(trials) { i ->
                val c = ScanHarness.randomCase(rng)
                val p = build(step, rng, i)
                if (ScanHarness.ok(ScanHarness.scan(scanner, c, p), c)) good++
            }
            out += step to good
        }
        println("limit $label: " + out.joinToString("  ") { "${it.first}=${it.second}/$trials" })
        return out
    }

    @Test
    fun `tilt`() {
        val r = rate("tilt degrees", listOf(30.0, 40.0, 50.0, 60.0, 70.0)) { t, rng, _ ->
            RenderParams(rotationRad = rng.nextInt(360) * deg, tiltRad = t * deg, tiltAxisRad = rng.nextInt(360) * deg, blurSigmaPx = 0.6)
        }
        assertTrue(r.first { it.first == 30.0 }.second >= 9)
    }

    @Test
    fun `noise`() {
        val r = rate("noise sigma (levels of 255)", listOf(16.0, 24.0, 32.0, 48.0, 64.0)) { n, rng, _ ->
            RenderParams(rotationRad = rng.nextInt(360) * deg, tiltRad = 15 * deg, tiltAxisRad = rng.nextInt(360) * deg, noiseSigma = n, noiseSeed = rng.nextLong(), blurSigmaPx = 0.7)
        }
        assertTrue(r.first { it.first == 16.0 }.second >= 9)
    }

    @Test
    fun `smudge`() {
        val r = rate("smudge percent of slots", listOf(20.0, 25.0, 30.0, 35.0, 40.0)) { f, rng, i ->
            val base = RenderParams(rotationRad = rng.nextInt(360) * deg, tiltRad = 15 * deg, tiltAxisRad = rng.nextInt(360) * deg, blurSigmaPx = 0.6)
            val c0 = ScanHarness.randomCase(SplitMix64(rng.nextLong()))
            val s = RingCodeRenderer.smudgeCovering(c0.bits, base, rng.nextInt(200), f / 100.0, luma = if (i % 2 == 0) 205 else 135)
            RenderParams(rotationRad = base.rotationRad, tiltRad = base.tiltRad, tiltAxisRad = base.tiltAxisRad, blurSigmaPx = 0.6, smudges = listOf(s))
        }
        assertTrue(r.first { it.first == 20.0 }.second >= 9)
    }

    @Test
    fun `contrast`() {
        val r = rate("contrast (share of full)", listOf(0.5, 0.35, 0.25, 0.18, 0.12)) { k, rng, _ ->
            RenderParams(contrast = k, rotationRad = rng.nextInt(360) * deg, tiltRad = 15 * deg, tiltAxisRad = rng.nextInt(360) * deg, noiseSigma = 2.0, noiseSeed = rng.nextLong(), blurSigmaPx = 0.6)
        }
        assertTrue(r.first { it.first == 0.5 }.second >= 9)
    }

    @Test
    fun `plate size`() {
        val r = rate("plate width in pixels", listOf(260.0, 200.0, 160.0, 130.0, 100.0)) { w, rng, _ ->
            RenderParams(plateHalfPx = w / 2, rotationRad = rng.nextInt(360) * deg, tiltRad = 15 * deg, tiltAxisRad = rng.nextInt(360) * deg, blurSigmaPx = 0.6, noiseSigma = 3.0, noiseSeed = rng.nextLong())
        }
        assertTrue(r.first { it.first == 260.0 }.second >= 9)
    }

    @Test
    fun `blur`() {
        val r = rate("blur sigma at 260 px plate", listOf(1.0, 1.25, 1.5, 1.75, 2.0)) { b, rng, _ ->
            RenderParams(rotationRad = rng.nextInt(360) * deg, tiltRad = 10 * deg, tiltAxisRad = rng.nextInt(360) * deg, blurSigmaPx = b)
        }
        assertTrue(r.first { it.first == 1.0 }.second >= 9)
    }
}

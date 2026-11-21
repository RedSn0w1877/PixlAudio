package com.theveloper.pixelplay.songtap.ring.image

import com.theveloper.pixelplay.songtap.SplitMix64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

/** Garbage in, "no code" out: the scanner never throws, never reads a code out of noise, and never reads a wrong one out of a damaged code. */
class RingScannerFuzzTest {
    private val scanner = RingScanner()

    private fun frame(w: Int, h: Int, fill: (Int, Int) -> Int): RingFrame {
        val luma = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) luma[y * w + x] = fill(x, y).toByte()
        return RingFrame(w, h, luma)
    }

    @Test
    fun `random images never decode and never throw`() {
        val rng = SplitMix64(1001)
        var decoded = 0
        var plates = 0
        val reasons = HashMap<String, Int>()
        for (i in 0 until 400) {
            val w = 64 + rng.nextInt(400)
            val h = 64 + rng.nextInt(400)
            val kind = i % 8
            val f = when (kind) {
                0 -> frame(w, h) { _, _ -> rng.nextInt(256) } // white noise
                1 -> frame(w, h) { x, y -> if (((x / 7) + (y / 7)) % 2 == 0) 20 else 230 } // checkerboard
                2 -> frame(w, h) { _, _ -> 0 }
                3 -> frame(w, h) { _, _ -> 255 }
                4 -> frame(w, h) { x, y -> (x * 255 / w + y * 90 / h).coerceAtMost(255) } // gradient
                5 -> { // random dark discs on light
                    val discs = Array(30 + rng.nextInt(60)) { intArrayOf(rng.nextInt(w), rng.nextInt(h), 2 + rng.nextInt(w / 6 + 4)) }
                    frame(w, h) { x, y -> if (discs.any { (x - it[0]) * (x - it[0]) + (y - it[1]) * (y - it[1]) <= it[2] * it[2] }) 40 else 225 }
                }
                6 -> { // concentric rings and dots like a code, but not one
                    val cx = w / 2.0
                    val cy = h / 2.0
                    frame(w, h) { x, y ->
                        val r = kotlin.math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                        if (r < 0.2 * minOf(w, h) || (r.toInt() / 9) % 3 == 0 && rng.nextInt(3) == 0) 40 else 225
                    }
                }
                else -> frame(w, h) { x, y -> if ((x * y) % 97 < 40) 30 else 220 } // moire
            }
            val res = scanner.scan(f)
            when (res) {
                is RingScanResult.Decoded -> decoded++
                is RingScanResult.Failed -> reasons.merge(res.reason.code, 1, Int::plus)
            }
            if (scanner.diagnostics.found) plates++
        }
        println("fuzz: 400 random images, $decoded decoded, $plates looked like a plate, reasons $reasons")
        assertEquals(0, decoded)
    }

    @Test
    fun `degenerate frames fail cleanly`() {
        assertTrue(scanner.scan(RingFrame(10, 10, ByteArray(100))) is RingScanResult.Failed)
        assertTrue(scanner.scan(RingFrame(100, 100, ByteArray(50))) is RingScanResult.Failed)
        assertTrue(scanner.scan(RingFrame(0, 0, ByteArray(0))) is RingScanResult.Failed)
        assertTrue(scanner.scan(RingFrame(64, 64, ByteArray(64 * 64), hue = ByteArray(1), hueWidth = 1, hueHeight = 1)) is RingScanResult.Failed)
        val huge = RingFrame(2000, 1500, ByteArray(2000 * 1500) { (it * 31).toByte() })
        assertTrue(scanner.scan(huge) is RingScanResult.Failed)
    }

    @Test
    fun `a damaged code is read right or not at all`() {
        val rng = SplitMix64(2002)
        var right = 0
        var failed = 0
        var wrong = 0
        for (i in 0 until 240) {
            val c = ScanHarness.randomCase(rng)
            val base = RenderParams(rotationRad = rng.nextInt(360) * PI / 180, tiltRad = rng.nextInt(25) * PI / 180, tiltAxisRad = rng.nextInt(360) * PI / 180, blurSigmaPx = 0.6, noiseSigma = 4.0, noiseSeed = rng.nextLong())
            // random smudges and glare of any size at any place: from harmless to far past what the code can repair
            val smudges = ArrayList<Smudge>()
            repeat(1 + rng.nextInt(5)) {
                smudges += Smudge(60.0 + rng.nextInt(240), 60.0 + rng.nextInt(240), 12.0 + rng.nextInt(70), rng.nextInt(256), 0.5 + rng.nextInt(51) / 100.0)
            }
            val glares = ArrayList<Glare>()
            repeat(rng.nextInt(3)) {
                glares += Glare(60.0 + rng.nextInt(240), 60.0 + rng.nextInt(240), 15.0 + rng.nextInt(60), 15.0 + rng.nextInt(60), rng.nextInt(180) * PI / 180, 100.0 + rng.nextInt(200))
            }
            val p = RenderParams(
                rotationRad = base.rotationRad, tiltRad = base.tiltRad, tiltAxisRad = base.tiltAxisRad, blurSigmaPx = 0.6, noiseSigma = 4.0, noiseSeed = base.noiseSeed,
                smudges = smudges, glares = glares,
            )
            val res = ScanHarness.scan(scanner, c, p)
            when {
                ScanHarness.ok(res, c) -> right++
                res is RingScanResult.Decoded -> wrong++
                else -> failed++
            }
        }
        println("damage fuzz: 240 codes with random smudges and glare of any size: $right read right, $failed not read, $wrong read wrong")
        assertEquals(0, wrong, "a damaged code must never read as another code")
    }

    @Test
    fun `a code with whole frames of garbage in between does not poison the accumulator`() {
        val rng = SplitMix64(3003)
        val c = ScanHarness.randomCase(rng)
        val acc = RingScanAccumulator()
        var accepted = 0
        for (i in 0 until 12) {
            val res = if (i % 2 == 0) {
                ScanHarness.scan(scanner, c, RenderParams(rotationRad = i * 0.3, noiseSigma = 4.0, noiseSeed = i.toLong()))
            } else scanner.scan(frame(300, 300) { _, _ -> rng.nextInt(256) })
            if (acc.offer(res, i * 80L) != null) accepted++
        }
        assertTrue(accepted >= 1)
    }
}

package com.theveloper.pixelplay.presentation.lyrics.background

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class LyricsBackgroundGradeTest {

    /** Independent three-step reference, in double precision, on 0..1 sRGB. */
    private fun referenceGrade(r: Double, g: Double, b: Double): DoubleArray {
        val luma = 0.2125 * r + 0.7154 * g + 0.0721 * b
        // 1. saturation 2.75: mix(luma, rgb, 2.75)
        val sat = doubleArrayOf(r, g, b).map { luma + (it - luma) * 2.75 }
        // 2. contrast 1.9 around 0.5
        val con = sat.map { (it - 0.5) * 1.9 + 0.5 }
        // 3. brightness ×0.7, then clamp once at the end
        return con.map { (it * 0.7).coerceIn(0.0, 1.0) }.toDoubleArray()
    }

    private fun applyMatrix(m: FloatArray, r: Int, g: Int, b: Int, a: Int): FloatArray {
        return FloatArray(4) { row ->
            val v = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b +
                m[row * 5 + 3] * a + m[row * 5 + 4]
            v.coerceIn(0f, 255f) // ColorMatrixColorFilter clamps the result
        }
    }

    @Test
    fun `single GRADE matrix equals the three-step reference on random colours`() {
        val random = Random(20260925)
        val grade = LyricsBackgroundGrade.GRADE
        repeat(20_000) {
            val r = random.nextInt(256)
            val g = random.nextInt(256)
            val b = random.nextInt(256)
            val expected = referenceGrade(r / 255.0, g / 255.0, b / 255.0)
            val actual = applyMatrix(grade, r, g, b, 255)
            for (c in 0 until 3) {
                assertEquals(
                    expected[c] * 255.0,
                    actual[c].toDouble(),
                    0.01,
                    "channel $c of ($r, $g, $b)"
                )
            }
            assertEquals(255f, actual[3], 0f, "alpha must pass through")
        }
    }

    @Test
    fun `kotlin reference grade matches the independent reference`() {
        val random = Random(7)
        val out = FloatArray(3)
        repeat(5_000) {
            val r = random.nextFloat()
            val g = random.nextFloat()
            val b = random.nextFloat()
            LyricsBackgroundGrade.gradeReference(r, g, b, out)
            val expected = referenceGrade(r.toDouble(), g.toDouble(), b.toDouble())
            for (c in 0 until 3) assertEquals(expected[c], out[c].toDouble(), 1e-5)
        }
    }

    @Test
    fun `GRADE matches the coefficients published in the spec`() {
        val expected = floatArrayOf(
            3.16291f, -1.66509f, -0.16781f, 0f, -80.325f,
            -0.49459f, 1.99241f, -0.16781f, 0f, -80.325f,
            -0.49459f, -1.66509f, 3.48969f, 0f, -80.325f,
            0f, 0f, 0f, 1f, 0f,
        )
        val grade = LyricsBackgroundGrade.GRADE
        for (i in expected.indices) {
            val tolerance = if (i % 5 == 4) 1e-3f else 1e-4f
            assertEquals(expected[i], grade[i], tolerance, "matrix entry $i")
        }
        for (row in 0 until 3) {
            val sum = grade[row * 5] + grade[row * 5 + 1] + grade[row * 5 + 2]
            assertEquals(1.33f, sum, 1e-5f, "row $row sums to contrast × brightness")
        }
    }

    @Test
    fun `white art counts as bright and black art does not`() {
        val scratch = FloatArray(3)
        assertTrue(LyricsBackgroundGrade.gradedLuma(1f, 1f, 1f, scratch) > LyricsBackgroundGrade.BRIGHT_ART_LUMA)
        assertTrue(LyricsBackgroundGrade.gradedLuma(0f, 0f, 0f, scratch) < LyricsBackgroundGrade.BRIGHT_ART_LUMA)
        assertTrue(LyricsBackgroundGrade.gradedLuma(0.5f, 0.5f, 0.5f, scratch) < LyricsBackgroundGrade.BRIGHT_ART_LUMA)
    }

    @Test
    fun `three box passes approximate the requested gaussian sigma`() {
        for (sigma in floatArrayOf(1.5f, 3.5f, 4.9f, 7.8f, 15.5f)) {
            val radii = SpriteBlur.boxRadiiForGaussian(sigma)
            // Variance of a box of width w = 2r + 1 is (w² − 1) / 12.
            val variance = radii.sumOf { r -> ((2 * r + 1) * (2 * r + 1) - 1) / 12.0 }
            val relativeError = kotlin.math.abs(kotlin.math.sqrt(variance) - sigma) / sigma
            assertTrue(relativeError < 0.12, "σ=$sigma → radii ${radii.toList()} (σ≈${kotlin.math.sqrt(variance)})")
        }
    }

    @Test
    fun `padded sprite blur conserves energy and fades to transparent at the edge`() {
        val artSize = 8
        val art = IntArray(artSize * artSize) { 0xFFFFFFFF.toInt() }
        val sigma = 2f
        val pad = kotlin.math.ceil(3 * sigma).toInt()
        val out = SpriteBlur.bakeSprite(art, artSize, pad, sigma, opaque = false)
        val size = artSize + 2 * pad
        val alphaSum = out.sumOf { (it ushr 24).toLong() }
        // 64 opaque texels in, ~64 texels' worth of alpha out (8-bit rounding aside).
        assertEquals(64.0 * 255.0, alphaSum.toDouble(), 64.0 * 255.0 * 0.03)
        // Corners are (almost) transparent.
        assertTrue((out[0] ushr 24) <= 2)
        assertTrue((out[size * size - 1] ushr 24) <= 2)
        // Centre stays opaque white.
        val centre = out[(size / 2) * size + size / 2]
        assertTrue((centre ushr 24) > 200)
        assertEquals(0xFF, (centre shr 16) and 0xFF)
    }
}

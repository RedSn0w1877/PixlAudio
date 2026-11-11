package com.theveloper.pixelplay.data.spatial.playback

import com.theveloper.pixelplay.data.spatial.SpatialFixtures
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * cloud-audio-spec §7 and §8: the stage must reproduce the worker's reference (`spatial/preview.py` `process`, from a
 * zero state) for every profile and rate of the shipped table, within the golden file's tolerance. This is the test
 * that proves the app's table and the golden file belong together, so it fails (never skips) when they do not.
 */
class SpatialStageGoldenTest {

    private val tables = SpatialPreviewTables.parse(SpatialFixtures.previewTable())
    private val golden = Json.parseToJsonElement(SpatialFixtures.golden()).jsonObject

    @Test
    fun `every profile and rate matches the reference within the tolerance`() {
        val frames = golden.number("frames").toInt()
        val tolerance = golden.number("tolerance")
        val input = golden.obj("input")
        val inL = input.floats("left")
        val inR = input.floats("right")
        assertEquals(frames, inL.size)
        assertEquals(frames, inR.size)
        val output = golden.obj("output")
        assertEquals(SpatialProfile.entries.map { it.id }.toSet(), output.keys, "golden profiles")

        var checked = 0
        for (profile in SpatialProfile.entries) {
            for (rate in SpatialPreviewTables.RATES) {
                val expected = output.obj(profile.id).obj(rate.toString())
                val expL = expected.doubles("left")
                val expR = expected.doubles("right")
                val preset = tables.preset(profile, rate) ?: error("${profile.id}/$rate is not in the table")
                val (outL, outR) = runFromZero(preset, inL, inR)
                var worst = 0.0
                var worstAt = -1
                for (i in 0 until frames) {
                    val e = maxOf(abs(outL[i] - expL[i]), abs(outR[i] - expR[i]))
                    if (e > worst) {
                        worst = e
                        worstAt = i
                    }
                }
                assertTrue(worst <= tolerance, "${profile.id}/$rate: error $worst at frame $worstAt (tolerance $tolerance)")
                checked++
            }
        }
        assertEquals(SpatialProfile.entries.size * SpatialPreviewTables.RATES.size, checked)
    }

    @Test
    fun `block boundaries do not change the output`() {
        val input = golden.obj("input")
        val inL = input.floats("left")
        val inR = input.floats("right")
        val preset = tables.preset(SpatialProfile.HALL, 48_000)!!
        val (oneL, oneR) = runFromZero(preset, inL, inR, chunks = intArrayOf(512))
        val (oddL, oddR) = runFromZero(preset, inL, inR, chunks = intArrayOf(1, 7, 300, 512, 3, 129))
        for (i in inL.indices) {
            assertEquals(oneL[i], oddL[i], 1e-6, "left $i")
            assertEquals(oneR[i], oddR[i], 1e-6, "right $i")
        }
    }

    @Test
    fun `the table parses with the shapes of section 7`() {
        assertTrue(tables.headroomDb > 0f, "headroomDb")
        for (profile in SpatialProfile.entries) {
            for (rate in SpatialPreviewTables.RATES) {
                val preset = tables.preset(profile, rate)!!
                assertEquals(rate, preset.sampleRate)
                assertTrue(preset.taps > 0)
                assertTrue(preset.lowDelay in 0..1_000)
                assertTrue(preset.gain > 0f && preset.gain < 2f, "gain")
            }
        }
        assertTrue(tables.maxSparseDelay > 0)
        assertEquals(null, tables.preset(SpatialProfile.STUDIO, 22_050), "other rates play without the stage")
    }

    private fun runFromZero(
        preset: SpatialPreset,
        inL: FloatArray,
        inR: FloatArray,
        chunks: IntArray = intArrayOf(1, 37, 512, 200, 64, 511),
    ): Pair<DoubleArray, DoubleArray> {
        val kernel = SpatialStageKernel()
        kernel.ensureCapacity(tables)
        kernel.setTarget(preset, wet = true)
        kernel.reset()
        val frames = inL.size
        val outL = DoubleArray(frames)
        val outR = DoubleArray(frames)
        val bl = FloatArray(kernel.maxBlock)
        val br = FloatArray(kernel.maxBlock)
        val ol = FloatArray(kernel.maxBlock)
        val oR = FloatArray(kernel.maxBlock)
        var done = 0
        var c = 0
        while (done < frames) {
            val n = minOf(chunks[c++ % chunks.size], frames - done, kernel.maxBlock)
            System.arraycopy(inL, done, bl, 0, n)
            System.arraycopy(inR, done, br, 0, n)
            kernel.process(bl, br, ol, oR, n)
            for (i in 0 until n) {
                outL[done + i] = ol[i].toDouble()
                outR[done + i] = oR[i].toDouble()
            }
            done += n
        }
        return outL to outR
    }

    private fun JsonObject.obj(key: String): JsonObject = requireNotNull(this[key]) { "missing $key" }.jsonObject

    private fun JsonObject.number(key: String): Double = requireNotNull(this[key]) { "missing $key" }.number()

    private fun JsonObject.doubles(key: String): DoubleArray {
        val values = requireNotNull(this[key]) { "missing $key" }.jsonArray
        return DoubleArray(values.size) { values[it].number() }
    }

    private fun JsonObject.floats(key: String): FloatArray {
        val values = doubles(key)
        return FloatArray(values.size) { values[it].toFloat() }
    }

    private fun JsonElement.number(): Double = jsonPrimitive.content.toDouble()
}

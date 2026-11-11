package com.theveloper.pixelplay.data.spatial.playback

import com.theveloper.pixelplay.data.spatial.SpatialContract
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One profile at one sample rate of the on-device spatial stage (cloud-audio-spec §7), in the form the stage runs on.
 *
 * The head is symmetric, so the table's two FIRs act on the sum and the difference of the two ears:
 * `yL + yR = fir(hiL + hiR, direct + cross)` and `yL - yR = fir(hiL - hiR, direct - cross)`. [firSum] and [firDif]
 * hold `(direct ± cross) / 2`, which halves the FIR work and gives the same output. A sparse tap
 * `gainFromLeft * hiL + gainFromRight * hiR` is written the same way on the sum and the difference.
 */
class SpatialPreset internal constructor(
    val profile: SpatialProfile,
    val sampleRate: Int,
    val xoverHz: Double,
    /** The low band's delay in samples (`lowDelay`); also the delay of the dry signal the stage fades to. */
    val lowDelay: Int,
    /** The number of FIR taps (`taps`). */
    val taps: Int,
    internal val firSum: FloatArray,
    internal val firDif: FloatArray,
    internal val sparseLDelay: IntArray,
    internal val sparseLSum: FloatArray,
    internal val sparseLDif: FloatArray,
    internal val sparseRDelay: IntArray,
    internal val sparseRSum: FloatArray,
    internal val sparseRDif: FloatArray,
    /** The output gain (`gain`): the loudness match and the 4 dB of headroom. */
    val gain: Float,
) {
    /** The longest sparse delay, in samples. */
    val maxSparseDelay: Int = maxOf(sparseLDelay.maxOrNull() ?: 0, sparseRDelay.maxOrNull() ?: 0)

    // The crossover (§7): RBJ low-pass, Q = 1/sqrt(2), a0 = 1, computed here from xoverHz and the rate exactly as the
    // worker's `lowpass_coefficients` does. Two in series are the crossover.
    internal val b0: Double
    internal val b1: Double
    internal val b2: Double
    internal val a1: Double
    internal val a2: Double

    init {
        val w0 = 2.0 * PI * xoverHz / sampleRate
        val alpha = sin(w0) / (2.0 * (1.0 / sqrt(2.0)))
        val cos0 = cos(w0)
        val a0 = 1.0 + alpha
        b0 = (1.0 - cos0) / 2.0 / a0
        b1 = (1.0 - cos0) / a0
        b2 = (1.0 - cos0) / 2.0 / a0
        a1 = -2.0 * cos0 / a0
        a2 = (1.0 - alpha) / a0
    }

    /** Whether the crossover of [other] is this one's (same rate and frequency), so filter states carry over. */
    internal fun sameCrossover(other: SpatialPreset): Boolean =
        other.sampleRate == sampleRate && other.xoverHz == xoverHz
}

/**
 * The on-device table `spatial_preview_v1.json` (cloud-audio-spec §7), parsed once into primitive arrays. Every number
 * comes from the file (the worker retunes them); none is hard-coded here.
 */
class SpatialPreviewTables private constructor(
    /** `headroomDb` at the table's top level: the headroom the stage's `gain` holds (§3.1, §6.3 Level). */
    val headroomDb: Float,
    private val presets: Array<SpatialPreset?>,
) {
    /** The largest sparse delay of any preset (the delay ring is sized from it once). */
    val maxSparseDelay: Int = presets.maxOf { it?.maxSparseDelay ?: 0 }

    /** The most FIR taps of any preset. */
    val maxTaps: Int = presets.maxOf { it?.taps ?: 0 }

    /** The largest `lowDelay` of any preset. */
    val maxLowDelay: Int = presets.maxOf { it?.lowDelay ?: 0 }

    /** The preset of [profile] at [sampleRate], or null for a rate the stage does not take (§6.3: plays without it). */
    fun preset(profile: SpatialProfile, sampleRate: Int): SpatialPreset? {
        val rateIndex = RATES.indexOf(sampleRate)
        if (rateIndex < 0) return null
        return presets[profile.ordinal * RATES.size + rateIndex]
    }

    companion object {
        /** The two rates of §7. Anything else plays without the stage. */
        val RATES = intArrayOf(44_100, 48_000)

        fun supportsRate(sampleRate: Int): Boolean = RATES.contains(sampleRate)

        /**
         * Parses the table's text. Throws [IllegalArgumentException] when the file is not the table this app knows
         * (schema, version, engine, a missing profile or rate, arrays of the wrong length): the stage then stays off.
         */
        fun parse(text: String): SpatialPreviewTables {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.string("schema") == SpatialContract.PREVIEW_SCHEMA) { "not ${SpatialContract.PREVIEW_SCHEMA}" }
            require(root.number("v").toInt() == SpatialContract.PREVIEW_VERSION) { "unknown table version" }
            require(root.string("engine") == SpatialContract.ENGINE_ID) { "unknown engine" }
            val headroomDb = root.number("headroomDb").toFloat()
            val profiles = root.obj("profiles")
            val presets = arrayOfNulls<SpatialPreset>(SpatialProfile.entries.size * RATES.size)
            for (profile in SpatialProfile.entries) {
                val rates = profiles.obj(profile.id)
                RATES.forEachIndexed { rateIndex, rate ->
                    presets[profile.ordinal * RATES.size + rateIndex] = parsePreset(profile, rate, rates.obj(rate.toString()))
                }
            }
            return SpatialPreviewTables(headroomDb, presets)
        }

        private fun parsePreset(profile: SpatialProfile, rate: Int, preset: JsonObject): SpatialPreset {
            val taps = preset.number("taps").toInt()
            val direct = preset.floats("direct")
            val cross = preset.floats("cross")
            require(taps > 0 && direct.size == taps && cross.size == taps) { "${profile.id}/$rate: FIR length" }
            val lowDelay = preset.number("lowDelay").toInt()
            require(lowDelay >= 0) { "${profile.id}/$rate: lowDelay" }
            val firSum = FloatArray(taps) { ((direct[it] + cross[it]) * 0.5).toFloat() }
            val firDif = FloatArray(taps) { ((direct[it] - cross[it]) * 0.5).toFloat() }
            val left = sparse(preset.array("sparseL"), "${profile.id}/$rate sparseL")
            val right = sparse(preset.array("sparseR"), "${profile.id}/$rate sparseR")
            return SpatialPreset(
                profile = profile,
                sampleRate = rate,
                xoverHz = preset.number("xoverHz"),
                lowDelay = lowDelay,
                taps = taps,
                firSum = firSum,
                firDif = firDif,
                sparseLDelay = left.delays,
                sparseLSum = left.sum,
                sparseLDif = left.dif,
                sparseRDelay = right.delays,
                sparseRSum = right.sum,
                sparseRDif = right.dif,
                gain = preset.number("gain").toFloat(),
            )
        }

        private class Sparse(val delays: IntArray, val sum: FloatArray, val dif: FloatArray)

        // [delaySamples, gainFromLeft, gainFromRight]: gl*hiL + gr*hiR = ((gl+gr)/2)*(hiL+hiR) + ((gl-gr)/2)*(hiL-hiR)
        private fun sparse(taps: JsonArray, what: String): Sparse {
            val delays = IntArray(taps.size)
            val sum = FloatArray(taps.size)
            val dif = FloatArray(taps.size)
            taps.forEachIndexed { i, element ->
                val tap = element.jsonArray
                require(tap.size == 3) { "$what: a tap is [delay, fromLeft, fromRight]" }
                val delay = tap[0].number()
                require(delay >= 0.0 && delay == Math.floor(delay)) { "$what: delay" }
                val fromLeft = tap[1].number()
                val fromRight = tap[2].number()
                delays[i] = delay.toInt()
                sum[i] = ((fromLeft + fromRight) * 0.5).toFloat()
                dif[i] = ((fromLeft - fromRight) * 0.5).toFloat()
            }
            return Sparse(delays, sum, dif)
        }

        private fun JsonObject.obj(key: String): JsonObject =
            requireNotNull(this[key]) { "missing $key" }.jsonObject

        private fun JsonObject.array(key: String): JsonArray =
            requireNotNull(this[key]) { "missing $key" }.jsonArray

        private fun JsonObject.string(key: String): String =
            requireNotNull(this[key]) { "missing $key" }.jsonPrimitive.content

        private fun JsonObject.number(key: String): Double = requireNotNull(this[key]) { "missing $key" }.number()

        private fun JsonObject.floats(key: String): DoubleArray {
            val values = array(key)
            return DoubleArray(values.size) { values[it].number() }
        }

        private fun JsonElement.number(): Double =
            requireNotNull(jsonPrimitive.content.toDoubleOrNull()) { "not a number: $this" }
    }
}

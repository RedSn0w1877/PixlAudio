package com.theveloper.pixelplay.data.tais.stems

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Deterministic reconstruction rules; both channels share a mask and a peak-safe linear gain. */
internal object StemAudioQuality {
    fun linkedInstrumentalMask(mixPower: Float, vocalPower: Float, frequencyHz: Float): Float {
        if (!mixPower.isFinite() || !vocalPower.isFinite()) {
            throw IllegalArgumentException("Separation model returned non-finite audio")
        }
        if (mixPower <= 1e-12f) return 1f
        val vocalFraction = sqrt(vocalPower.coerceAtLeast(0f) / mixPower).coerceIn(0f, 1f)
        // Protect the sub-bass, then smoothly introduce separation. A hard 150Hz boundary
        // left low vocal fundamentals in the instrumental and caused an abrupt timbral step.
        val blend = ((frequencyHz - 55f) / 95f).coerceIn(0f, 1f)
        val smoothBlend = blend * blend * (3f - 2f * blend)
        return (1f - vocalFraction * smoothBlend).coerceIn(0f, 1f)
    }

    fun peakSafeGain(left: FloatArray, right: FloatArray, desiredGain: Float): Float {
        var peak = 0f
        for (index in left.indices) {
            require(left[index].isFinite() && right[index].isFinite()) { "Non-finite rendered audio" }
            peak = maxOf(peak, abs(left[index]), abs(right[index]))
        }
        return if (peak <= 1e-8f) 1f else min(desiredGain.coerceIn(0.5f, 2f), 0.98f / peak)
    }
}

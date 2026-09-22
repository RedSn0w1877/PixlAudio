package com.theveloper.pixelplay.data.remix.dsp

import kotlin.math.PI
import kotlin.math.tan

/**
 * Topology-preserving (Zavalishin) state-variable filter: low-pass, band-pass and high-pass from
 * one structure, with all three available on the same sample.
 *
 * This is the lo-fi knob's filter, chosen over a [Biquad] for two reasons: it stays well-behaved
 * when the cutoff is swept fast (a dragged slider), and having LP and BP simultaneously lets the
 * UI morph between them on a single control instead of switching filter types and clicking.
 *
 * Per sample:  ~12 flops. Coefficients are recomputed per block via [setCutoff].
 */
class StateVariableFilter {
    private var g = 0f
    private var k = 1f
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f

    private var ic1eq = 0f
    private var ic2eq = 0f

    var lowPass: Float = 0f
        private set
    var bandPass: Float = 0f
        private set
    var highPass: Float = 0f
        private set

    fun reset() {
        ic1eq = 0f
        ic2eq = 0f
        lowPass = 0f
        bandPass = 0f
        highPass = 0f
    }

    fun setCutoff(freqHz: Float, q: Float, sampleRate: Int) {
        val nyquist = sampleRate / 2f
        val f = freqHz.coerceIn(20f, nyquist * 0.98f)
        g = tan(PI * f / sampleRate).toFloat()
        k = 1f / q.coerceIn(0.05f, 40f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun process(x: Float) {
        val v3 = x - ic2eq
        val v1 = a1 * ic1eq + a2 * v3
        val v2 = ic2eq + a2 * ic1eq + a3 * v3
        ic1eq = 2f * v1 - ic1eq
        ic2eq = 2f * v2 - ic2eq
        lowPass = v2
        bandPass = v1
        highPass = x - k * v1 - v2
    }
}

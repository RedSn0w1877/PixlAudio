package com.theveloper.pixelplay.data.remix.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order IIR section in transposed direct form II, with the usual RBJ cookbook coefficients.
 *
 * TDF-II rather than DF-I because it needs two state words instead of four and behaves better in
 * float when coefficients are updated between blocks — which is how every filter here is driven.
 *
 * Coefficients are recomputed **per block, not per sample**: a `sin`/`cos` pair per sample, per
 * stem, would cost more than the rest of the engine put together. At 128–256 frame blocks
 * (3–6 ms) a swept cutoff is still smooth.
 *
 * For anything swept hard — the lo-fi knob — prefer [StateVariableFilter]: biquads can misbehave
 * transiently when their coefficients move quickly. These are for fixed or slowly-moving shapes
 * (the head-shadow shelf, the elevation notch).
 */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var z1 = 0f
    private var z2 = 0f

    fun reset() {
        z1 = 0f
        z2 = 0f
    }

    fun setPassthrough() {
        b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
    }

    fun setLowPass(freqHz: Float, q: Float, sampleRate: Int) {
        val w0 = omega(freqHz, sampleRate)
        val cosW = cos(w0)
        val alpha = sin(w0) / (2f * q.coerceAtLeast(MIN_Q))
        val a0 = 1f + alpha
        normalize(
            b0 = (1f - cosW) / 2f, b1 = 1f - cosW, b2 = (1f - cosW) / 2f,
            a0 = a0, a1 = -2f * cosW, a2 = 1f - alpha,
        )
    }

    fun setHighPass(freqHz: Float, q: Float, sampleRate: Int) {
        val w0 = omega(freqHz, sampleRate)
        val cosW = cos(w0)
        val alpha = sin(w0) / (2f * q.coerceAtLeast(MIN_Q))
        normalize(
            b0 = (1f + cosW) / 2f, b1 = -(1f + cosW), b2 = (1f + cosW) / 2f,
            a0 = 1f + alpha, a1 = -2f * cosW, a2 = 1f - alpha,
        )
    }

    fun setPeaking(freqHz: Float, q: Float, gainDb: Float, sampleRate: Int) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = omega(freqHz, sampleRate)
        val cosW = cos(w0)
        val alpha = sin(w0) / (2f * q.coerceAtLeast(MIN_Q))
        normalize(
            b0 = 1f + alpha * a, b1 = -2f * cosW, b2 = 1f - alpha * a,
            a0 = 1f + alpha / a, a1 = -2f * cosW, a2 = 1f - alpha / a,
        )
    }

    fun setLowShelf(freqHz: Float, gainDb: Float, sampleRate: Int, slope: Float = 1f) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = omega(freqHz, sampleRate)
        val cosW = cos(w0)
        val alpha = sin(w0) / 2f * sqrt((a + 1f / a) * (1f / slope - 1f) + 2f)
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha
        normalize(
            b0 = a * ((a + 1f) - (a - 1f) * cosW + twoSqrtAAlpha),
            b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW),
            b2 = a * ((a + 1f) - (a - 1f) * cosW - twoSqrtAAlpha),
            a0 = (a + 1f) + (a - 1f) * cosW + twoSqrtAAlpha,
            a1 = -2f * ((a - 1f) + (a + 1f) * cosW),
            a2 = (a + 1f) + (a - 1f) * cosW - twoSqrtAAlpha,
        )
    }

    fun setHighShelf(freqHz: Float, gainDb: Float, sampleRate: Int, slope: Float = 1f) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = omega(freqHz, sampleRate)
        val cosW = cos(w0)
        val alpha = sin(w0) / 2f * sqrt((a + 1f / a) * (1f / slope - 1f) + 2f)
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha
        normalize(
            b0 = a * ((a + 1f) + (a - 1f) * cosW + twoSqrtAAlpha),
            b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW),
            b2 = a * ((a + 1f) + (a - 1f) * cosW - twoSqrtAAlpha),
            a0 = (a + 1f) - (a - 1f) * cosW + twoSqrtAAlpha,
            a1 = 2f * ((a - 1f) - (a + 1f) * cosW),
            a2 = (a + 1f) - (a - 1f) * cosW - twoSqrtAAlpha,
        )
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    private fun normalize(b0: Float, b1: Float, b2: Float, a0: Float, a1: Float, a2: Float) {
        val inv = 1f / a0
        this.b0 = b0 * inv
        this.b1 = b1 * inv
        this.b2 = b2 * inv
        this.a1 = a1 * inv
        this.a2 = a2 * inv
    }

    private fun omega(freqHz: Float, sampleRate: Int): Float {
        // Keep below Nyquist: tan()/cos() blow up as w0 approaches pi.
        val nyquist = sampleRate / 2f
        val f = freqHz.coerceIn(10f, nyquist * 0.99f)
        return (2.0 * PI * f / sampleRate).toFloat()
    }

    private companion object {
        const val MIN_Q = 0.05f
    }
}

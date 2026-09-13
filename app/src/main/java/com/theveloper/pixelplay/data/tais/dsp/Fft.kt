package com.theveloper.pixelplay.data.tais.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * DFT dispatcher shared by every TAIS DSP path that needs an FFT of a length the caller doesn't
 * control (dictated by a model's STFT contract): power-of-2 lengths go through the fast radix-2
 * core directly; other lengths go through [Bluestein]'s algorithm, which re-expresses an
 * arbitrary-length DFT as a power-of-2 convolution and reuses [radix2] as its inner engine.
 *
 * Used by [com.theveloper.pixelplay.data.tais.stems.TaisStemSeparator] (6144-point STFT, not a
 * power of two).
 */
internal object Fft {
    /**
     * A fixed-size workspace owned by one render. Scratch is released with the render instead
     * of being retained on a worker thread or in the global immutable-plan cache. Calls are
     * serialized if a caller shares this workspace; separate renders never share scratch.
     */
    class Workspace(private val size: Int) {
        init {
            require(size > 0) { "FFT size must be positive" }
        }

        private val bluestein = if (size and (size - 1) == 0) null else Bluestein.Workspace(size)

        @Synchronized
        fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
            require(re.size == size && im.size == size) { "FFT input must match workspace size $size" }
            if (bluestein == null) radix2(re, im, inverse)
            else bluestein.transform(re, im, inverse)
        }
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        if (n and (n - 1) == 0) {
            radix2(re, im, inverse)
        } else {
            Bluestein.transform(re, im, inverse)
        }
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT. [re]/[im] length must be a power of two. */
    fun radix2(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    val nextIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                    curIm = nextIm
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}

/**
 * Bluestein's algorithm (chirp z-transform): computes a DFT of arbitrary length [n] by turning it
 * into a linear convolution, which — zero-padded to a power-of-two length [Plan.m] — can run
 * through the ordinary radix-2 FFT ([Fft.radix2]). Forward/inverse are both implemented in terms
 * of a single forward chirp-z transform, following the standard IDFT(x) = conj(DFT(conj(x)))/n
 * identity, so there's only one place the chirp/convolution math can go wrong.
 *
 * Per-length chirp and convolution-kernel arrays are cached in [plans] — they depend only on the
 * transform length, not on the data, so they're computed once and reused across every STFT/iSTFT
 * frame at that length.
 */
internal object Bluestein {
    private val plans = HashMap<Int, Plan>()

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        Workspace(re.size).transform(re, im, inverse)
    }

    /** Only immutable chirps/kernels are shared. The convolution arrays belong to this owner. */
    class Workspace(private val n: Int) {
        private val plan = synchronized(plans) { plans.getOrPut(n) { Plan(n) } }
        private val aRe = FloatArray(plan.m)
        private val aIm = FloatArray(plan.m)

        fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
            if (inverse) {
                for (i in 0 until n) im[i] = -im[i]
            }
            forward(re, im)
            if (inverse) {
                for (i in 0 until n) {
                    re[i] /= n
                    im[i] = -im[i] / n
                }
            }
        }

        private fun forward(re: FloatArray, im: FloatArray) {
            // The first n values are overwritten below. Clear the convolution padding left
            // by the previous transform so reuse is identical to fresh zero-filled arrays.
            aRe.fill(0f, n, plan.m)
            aIm.fill(0f, n, plan.m)

            for (k in 0 until n) {
                // a[k] = x[k] * w[k]
                aRe[k] = re[k] * plan.wRe[k] - im[k] * plan.wIm[k]
                aIm[k] = re[k] * plan.wIm[k] + im[k] * plan.wRe[k]
            }

            Fft.radix2(aRe, aIm, inverse = false)
            for (i in 0 until plan.m) {
                // Pointwise multiply against the pre-transformed convolution kernel.
                val r = aRe[i] * plan.bFftRe[i] - aIm[i] * plan.bFftIm[i]
                val ii = aRe[i] * plan.bFftIm[i] + aIm[i] * plan.bFftRe[i]
                aRe[i] = r
                aIm[i] = ii
            }
            Fft.radix2(aRe, aIm, inverse = true)

            for (k in 0 until n) {
                // X[k] = w[k] * c[k]
                val cRe = aRe[k]
                val cIm = aIm[k]
                re[k] = cRe * plan.wRe[k] - cIm * plan.wIm[k]
                im[k] = cRe * plan.wIm[k] + cIm * plan.wRe[k]
            }
        }
    }

    private class Plan(n: Int) {
        val m: Int
        val wRe: FloatArray
        val wIm: FloatArray
        val bFftRe: FloatArray
        val bFftIm: FloatArray

        init {
            var mm = 1
            while (mm < 2 * n - 1) mm = mm shl 1
            m = mm

            // Chirp w[k] = exp(-i*pi*k^2/n); k^2 mod 2n keeps the angle argument bounded for
            // large k instead of accumulating floating-point error over a huge product.
            wRe = FloatArray(n)
            wIm = FloatArray(n)
            for (k in 0 until n) {
                val kk = (k.toLong() * k) % (2L * n)
                val angle = Math.PI * kk / n
                wRe[k] = cos(angle).toFloat()
                wIm[k] = -sin(angle).toFloat()
            }

            // Convolution kernel b: conj(w) mirrored around 0, zero-padded to length m.
            val bRe = FloatArray(m)
            val bIm = FloatArray(m)
            bRe[0] = wRe[0]
            bIm[0] = -wIm[0]
            for (k in 1 until n) {
                bRe[k] = wRe[k]
                bIm[k] = -wIm[k]
                bRe[m - k] = wRe[k]
                bIm[m - k] = -wIm[k]
            }
            Fft.radix2(bRe, bIm, inverse = false)
            bFftRe = bRe
            bFftIm = bIm
        }
    }
}

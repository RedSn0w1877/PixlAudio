package com.theveloper.pixelplay.data.spatial.playback

import java.util.Arrays

/**
 * The maths of the on-device spatial stage (cloud-audio-spec §7) on planar float blocks, apart from Media3 so it runs in
 * JVM unit tests: the crossover (two RBJ low-pass biquads in series, Q = 1/sqrt 2, in double), the high band through
 * the two FIRs and the sparse room, the low band delayed by `lowDelay`, all times `gain`. It reproduces the worker's
 * reference `spatial/preview.py` `process` from a zero state (the golden test).
 *
 * The convolutions run "tap-major" on a block: for each tap, one pass over the block adds `coefficient * history`
 * into an accumulator. Each pass is a plain indexed loop with no dependency between frames (the JIT can vectorise it),
 * and the head's symmetry folds the two FIRs onto the sum and difference of the ears ([SpatialPreset]), so a frame
 * costs about 550 multiply-adds instead of the direct form's 900, with no FFT and no added latency.
 *
 * History lives in mirrored rings: every sample at ring position `p < maxBlock` is also written at `p + ringSize`, so a
 * block-long window that starts anywhere in the ring is contiguous. The rings are sized once, from the whole table
 * ([ensureCapacity]); nothing is allocated while processing.
 *
 * Wet/dry: the output is `dry + mix * (wet - dry)`, `dry` being the untouched input delayed by `lowDelay` (§7). A
 * preset change ramps the mix down, swaps the coefficients at fully dry, and ramps up again; bypass ramps down. The
 * dry delay itself only changes where no one hears it (fully wet, or at a [reset]), so a ramp is never a click. Before
 * any preset was ever set the stage is a plain copy (no delay, no work), which is what everyone without a profile gets.
 *
 * Not thread-safe: one instance per audio processor, used on the playback thread only.
 */
internal class SpatialStageKernel(val maxBlock: Int = DEFAULT_MAX_BLOCK) {

    private var ringSize = 0
    private var ringMask = 0
    private var ringS = EMPTY // high band, left + right
    private var ringM = EMPTY // high band, left - right
    private var shortSize = 0
    private var shortMask = 0
    private var lowL = EMPTY
    private var lowR = EMPTY
    private var dryL = EMPTY
    private var dryR = EMPTY

    private val accS = FloatArray(maxBlock) // FIR on the sum: (yL + yR) / 2
    private val accD = FloatArray(maxBlock) // FIR on the difference: (yL - yR) / 2
    private val accL = FloatArray(maxBlock) // sparse room, left ear
    private val accR = FloatArray(maxBlock) // sparse room, right ear

    /** The absolute index of the next input frame (wraps; only its low bits are ever used). */
    private var t = 0

    // Crossover states: stage 1 and 2, left and right; x1, x2 inputs, y1, y2 outputs.
    private var l1x1 = 0.0; private var l1x2 = 0.0; private var l1y1 = 0.0; private var l1y2 = 0.0
    private var l2x1 = 0.0; private var l2x2 = 0.0; private var l2y1 = 0.0; private var l2y2 = 0.0
    private var r1x1 = 0.0; private var r1x2 = 0.0; private var r1y1 = 0.0; private var r1y2 = 0.0
    private var r2x1 = 0.0; private var r2x2 = 0.0; private var r2y1 = 0.0; private var r2y2 = 0.0

    /** The coefficients in use (null: never set since the last [reset]; the stage is a plain copy). */
    private var preset: SpatialPreset? = null
    private var pending: SpatialPreset? = null
    private var wantWet = false

    private var mix = 0f
    private var mixTarget = 0f
    private var defaultStep = 1f
    private var mixStep = 1f
    private var fastUntilSettled = false
    private var dryDelay = 0

    /** Fully wet now (for tests and diagnostics). */
    val isFullyWet: Boolean get() = mix >= 1f

    /** Fully dry now (for tests and diagnostics). */
    val isFullyDry: Boolean get() = mix <= 0f

    /** The delay of the dry path in frames (for tests). */
    val currentDryDelay: Int get() = dryDelay

    /** The preset whose coefficients are in use (for tests). */
    val currentPreset: SpatialPreset? get() = preset

    /** No preset in use or wanted: [process] would copy its input unchanged (no delay, no state). */
    val isIdle: Boolean get() = preset == null && pending == null

    /** Sizes the rings once for every preset of [tables] (the first time a table is there). */
    fun ensureCapacity(tables: SpatialPreviewTables) =
        ensureCapacity(tables.maxSparseDelay, tables.maxTaps, tables.maxLowDelay)

    fun ensureCapacity(maxDelay: Int, maxTaps: Int, maxLowDelay: Int) {
        val ringNeed = nextPowerOfTwo(maxOf(maxDelay, maxTaps - 1, 0) + maxBlock + 1)
        if (ringNeed > ringSize) {
            ringSize = ringNeed
            ringMask = ringNeed - 1
            ringS = FloatArray(ringNeed + maxBlock)
            ringM = FloatArray(ringNeed + maxBlock)
        }
        val shortNeed = nextPowerOfTwo(maxLowDelay + maxBlock + 1)
        if (shortNeed > shortSize) {
            shortSize = shortNeed
            shortMask = shortNeed - 1
            lowL = FloatArray(shortNeed)
            lowR = FloatArray(shortNeed)
            dryL = FloatArray(shortNeed)
            dryR = FloatArray(shortNeed)
        }
    }

    private fun fits(p: SpatialPreset): Boolean =
        p.maxSparseDelay + maxBlock < ringSize && p.taps - 1 + maxBlock < ringSize && p.lowDelay + maxBlock < shortSize

    /** The ramp of a preset change or a bypass, in frames (§7: about 80 ms). */
    fun setRampFrames(frames: Int) {
        defaultStep = 1f / frames.coerceAtLeast(1)
        if (!fastUntilSettled) mixStep = defaultStep
    }

    /**
     * What the stage should play from the next block on: [next]'s coefficients, heard ([wet]) or bypassed. Cheap when
     * nothing changed. A preset whose rings were not sized yet is sized here (once).
     */
    fun setTarget(next: SpatialPreset?, wet: Boolean) {
        if (next != null && !fits(next)) ensureCapacity(next.maxSparseDelay, next.taps, next.lowDelay)
        pending = next
        wantWet = wet && next != null
    }

    /**
     * Back to a zero state (a seek, a new rate, the first stream): empty history, and the mix snapped to where the
     * target says, since the output restarts here anyway. The dry delay is the preset's `lowDelay` (none without one).
     */
    fun reset() {
        if (ringS.isNotEmpty()) {
            Arrays.fill(ringS, 0f)
            Arrays.fill(ringM, 0f)
            Arrays.fill(lowL, 0f)
            Arrays.fill(lowR, 0f)
            Arrays.fill(dryL, 0f)
            Arrays.fill(dryR, 0f)
        }
        l1x1 = 0.0; l1x2 = 0.0; l1y1 = 0.0; l1y2 = 0.0
        l2x1 = 0.0; l2x2 = 0.0; l2y1 = 0.0; l2y2 = 0.0
        r1x1 = 0.0; r1x2 = 0.0; r1y1 = 0.0; r1y2 = 0.0
        r2x1 = 0.0; r2x2 = 0.0; r2y1 = 0.0; r2y2 = 0.0
        t = 0
        val want = pending
        preset = want
        mix = if (wantWet && want != null) 1f else 0f
        mixTarget = mix
        dryDelay = want?.lowDelay ?: 0
        mixStep = defaultStep
        fastUntilSettled = false
    }

    /**
     * An item boundary without a seek (gapless, same rate): the history carries on, so the music does too; a change of
     * target that the new item brings (a render starts: bypass) ramps over [rampFrames] instead of the usual ramp.
     */
    fun boundary(rampFrames: Int) {
        mixStep = 1f / rampFrames.coerceAtLeast(1)
        fastUntilSettled = true
    }

    /** Processes [n] frames (at most [maxBlock]) of planar input into planar output. */
    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
        if (n <= 0) return
        settleTarget()
        val p = preset
        if (p == null) {
            System.arraycopy(inL, 0, outL, 0, n)
            System.arraycopy(inR, 0, outR, 0, n)
            t += n
            return
        }
        writeHistory(inL, inR, n, p)
        val wet = mix > 0f || mixTarget > 0f
        if (wet) {
            convolve(p, n)
            mixWet(outL, outR, n, p)
        } else {
            copyDry(outL, outR, n)
        }
        t += n
        if (mix >= 1f && mixTarget >= 1f) dryDelay = p.lowDelay
        if (fastUntilSettled && mix == mixTarget) {
            fastUntilSettled = false
            mixStep = defaultStep
        }
    }

    private fun settleTarget() {
        val want = pending
        if (wantWet && want != null) {
            if (preset !== want) {
                if (mix <= 0f) {
                    preset = want
                    mixTarget = 1f
                } else {
                    mixTarget = 0f
                }
            } else {
                mixTarget = 1f
            }
        } else {
            mixTarget = 0f
            // While dry the new preset can take over at once; without one the last coefficients stay (the dry delay
            // must not jump while it is heard).
            if (mix <= 0f && want != null && preset !== want) preset = want
        }
    }

    private fun writeHistory(inL: FloatArray, inR: FloatArray, n: Int, p: SpatialPreset) {
        val b0 = p.b0
        val b1 = p.b1
        val b2 = p.b2
        val a1 = p.a1
        val a2 = p.a2
        var l1x1 = l1x1; var l1x2 = l1x2; var l1y1 = l1y1; var l1y2 = l1y2
        var l2x1 = l2x1; var l2x2 = l2x2; var l2y1 = l2y1; var l2y2 = l2y2
        var r1x1 = r1x1; var r1x2 = r1x2; var r1y1 = r1y1; var r1y2 = r1y2
        var r2x1 = r2x1; var r2x2 = r2x2; var r2y1 = r2y1; var r2y2 = r2y2
        val rs = ringS
        val rm = ringM
        val mask = ringMask
        val size = ringSize
        val mirror = maxBlock
        val lowL = lowL
        val lowR = lowR
        val dryL = dryL
        val dryR = dryR
        val shortMask = shortMask
        val start = t
        for (i in 0 until n) {
            val xl = inL[i].toDouble()
            val xr = inR[i].toDouble()
            val l1 = b0 * xl + b1 * l1x1 + b2 * l1x2 - a1 * l1y1 - a2 * l1y2
            l1x2 = l1x1; l1x1 = xl; l1y2 = l1y1; l1y1 = l1
            val l2 = b0 * l1 + b1 * l2x1 + b2 * l2x2 - a1 * l2y1 - a2 * l2y2
            l2x2 = l2x1; l2x1 = l1; l2y2 = l2y1; l2y1 = l2
            val r1 = b0 * xr + b1 * r1x1 + b2 * r1x2 - a1 * r1y1 - a2 * r1y2
            r1x2 = r1x1; r1x1 = xr; r1y2 = r1y1; r1y1 = r1
            val r2 = b0 * r1 + b1 * r2x1 + b2 * r2x2 - a1 * r2y1 - a2 * r2y2
            r2x2 = r2x1; r2x1 = r1; r2y2 = r2y1; r2y1 = r2
            val hl = xl - l2
            val hr = xr - r2
            val sum = (hl + hr).toFloat()
            val dif = (hl - hr).toFloat()
            val a = start + i
            val pos = a and mask
            rs[pos] = sum
            rm[pos] = dif
            if (pos < mirror) {
                rs[pos + size] = sum
                rm[pos + size] = dif
            }
            val q = a and shortMask
            lowL[q] = l2.toFloat()
            lowR[q] = r2.toFloat()
            dryL[q] = inL[i]
            dryR[q] = inR[i]
        }
        this.l1x1 = l1x1; this.l1x2 = l1x2; this.l1y1 = l1y1; this.l1y2 = l1y2
        this.l2x1 = l2x1; this.l2x2 = l2x2; this.l2y1 = l2y1; this.l2y2 = l2y2
        this.r1x1 = r1x1; this.r1x2 = r1x2; this.r1y1 = r1y1; this.r1y2 = r1y2
        this.r2x1 = r2x1; this.r2x2 = r2x2; this.r2y1 = r2y1; this.r2y2 = r2y2
    }

    private fun convolve(p: SpatialPreset, n: Int) {
        val s = accS
        val d = accD
        Arrays.fill(s, 0, n, 0f)
        Arrays.fill(d, 0, n, 0f)
        val rs = ringS
        val rm = ringM
        val mask = ringMask
        val fs = p.firSum
        val fd = p.firDif
        val taps = p.taps
        val start = t
        var k = 0
        // fir(x, h)[n] = sum_k h[k] * x[n - k]: four taps per pass over the block.
        while (k + 3 < taps) {
            val o0 = (start - k) and mask
            val o1 = (start - k - 1) and mask
            val o2 = (start - k - 2) and mask
            val o3 = (start - k - 3) and mask
            val s0 = fs[k]; val s1 = fs[k + 1]; val s2 = fs[k + 2]; val s3 = fs[k + 3]
            val d0 = fd[k]; val d1 = fd[k + 1]; val d2 = fd[k + 2]; val d3 = fd[k + 3]
            for (j in 0 until n) {
                s[j] += s0 * rs[o0 + j] + s1 * rs[o1 + j] + s2 * rs[o2 + j] + s3 * rs[o3 + j]
                d[j] += d0 * rm[o0 + j] + d1 * rm[o1 + j] + d2 * rm[o2 + j] + d3 * rm[o3 + j]
            }
            k += 4
        }
        while (k < taps) {
            val o = (start - k) and mask
            val sk = fs[k]
            val dk = fd[k]
            for (j in 0 until n) {
                s[j] += sk * rs[o + j]
                d[j] += dk * rm[o + j]
            }
            k++
        }
        sparse(p.sparseLDelay, p.sparseLSum, p.sparseLDif, accL, n)
        sparse(p.sparseRDelay, p.sparseRSum, p.sparseRDif, accR, n)
    }

    private fun sparse(delays: IntArray, sums: FloatArray, difs: FloatArray, acc: FloatArray, n: Int) {
        Arrays.fill(acc, 0, n, 0f)
        val rs = ringS
        val rm = ringM
        val mask = ringMask
        val start = t
        val count = delays.size
        var i = 0
        while (i + 1 < count) {
            val o0 = (start - delays[i]) and mask
            val o1 = (start - delays[i + 1]) and mask
            val a0 = sums[i]; val b0 = difs[i]
            val a1 = sums[i + 1]; val b1 = difs[i + 1]
            for (j in 0 until n) {
                acc[j] += a0 * rs[o0 + j] + b0 * rm[o0 + j] + a1 * rs[o1 + j] + b1 * rm[o1 + j]
            }
            i += 2
        }
        if (i < count) {
            val o = (start - delays[i]) and mask
            val a = sums[i]
            val b = difs[i]
            for (j in 0 until n) acc[j] += a * rs[o + j] + b * rm[o + j]
        }
    }

    private fun mixWet(outL: FloatArray, outR: FloatArray, n: Int, p: SpatialPreset) {
        val s = accS
        val d = accD
        val l = accL
        val r = accR
        val lowL = lowL
        val lowR = lowR
        val dryL = dryL
        val dryR = dryR
        val mask = shortMask
        val gain = p.gain
        val lowDelay = p.lowDelay
        val dryDelay = dryDelay
        val start = t
        var m = mix
        val target = mixTarget
        val step = mixStep
        for (j in 0 until n) {
            val a = start + j
            val lq = (a - lowDelay) and mask
            val wl = gain * (s[j] + d[j] + l[j] + lowL[lq])
            val wr = gain * (s[j] - d[j] + r[j] + lowR[lq])
            if (m != target) m = if (m < target) minOf(target, m + step) else maxOf(target, m - step)
            if (m >= 1f) {
                outL[j] = wl
                outR[j] = wr
            } else {
                val dq = (a - dryDelay) and mask
                val dl = dryL[dq]
                val dr = dryR[dq]
                outL[j] = dl + m * (wl - dl)
                outR[j] = dr + m * (wr - dr)
            }
        }
        mix = m
    }

    private fun copyDry(outL: FloatArray, outR: FloatArray, n: Int) {
        val dryL = dryL
        val dryR = dryR
        val mask = shortMask
        val dryDelay = dryDelay
        val start = t
        for (j in 0 until n) {
            val dq = (start + j - dryDelay) and mask
            outL[j] = dryL[dq]
            outR[j] = dryR[dq]
        }
        mix = 0f
    }

    companion object {
        /** Frames per block: the accumulators and one ring window stay in the first-level cache. */
        const val DEFAULT_MAX_BLOCK = 512

        private val EMPTY = FloatArray(0)

        fun nextPowerOfTwo(value: Int): Int {
            var size = 1
            while (size < value) size = size shl 1
            return size
        }
    }
}

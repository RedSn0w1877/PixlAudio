package com.theveloper.pixelplay.data.spatial.playback

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.tanh

/**
 * The maths of the on-device spatial stage (cloud-audio-spec §7, tables v1.1) on planar float blocks, apart from Media3
 * so it runs in JVM unit tests: the crossover (two RBJ low-pass biquads in series, Q = 1/sqrt 2, in double), the high
 * band through the two FIRs, the room's high-pass (one RBJ high-pass biquad) into the sparse room, the low band delayed
 * by `lowDelay`, all times `gain`, then the soft limiter. Without the limiter ([limitOutput] false) it reproduces the
 * worker's reference `spatial/preview.py` `process` from a zero state (the golden test).
 *
 * The convolutions run "tap-major" on a block: for each tap, one pass over the block adds `coefficient * history`
 * into an accumulator. Each pass is a plain indexed loop with no dependency between frames (the JIT can vectorise it),
 * and the head's symmetry folds the two FIRs onto the sum and difference of the ears ([SpatialPreset]), so a frame
 * costs about 550 multiply-adds instead of the direct form's 900, with no FFT and no added latency.
 *
 * History lives in mirrored rings: every sample at ring position `p < maxBlock` is also written at `p + size`, so a
 * block-long window that starts anywhere in the ring is contiguous. The FIRs need only `taps` of history, the room up to
 * its longest delay, so they have a short ring and a long one. The processor sizes the rings once, from the whole table
 * ([ensureCapacity]), before the first preset is used; nothing is allocated while processing. A ring that grows later
 * (a caller that sizes per preset) keeps its history, so even that is never heard.
 *
 * Wet/dry: the output is `dry + mix * (limit(wet) - dry)`, `dry` being the untouched input delayed by `lowDelay` (§7).
 * A preset change ramps the mix down, swaps the coefficients at fully dry, and ramps up again; bypass ramps down. The
 * dry delay itself only changes where no one hears it (fully wet, or at a [reset]), so a ramp is never a click. Before
 * any preset was ever set the stage is a plain copy (no delay, no work), which is what everyone without a profile gets.
 *
 * Not thread-safe: one instance per audio processor, used on the playback thread only.
 */
internal class SpatialStageKernel(
    val maxBlock: Int = DEFAULT_MAX_BLOCK,
    /** §7 Headroom: the soft limiter on the stage's output. Off only to compare with the reference (the golden file). */
    private val limitOutput: Boolean = true,
) {

    // High band (FIR input), as left + right and left - right.
    private var firSize = 0
    private var firMask = 0
    private var firS = EMPTY
    private var firM = EMPTY

    // The room's input (the high band through the room high-pass), as left + right and left - right.
    private var roomSize = 0
    private var roomMask = 0
    private var roomS = EMPTY
    private var roomM = EMPTY

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

    // Room high-pass states, left and right.
    private var lhx1 = 0.0; private var lhx2 = 0.0; private var lhy1 = 0.0; private var lhy2 = 0.0
    private var rhx1 = 0.0; private var rhx2 = 0.0; private var rhy1 = 0.0; private var rhy2 = 0.0

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

    /** Sizes the rings for every preset of [tables]: allocates the first time, a few comparisons after that. */
    fun ensureCapacity(tables: SpatialPreviewTables) =
        ensureCapacity(tables.maxSparseDelay, tables.maxTaps, tables.maxLowDelay)

    /** Makes the rings at least this big. A ring that grows keeps its history (each frame at its absolute index). */
    fun ensureCapacity(maxDelay: Int, maxTaps: Int, maxLowDelay: Int) {
        val firNeed = nextPowerOfTwo(maxOf(maxTaps - 1, 0) + maxBlock + 1)
        if (firNeed > firSize) {
            firS = grown(firS, firSize, firNeed, mirror = maxBlock)
            firM = grown(firM, firSize, firNeed, mirror = maxBlock)
            firSize = firNeed
            firMask = firNeed - 1
        }
        val roomNeed = nextPowerOfTwo(maxOf(maxDelay, 0) + maxBlock + 1)
        if (roomNeed > roomSize) {
            roomS = grown(roomS, roomSize, roomNeed, mirror = maxBlock)
            roomM = grown(roomM, roomSize, roomNeed, mirror = maxBlock)
            roomSize = roomNeed
            roomMask = roomNeed - 1
        }
        val shortNeed = nextPowerOfTwo(maxLowDelay + maxBlock + 1)
        if (shortNeed > shortSize) {
            lowL = grown(lowL, shortSize, shortNeed, mirror = 0)
            lowR = grown(lowR, shortSize, shortNeed, mirror = 0)
            dryL = grown(dryL, shortSize, shortNeed, mirror = 0)
            dryR = grown(dryR, shortSize, shortNeed, mirror = 0)
            shortSize = shortNeed
            shortMask = shortNeed - 1
        }
    }

    /**
     * A ring of [newSize] (plus [mirror]) holding the last [oldSize] frames of [old], each at its absolute index, so a
     * preset that is still playing reads the same history from it (a zeroed ring would drop its room in one sample).
     */
    private fun grown(old: FloatArray, oldSize: Int, newSize: Int, mirror: Int): FloatArray {
        val ring = FloatArray(newSize + mirror)
        val oldMask = oldSize - 1
        val newMask = newSize - 1
        for (back in 1..oldSize) {
            val a = t - back
            val value = old[a and oldMask]
            val q = a and newMask
            ring[q] = value
            if (q < mirror) ring[q + newSize] = value
        }
        return ring
    }

    private fun fits(p: SpatialPreset): Boolean =
        p.taps - 1 + maxBlock < firSize && p.maxSparseDelay + maxBlock < roomSize && p.lowDelay + maxBlock < shortSize

    /** The ramp of a preset change or a bypass, in frames (§7: about 80 ms). */
    fun setRampFrames(frames: Int) {
        defaultStep = 1f / frames.coerceAtLeast(1)
        if (!fastUntilSettled) mixStep = defaultStep
    }

    /**
     * What the stage should play from the next block on: [next]'s coefficients, heard ([wet]) or bypassed. Cheap when
     * nothing changed. A preset the rings are too small for grows them here, keeping the history (the processor sizes
     * them for the whole table first, so this never allocates in the app).
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
        if (firS.isNotEmpty()) {
            Arrays.fill(firS, 0f)
            Arrays.fill(firM, 0f)
            Arrays.fill(roomS, 0f)
            Arrays.fill(roomM, 0f)
            Arrays.fill(lowL, 0f)
            Arrays.fill(lowR, 0f)
            Arrays.fill(dryL, 0f)
            Arrays.fill(dryR, 0f)
        }
        l1x1 = 0.0; l1x2 = 0.0; l1y1 = 0.0; l1y2 = 0.0
        l2x1 = 0.0; l2x2 = 0.0; l2y1 = 0.0; l2y2 = 0.0
        r1x1 = 0.0; r1x2 = 0.0; r1y1 = 0.0; r1y2 = 0.0
        r2x1 = 0.0; r2x2 = 0.0; r2y1 = 0.0; r2y2 = 0.0
        lhx1 = 0.0; lhx2 = 0.0; lhy1 = 0.0; lhy2 = 0.0
        rhx1 = 0.0; rhx2 = 0.0; rhy1 = 0.0; rhy2 = 0.0
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
     * Idle there is nothing to ramp, and [process] (which ends the fast ramp once settled) does not run: the profile
     * picked later in the song must fade in over the usual ramp, not this one.
     */
    fun boundary(rampFrames: Int) {
        if (isIdle) return
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
        val roomHp = p.hasRoomHp
        val hb0 = p.hb0
        val hb1 = p.hb1
        val hb2 = p.hb2
        val ha1 = p.ha1
        val ha2 = p.ha2
        var l1x1 = l1x1; var l1x2 = l1x2; var l1y1 = l1y1; var l1y2 = l1y2
        var l2x1 = l2x1; var l2x2 = l2x2; var l2y1 = l2y1; var l2y2 = l2y2
        var r1x1 = r1x1; var r1x2 = r1x2; var r1y1 = r1y1; var r1y2 = r1y2
        var r2x1 = r2x1; var r2x2 = r2x2; var r2y1 = r2y1; var r2y2 = r2y2
        var lhx1 = lhx1; var lhx2 = lhx2; var lhy1 = lhy1; var lhy2 = lhy2
        var rhx1 = rhx1; var rhx2 = rhx2; var rhy1 = rhy1; var rhy2 = rhy2
        val fs = firS
        val fm = firM
        val firMask = firMask
        val firSize = firSize
        val rs = roomS
        val rm = roomM
        val roomMask = roomMask
        val roomSize = roomSize
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
            val ml: Double
            val mr: Double
            if (roomHp) {
                ml = hb0 * hl + hb1 * lhx1 + hb2 * lhx2 - ha1 * lhy1 - ha2 * lhy2
                lhx2 = lhx1; lhx1 = hl; lhy2 = lhy1; lhy1 = ml
                mr = hb0 * hr + hb1 * rhx1 + hb2 * rhx2 - ha1 * rhy1 - ha2 * rhy2
                rhx2 = rhx1; rhx1 = hr; rhy2 = rhy1; rhy1 = mr
            } else {
                ml = hl
                mr = hr
            }
            val a = start + i
            val fp = a and firMask
            val fSum = (hl + hr).toFloat()
            val fDif = (hl - hr).toFloat()
            fs[fp] = fSum
            fm[fp] = fDif
            if (fp < mirror) {
                fs[fp + firSize] = fSum
                fm[fp + firSize] = fDif
            }
            val rp = a and roomMask
            val rSum = (ml + mr).toFloat()
            val rDif = (ml - mr).toFloat()
            rs[rp] = rSum
            rm[rp] = rDif
            if (rp < mirror) {
                rs[rp + roomSize] = rSum
                rm[rp + roomSize] = rDif
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
        this.lhx1 = lhx1; this.lhx2 = lhx2; this.lhy1 = lhy1; this.lhy2 = lhy2
        this.rhx1 = rhx1; this.rhx2 = rhx2; this.rhy1 = rhy1; this.rhy2 = rhy2
    }

    private fun convolve(p: SpatialPreset, n: Int) {
        val s = accS
        val d = accD
        Arrays.fill(s, 0, n, 0f)
        Arrays.fill(d, 0, n, 0f)
        val fs = firS
        val fm = firM
        val mask = firMask
        val sumTaps = p.firSum
        val difTaps = p.firDif
        val taps = p.taps
        val start = t
        var k = 0
        // fir(x, h)[n] = sum_k h[k] * x[n - k]: four taps per pass over the block.
        while (k + 3 < taps) {
            val o0 = (start - k) and mask
            val o1 = (start - k - 1) and mask
            val o2 = (start - k - 2) and mask
            val o3 = (start - k - 3) and mask
            val s0 = sumTaps[k]; val s1 = sumTaps[k + 1]; val s2 = sumTaps[k + 2]; val s3 = sumTaps[k + 3]
            val d0 = difTaps[k]; val d1 = difTaps[k + 1]; val d2 = difTaps[k + 2]; val d3 = difTaps[k + 3]
            for (j in 0 until n) {
                s[j] += s0 * fs[o0 + j] + s1 * fs[o1 + j] + s2 * fs[o2 + j] + s3 * fs[o3 + j]
                d[j] += d0 * fm[o0 + j] + d1 * fm[o1 + j] + d2 * fm[o2 + j] + d3 * fm[o3 + j]
            }
            k += 4
        }
        while (k < taps) {
            val o = (start - k) and mask
            val sk = sumTaps[k]
            val dk = difTaps[k]
            for (j in 0 until n) {
                s[j] += sk * fs[o + j]
                d[j] += dk * fm[o + j]
            }
            k++
        }
        sparse(p.sparseLDelay, p.sparseLSum, p.sparseLDif, accL, n)
        sparse(p.sparseRDelay, p.sparseRSum, p.sparseRDif, accR, n)
    }

    private fun sparse(delays: IntArray, sums: FloatArray, difs: FloatArray, acc: FloatArray, n: Int) {
        Arrays.fill(acc, 0, n, 0f)
        val rs = roomS
        val rm = roomM
        val mask = roomMask
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
        val limit = limitOutput
        val start = t
        var m = mix
        val target = mixTarget
        val step = mixStep
        for (j in 0 until n) {
            val a = start + j
            val lq = (a - lowDelay) and mask
            var wl = gain * (s[j] + d[j] + l[j] + lowL[lq])
            var wr = gain * (s[j] - d[j] + r[j] + lowR[lq])
            if (limit) {
                wl = softLimit(wl)
                wr = softLimit(wr)
            }
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

        /** §7: the soft limiter's threshold, -1 dBFS. */
        const val LIMIT_THRESHOLD = 0.8912509f

        private val EMPTY = FloatArray(0)

        fun nextPowerOfTwo(value: Int): Int {
            var size = 1
            while (size < value) size = size shl 1
            return size
        }

        /**
         * §7 Headroom, the same function as iOS's `SoftLimiter`: untouched up to -1 dBFS, above it
         * `sign(x) * (t + (1 - t) * tanh((|x| - t) / (1 - t)))`, which never reaches full scale.
         */
        fun softLimit(x: Float): Float {
            val magnitude = abs(x)
            if (magnitude <= LIMIT_THRESHOLD) return x
            val limited = LIMIT_THRESHOLD + (1f - LIMIT_THRESHOLD) * tanh((magnitude - LIMIT_THRESHOLD) / (1f - LIMIT_THRESHOLD))
            return if (x < 0f) -limited else limited
        }
    }
}

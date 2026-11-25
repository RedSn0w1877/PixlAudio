package com.theveloper.pixelplay.brain

/**
 * Tempo and loudness measured on the phone from decoded audio (docs/brain-spec.md, 3.3 "Stage 5", 14 "Stage 5").
 *
 * The platform decodes a short window of a track (30-45 s from 20% in) to 16-bit PCM and feeds it here, block by block. Nothing is
 * kept but a few small arrays sized from the window, allocated once per [Estimator]: the decoder loop allocates nothing per block.
 *
 * Pure and deterministic: `+ - * /`, `sqrt` and the functions of [BrainMath] only, sums in index order, so the Swift port gives the
 * same bits from the same samples (`audiofeatures.jsonl`). THE EXPRESSION ORDER IN THIS FILE IS THE DEFINITION.
 *
 * **Tempo.** Three-band onset strength (one-pole splits at 200 Hz and 3 kHz; per band the log energy of each 5 ms hop, half-wave
 * rectified difference, summed with the low band counted twice), with its mean removed; the unbiased autocorrelation of that curve; a comb score per candidate tempo
 * (the autocorrelation at 1-4 beat lags, minus a part of it at the off-beat lags, which tells 100 from 200 BPM on a plain click, and at
 * the thirds of a beat, which keeps 100 from being read off music at 150);
 * a log-Gaussian preference around [PRIOR_BPM] that settles the octave when the comb cannot; then a fine search around the winner with
 * eight beat lags. Confidence (0..1000) is how far the winner's comb stands above the mean comb of the whole range.
 *
 * **Loudness.** ITU-R BS.1770 / EBU R128 integrated loudness, approximately: the K-weighting filter (the two biquads of BS.1770,
 * designed for the actual sample rate with the formulas pyloudnorm uses), mean square per 100 ms, 400 ms blocks with 75% overlap,
 * the absolute gate at -70 LUFS and the relative gate 10 LU below. Every channel weighs 1 (no surround weights: the decoder hands
 * mono or stereo). ReplayGain 2 would be `-18 - LUFS`; the brain stores LUFS, which runs in the same direction as Deezer's `gain`.
 */
object AudioFeatures {

    /** Bumped when anything here changes a result: cached measurements of an older version are measured again. */
    const val VERSION = 1

    /** Where the window starts, as a share of the track, and how long it is. */
    const val WINDOW_START_PERMILLE = 200
    const val WINDOW_MS = 40_000
    /** Shorter than this and the tempo is not trusted (a track of 50 s still gets a window). */
    const val MIN_TEMPO_MS = 12_000
    /** Loudness needs at least one gated block. */
    const val MIN_LOUDNESS_MS = 3_000

    /** A measured tempo replaces Deezer's from this confidence up (`DnaAssembler`, `AudioMeasureStore`). */
    const val TRUST_CONF = 550

    /** Below this a measured tempo is not kept at all (a held tone, noise). */
    const val WEAK_CONF = 250

    const val MIN_BPM = 50.0
    const val MAX_BPM = 220.0
    const val PRIOR_BPM = 120.0
    /** Width of the octave preference in octaves. */
    const val PRIOR_OCTAVES = 1.1

    /** Hops per second (the hop is `sampleRate / HOP_RATE` samples, integer division). */
    const val HOP_RATE = 200

    /** No loudness (silence, or too short). Same value as [TrackDna.GAIN_UNKNOWN]. */
    const val LOUDNESS_UNKNOWN = TrackDna.GAIN_UNKNOWN

    private val PI: Double = Double.fromBits(0x400921FB54442D18L)
    private val LN10: Double = BrainMath.ln(10.0)

    class Result(
        /** Tempo x 10; 0 when not measured. */
        val bpmX10: Int,
        /** 0..1000. */
        val tempoConf: Int,
        /** Integrated loudness in LUFS x 10, or [LOUDNESS_UNKNOWN]. */
        val lufsX10: Int,
        /** How much audio was analysed. */
        val analysedMs: Int,
        /** The standard deviation of the onset curve x 1000 (how much rhythm there is to read); 0 when not measured. */
        val onsetX1000: Int = 0,
        val version: Int = VERSION,
    ) {
        val tempoTrusted: Boolean get() = bpmX10 > 0 && tempoConf >= TRUST_CONF
        val loudnessKnown: Boolean get() = lufsX10 != LOUDNESS_UNKNOWN
    }

    /** `tan(x)` for `0 <= x < 0.8` by its Taylor series (the K-filter needs `tan(pi f / fs)`, at most 0.13 at 40 kHz). */
    fun tanSmall(x: Double): Double {
        val x2 = x * x
        // tan x = x + x^3/3 + 2x^5/15 + 17x^7/315 + 62x^9/2835 + 1382x^11/155925 + 21844x^13/6081075 + 929569x^15/638512875
        val p = 1.0 + x2 * (T3 + x2 * (T5 + x2 * (T7 + x2 * (T9 + x2 * (T11 + x2 * (T13 + x2 * T15))))))
        return x * p
    }

    private val T3 = 1.0 / 3.0
    private val T5 = 2.0 / 15.0
    private val T7 = 17.0 / 315.0
    private val T9 = 62.0 / 2835.0
    private val T11 = 1382.0 / 155925.0
    private val T13 = 21844.0 / 6081075.0
    private val T15 = 929569.0 / 638512875.0

    /** 10^(x/10) and 10 log10(x), through [BrainMath]. */
    private fun db10(x: Double): Double = 10.0 * (BrainMath.ln(x) / LN10)
    private fun undb20(db: Double): Double = BrainMath.exp(db / 20.0 * LN10)

    /** One biquad, direct form I, with its state. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        fun run(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    /** The BS.1770 pre-filter (high shelf, +4 dB above about 1.7 kHz). */
    private fun shelf(sampleRate: Int): Biquad {
        val g = 3.99984385397
        val q = 0.7071752369554193
        val fc = 1681.974450955533
        val k = tanSmall(PI * fc / sampleRate.toDouble())
        val vh = undb20(g)
        val vb = BrainMath.exp(0.499666774155 * BrainMath.ln(vh))
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            b0 = (vh + vb * k / q + k * k) / a0,
            b1 = 2.0 * (k * k - vh) / a0,
            b2 = (vh - vb * k / q + k * k) / a0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / q + k * k) / a0,
        )
    }

    /** The BS.1770 RLB high-pass (about 38 Hz). */
    private fun highPass(sampleRate: Int): Biquad {
        val q = 0.5003270373253953
        val fc = 38.13547087613982
        val k = tanSmall(PI * fc / sampleRate.toDouble())
        val a0 = 1.0 + k / q + k * k
        return Biquad(b0 = 1.0, b1 = -2.0, b2 = 1.0, a1 = 2.0 * (k * k - 1.0) / a0, a2 = (1.0 - k / q + k * k) / a0)
    }

    /**
     * Streams PCM in, one result out. [sampleRate] 8,000 to 192,000, [channels] 1 or more (interleaved). At most [maxMs] of audio is
     * read; the rest is ignored. Not thread-safe; one per window.
     */
    class Estimator(val sampleRate: Int, val channels: Int, maxMs: Int = WINDOW_MS + 5_000) {
        private val ok = sampleRate in 8_000..192_000 && channels >= 1
        private val hop = if (ok) sampleRate / HOP_RATE else 1
        private val frameRate = if (ok) sampleRate.toDouble() / hop.toDouble() else 1.0
        private val maxFrames = if (ok) ((maxMs.toLong() * sampleRate) / 1000L).toInt() else 0
        private val maxHops = maxFrames / hop + 1
        private val sub = if (ok) sampleRate / 10 else 1
        private val maxSubs = maxFrames / sub + 1

        // One-pole band splits.
        private val aLow = 1.0 - BrainMath.exp(-2.0 * PI * 200.0 / sampleRate.toDouble())
        private val aMid = 1.0 - BrainMath.exp(-2.0 * PI * 3000.0 / sampleRate.toDouble())
        private var lp1 = 0.0
        private var lp2 = 0.0
        private var eLow = 0.0
        private var eMid = 0.0
        private var eHigh = 0.0
        private var inHop = 0
        private var prevLow = 0.0
        private var prevMid = 0.0
        private var prevHigh = 0.0
        private var hops = 0
        private val flux = DoubleArray(maxHops)

        // K-weighting per channel, and the 100 ms mean squares.
        private val shelves = Array(if (ok) channels else 0) { shelf(sampleRate) }
        private val highs = Array(if (ok) channels else 0) { highPass(sampleRate) }
        private var subSum = 0.0
        private var inSub = 0
        private var subs = 0
        private val subPower = DoubleArray(maxSubs)

        private var frames = 0

        /** Interleaved 16-bit samples: [count] frames from [offset] (in samples). */
        fun feed(pcm: ShortArray, offset: Int, count: Int) {
            if (!ok) return
            var i = offset
            var f = 0
            while (f < count && frames < maxFrames) {
                var mono = 0.0
                var c = 0
                var power = 0.0
                while (c < channels) {
                    val x = pcm[i + c].toDouble() / 32768.0
                    mono += x
                    val y = highs[c].run(shelves[c].run(x))
                    power += y * y
                    c++
                }
                sample(mono / channels.toDouble(), power)
                i += channels
                f++
            }
        }

        /** Interleaved samples in [-1, 1]. */
        fun feed(pcm: FloatArray, offset: Int, count: Int) {
            if (!ok) return
            var i = offset
            var f = 0
            while (f < count && frames < maxFrames) {
                var mono = 0.0
                var c = 0
                var power = 0.0
                while (c < channels) {
                    val x = pcm[i + c].toDouble()
                    mono += x
                    val y = highs[c].run(shelves[c].run(x))
                    power += y * y
                    c++
                }
                sample(mono / channels.toDouble(), power)
                i += channels
                f++
            }
        }

        /** True once [maxMs] has been read: the caller may stop decoding. */
        val full: Boolean get() = frames >= maxFrames

        private fun sample(m: Double, power: Double) {
            frames++
            lp1 += aLow * (m - lp1)
            lp2 += aMid * (m - lp2)
            val low = lp1
            val mid = lp2 - lp1
            val high = m - lp2
            eLow += low * low
            eMid += mid * mid
            eHigh += high * high
            inHop++
            if (inHop == hop) {
                val n = hop.toDouble()
                val l = BrainMath.ln(eLow / n + FLOOR)
                val md = BrainMath.ln(eMid / n + FLOOR)
                val h = BrainMath.ln(eHigh / n + FLOOR)
                if (hops > 0 && hops < flux.size) {
                    var v = 0.0
                    if (l > prevLow) v += LOW_WEIGHT * (l - prevLow)
                    if (md > prevMid) v += md - prevMid
                    if (h > prevHigh) v += h - prevHigh
                    flux[hops] = v
                }
                prevLow = l; prevMid = md; prevHigh = h
                if (hops < flux.size) hops++
                eLow = 0.0; eMid = 0.0; eHigh = 0.0
                inHop = 0
            }
            subSum += power
            inSub++
            if (inSub == sub) {
                if (subs < subPower.size) {
                    subPower[subs] = subSum / sub.toDouble()
                    subs++
                }
                subSum = 0.0
                inSub = 0
            }
        }

        fun finish(): Result {
            val analysedMs = if (ok) ((frames.toLong() * 1000L) / sampleRate).toInt() else 0
            val lufs = if (analysedMs >= MIN_LOUDNESS_MS) loudness() else LOUDNESS_UNKNOWN
            var bpmX10 = 0
            var conf = 0
            var onset = 0
            if (analysedMs >= MIN_TEMPO_MS) {
                val t = tempo()
                bpmX10 = t[0]
                conf = t[1]
                onset = t[2]
            }
            return Result(bpmX10, conf, lufs, analysedMs, onset)
        }

        // ── Loudness ─────────────────────────────────────────────────────────

        private fun loudness(): Int {
            if (subs < 4) return LOUDNESS_UNKNOWN
            val blocks = subs - 3
            val z = DoubleArray(blocks)
            for (b in 0 until blocks) z[b] = (subPower[b] + subPower[b + 1] + subPower[b + 2] + subPower[b + 3]) / 4.0
            // Absolute gate: -70 LUFS, i.e. z > 10^((-70 + 0.691) / 10).
            val absGate = BrainMath.exp((-70.0 + 0.691) / 10.0 * LN10)
            var sum = 0.0
            var n = 0
            for (b in 0 until blocks) if (z[b] > absGate) { sum += z[b]; n++ }
            if (n == 0) return LOUDNESS_UNKNOWN
            val ungated = -0.691 + db10(sum / n.toDouble())
            val relGate = BrainMath.exp((ungated - 10.0 + 0.691) / 10.0 * LN10)
            var sum2 = 0.0
            var n2 = 0
            for (b in 0 until blocks) if (z[b] > absGate && z[b] > relGate) { sum2 += z[b]; n2++ }
            if (n2 == 0) return LOUDNESS_UNKNOWN
            val lufs = -0.691 + db10(sum2 / n2.toDouble())
            return BrainMath.floorToLong(BrainMath.clip(lufs, -99.0, 9.0) * 10.0 + 0.5).toInt()
        }

        // ── Tempo ────────────────────────────────────────────────────────────

        /** `[bpmX10, conf]`. */
        private fun tempo(): IntArray {
            val n = hops
            if (n < 64) return intArrayOf(0, 0, 0)
            // The onset curve without its mean.
            var mean = 0.0
            for (t in 1 until n) mean += flux[t]
            mean /= (n - 1).toDouble()
            val o = DoubleArray(n)
            for (t in 1 until n) o[t] = flux[t] - mean
            // Autocorrelation up to eight beats at the slowest tempo the fine search reads.
            val maxLag = minOf(n - 32, (8.0 * 60.0 * frameRate / (MIN_BPM - 2.0)).toInt() + 2)
            if (maxLag < (60.0 * frameRate / MIN_BPM).toInt() + 2) return intArrayOf(0, 0, 0)
            val r = DoubleArray(maxLag + 1)
            for (lag in 0..maxLag) {
                var s = 0.0
                var t = 1
                val end = n - lag
                while (t < end) {
                    s += o[t] * o[t + lag]
                    t++
                }
                r[lag] = s / (end - 1).toDouble()
            }
            val r0 = r[0]
            if (!(r0 > 0.0)) return intArrayOf(0, 0, 0)

            // Coarse: every half BPM, four beats, minus half the off-beats, times the octave preference.
            var best = 0.0
            var bestScore = 0.0
            var first = true
            var combSum = 0.0
            var combN = 0
            var bestComb = 0.0
            var b = MIN_BPM
            while (b <= MAX_BPM) {
                val c = comb(r, maxLag, 60.0 * frameRate / b, 4) / r0
                val s = c * prior(b)
                combSum += c
                combN++
                if (first || s > bestScore) {
                    best = b
                    bestScore = s
                    bestComb = c
                    first = false
                }
                b += 0.5
            }
            if (bestComb <= 0.0) return intArrayOf(0, 0, 0)

            // Fine: +-1.5 BPM every 0.02 with eight beats, no preference (the octave is settled).
            var fine = best
            var fineScore = 0.0
            first = true
            var k = -75
            while (k <= 75) {
                val bb = best + k.toDouble() * 0.02
                if (bb >= MIN_BPM - 2.0) {
                    val c = comb(r, maxLag, 60.0 * frameRate / bb, 8)
                    if (first || c > fineScore) {
                        fine = bb
                        fineScore = c
                        first = false
                    }
                }
                k++
            }
            val meanComb = combSum / combN.toDouble()
            // A curve with almost no onsets (a held tone, a drone) has an autocorrelation of rounding noise: nothing to trust.
            val strength = BrainMath.sqrt(r0)
            val conf = if (strength < ONSET_FLOOR) 0.0 else BrainMath.clip((bestComb - meanComb) * 2.5, 0.0, 1.0)
            return intArrayOf(
                BrainMath.floorToLong(fine * 10.0 + 0.5).toInt(),
                BrainMath.floorToLong(conf * 1000.0 + 0.5).toInt(),
                BrainMath.floorToLong(BrainMath.clip(strength, 0.0, 1000.0) * 1000.0 + 0.5).toInt(),
            )
        }
    }

    private const val FLOOR = 1e-9

    /** The low band (kick, bass) carries the beat; the others carry subdivisions too. */
    const val LOW_WEIGHT = 4.0

    /** How much the off-beat lags count against a tempo (tells 100 from 200 on a click without doubling music that has eighths). */
    const val OFFBEAT_W = 0.35

    /** How much the lags at a third and two thirds of a beat count against it. */
    const val THIRD_W = 0.3
    private val THIRD_1 = 1.0 / 3.0
    private val THIRD_2 = 2.0 / 3.0

    /** Below this onset strength (standard deviation of the onset curve) the tempo has no confidence. */
    const val ONSET_FLOOR = 0.05

    /** The autocorrelation at a fractional lag, by linear interpolation; 0 past the end. */
    private fun at(r: DoubleArray, maxLag: Int, lag: Double): Double {
        if (lag < 0.0) return 0.0
        val i = BrainMath.floorToLong(lag).toInt()
        if (i + 1 > maxLag) return 0.0
        val f = lag - i.toDouble()
        return r[i] * (1.0 - f) + r[i + 1] * f
    }

    /**
     * Mean of the autocorrelation at 1..[beats] beat lags, minus [OFFBEAT_W] times the mean at the off-beat lags between them, minus
     * [THIRD_W] times the mean at the thirds of a beat (a "beat" made of three of the real pulses: 100 read off music at 150).
     */
    private fun comb(r: DoubleArray, maxLag: Int, period: Double, beats: Int): Double {
        var on = 0.0
        var off = 0.0
        var third = 0.0
        var k = 1
        while (k <= beats) {
            val kd = k.toDouble()
            on += at(r, maxLag, period * kd)
            off += at(r, maxLag, period * (kd - 0.5))
            third += at(r, maxLag, period * (kd - THIRD_1)) + at(r, maxLag, period * (kd - THIRD_2))
            k++
        }
        return (on - OFFBEAT_W * off - THIRD_W * 0.5 * third) / beats.toDouble()
    }

    /** `exp(-0.5 (log2(b / PRIOR_BPM) / PRIOR_OCTAVES)^2)`. */
    private fun prior(bpm: Double): Double {
        val o = BrainMath.log2(bpm / PRIOR_BPM) / PRIOR_OCTAVES
        return BrainMath.exp(-0.5 * o * o)
    }

    /** Where the decode window starts for a track of [durationMs]: 20% in, but never so late that it runs off the end. */
    fun windowStartMs(durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val start = durationMs * WINDOW_START_PERMILLE / 1000L
        val latest = durationMs - WINDOW_MS.toLong()
        return if (latest <= 0L) 0L else minOf(start, latest)
    }

    /**
     * The tempo the DNA keeps (3.2 `tempoBpmX10`, `tempoSource`): a trusted measurement first, then Deezer's, then an untrusted
     * measurement rather than nothing. Returns `[bpmX10, source]`.
     */
    fun chooseTempo(deezerBpmX10: Int, measured: Result?): IntArray {
        if (measured != null && measured.tempoTrusted) return intArrayOf(measured.bpmX10, TrackDna.TEMPO_ON_DEVICE)
        if (deezerBpmX10 > 0) return intArrayOf(deezerBpmX10, TrackDna.TEMPO_DEEZER)
        if (measured != null && measured.bpmX10 > 0 && measured.tempoConf >= WEAK_CONF) return intArrayOf(measured.bpmX10, TrackDna.TEMPO_ON_DEVICE_WEAK)
        return intArrayOf(0, TrackDna.TEMPO_UNKNOWN)
    }

    /** The loudness the DNA keeps: Deezer's `gain` when it has one (498 of 500), the measurement otherwise. `[gainDbX10, measured 0/1]`. */
    fun chooseGain(deezerGainX10: Int, measured: Result?): IntArray {
        if (deezerGainX10 != TrackDna.GAIN_UNKNOWN) return intArrayOf(deezerGainX10, 0)
        if (measured != null && measured.loudnessKnown) return intArrayOf(measured.lufsX10, 1)
        return intArrayOf(TrackDna.GAIN_UNKNOWN, 0)
    }
}

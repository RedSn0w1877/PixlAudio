package com.theveloper.pixelplay.brain

/**
 * Synthetic PCM for the stage 5 estimator (`audiofeatures.jsonl`): click tracks and drum grooves at known tempos, sine tones at
 * known levels, and noise. Deterministic and made of `+ - * /` and SplitMix64 only, so the Swift tests rebuild the same samples
 * from the same parameters (each golden line carries the FNV-1a 64 of the 16-bit little-endian bytes, to check the synth first).
 *
 * THE EXPRESSION ORDER HERE IS THE DEFINITION OF THE FIXTURES, like in the core.
 */
object AudioFixtures {

    const val KIND_CLICKS = "clicks"
    const val KIND_GROOVE = "groove"
    const val KIND_TONE = "tone"
    const val KIND_NOISE = "noise"

    class Spec(
        val kind: String,
        val sampleRate: Int,
        val channels: Int,
        val seconds: Int,
        /** Clicks and grooves: tempo x 10. Tone: frequency in Hz. */
        val param: Int,
        /** Peak level in dBFS x 10 (a tone's amplitude; a click's or a drum's peak). */
        val levelDbX10: Int,
        val seed: Long,
    ) {
        val name: String get() = "$kind:$param:$levelDbX10:$sampleRate:$channels:$seconds:$seed"
    }

    private val PI: Double = Double.fromBits(0x400921FB54442D18L)

    /** `sin x` and `cos x` for |x| <= pi by Taylor series to x^21 (fixtures only). */
    fun sinT(x: Double): Double {
        val x2 = x * x
        var term = x
        var sum = x
        var k = 1
        while (k <= 10) {
            term = -term * x2 / ((2 * k).toDouble() * (2 * k + 1).toDouble())
            sum += term
            k++
        }
        return sum
    }

    fun cosT(x: Double): Double {
        val x2 = x * x
        var term = 1.0
        var sum = 1.0
        var k = 1
        while (k <= 10) {
            term = -term * x2 / ((2 * k - 1).toDouble() * (2 * k).toDouble())
            sum += term
            k++
        }
        return sum
    }

    /** `10^(db / 20)` through [BrainMath]. */
    fun amplitude(dbX10: Int): Double = BrainMath.exp(dbX10.toDouble() / 200.0 * BrainMath.ln(10.0))

    /** Interleaved 16-bit samples: `x * 32767`, rounded half up, clipped to +-32767. */
    fun render(spec: Spec): ShortArray {
        val frames = spec.sampleRate * spec.seconds
        val mono = DoubleArray(frames)
        val rng = BrainHash.Rng(spec.seed)
        val amp = amplitude(spec.levelDbX10)
        val fs = spec.sampleRate.toDouble()
        // A faint noise floor under the rhythmic fixtures (-60 dBFS), as real recordings have.
        val floor = amplitude(-600)
        when (spec.kind) {
            KIND_CLICKS -> {
                for (n in 0 until frames) mono[n] = floor * (rng.uniform() * 2.0 - 1.0)
                val period = 60.0 * fs * 10.0 / spec.param.toDouble()
                val len = spec.sampleRate / 100 // 10 ms bursts
                var beat = 0
                while (true) {
                    val start = BrainMath.floorToLong(beat.toDouble() * period + 0.5).toInt()
                    if (start >= frames) break
                    var env = amp
                    var i = 0
                    while (i < len && start + i < frames) {
                        mono[start + i] += env * (rng.uniform() * 2.0 - 1.0)
                        env *= 0.997
                        i++
                    }
                    beat++
                }
            }
            KIND_GROOVE -> {
                for (n in 0 until frames) mono[n] = floor * (rng.uniform() * 2.0 - 1.0)
                val period = 60.0 * fs * 10.0 / spec.param.toDouble()
                // Kick: a 60 Hz sine burst on every beat, 120 ms. Snare: noise on 2 and 4, 80 ms. Hats: high noise on every eighth, 20 ms.
                val w = 2.0 * PI * 60.0 / fs
                val c2 = 2.0 * cosT(w)
                val s1 = sinT(w)
                var eighth = 0
                while (true) {
                    val start = BrainMath.floorToLong(eighth.toDouble() * period * 0.5 + 0.5).toInt()
                    if (start >= frames) break
                    val onBeat = eighth % 2 == 0
                    if (onBeat) {
                        val len = spec.sampleRate * 12 / 100
                        var y1 = 0.0
                        var y2 = 0.0
                        var env = amp
                        var i = 0
                        while (i < len && start + i < frames) {
                            val y = if (i == 0) 0.0 else if (i == 1) s1 else c2 * y1 - y2
                            mono[start + i] += env * y
                            y2 = y1; y1 = y
                            env *= 0.9995
                            i++
                        }
                        if ((eighth / 2) % 2 == 1) {
                            val sl = spec.sampleRate * 8 / 100
                            var e = amp * 0.6
                            var j = 0
                            while (j < sl && start + j < frames) {
                                mono[start + j] += e * (rng.uniform() * 2.0 - 1.0)
                                e *= 0.9993
                                j++
                            }
                        }
                    }
                    val hl = spec.sampleRate / 50
                    var e = amp * 0.25
                    var prev = 0.0
                    var j = 0
                    while (j < hl && start + j < frames) {
                        val v = rng.uniform() * 2.0 - 1.0
                        mono[start + j] += e * (v - prev) * 0.5
                        prev = v
                        e *= 0.996
                        j++
                    }
                    eighth++
                }
            }
            KIND_TONE -> {
                val w = 2.0 * PI * spec.param.toDouble() / fs
                val c2 = 2.0 * cosT(w)
                var y1 = 0.0
                var y2 = 0.0
                for (n in 0 until frames) {
                    val y = if (n == 0) 0.0 else if (n == 1) sinT(w) else c2 * y1 - y2
                    mono[n] = amp * y
                    y2 = y1; y1 = y
                }
            }
            KIND_NOISE -> for (n in 0 until frames) mono[n] = amp * (rng.uniform() * 2.0 - 1.0)
        }
        val out = ShortArray(frames * spec.channels)
        var k = 0
        for (n in 0 until frames) {
            var q = BrainMath.floorToLong(mono[n] * 32767.0 + 0.5)
            if (q > 32767L) q = 32767L
            if (q < -32767L) q = -32767L
            for (c in 0 until spec.channels) out[k++] = q.toShort()
        }
        return out
    }

    /** FNV-1a 64 over the samples as 16-bit little-endian bytes. */
    fun hash(pcm: ShortArray): Long {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return BrainHash.fnv1a64(bytes)
    }

    /** Runs the estimator on [pcm] in blocks of 4,096 frames, the way the app's decoder hands them over. */
    fun estimate(spec: Spec, pcm: ShortArray): AudioFeatures.Result {
        val est = AudioFeatures.Estimator(spec.sampleRate, spec.channels)
        val frames = pcm.size / spec.channels
        var f = 0
        while (f < frames) {
            val n = minOf(4096, frames - f)
            est.feed(pcm, f * spec.channels, n)
            f += n
        }
        return est.finish()
    }

    /** The fixture set of the golden file and of the accuracy test. */
    fun specs(): List<Spec> {
        val out = ArrayList<Spec>()
        var seed = 1L
        for (bpm in intArrayOf(600, 750, 900, 1000, 1200, 1280, 1400, 1600, 1740)) {
            out.add(Spec(KIND_CLICKS, 44_100, 2, 40, bpm, -60, seed++))
        }
        out.add(Spec(KIND_CLICKS, 48_000, 1, 40, 1235, -120, seed++))
        for (bpm in intArrayOf(850, 1000, 1200, 1280, 1500)) {
            out.add(Spec(KIND_GROOVE, 44_100, 2, 40, bpm, -60, seed++))
        }
        out.add(Spec(KIND_GROOVE, 48_000, 2, 30, 965, -90, seed++))
        // EBU R128: a stereo 1 kHz sine at -23 dBFS reads -23 LUFS; the K-filter barely touches 1 kHz.
        out.add(Spec(KIND_TONE, 48_000, 2, 20, 1000, -230, seed++))
        out.add(Spec(KIND_TONE, 44_100, 2, 20, 1000, -180, seed++))
        out.add(Spec(KIND_TONE, 44_100, 2, 20, 1000, -100, seed++))
        out.add(Spec(KIND_TONE, 48_000, 1, 20, 1000, -230, seed++))
        out.add(Spec(KIND_TONE, 44_100, 2, 20, 100, -200, seed++))
        out.add(Spec(KIND_TONE, 44_100, 2, 20, 5000, -200, seed++))
        out.add(Spec(KIND_NOISE, 44_100, 2, 30, 0, -200, seed++))
        out.add(Spec(KIND_NOISE, 44_100, 2, 2, 0, -200, seed++))
        return out
    }
}

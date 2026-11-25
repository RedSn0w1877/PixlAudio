package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The stage 5 estimator on the synthetic fixtures ([AudioFixtures]): tempo within 1% (octave errors counted apart), confidence high
 * on rhythm and low on noise, loudness within 0.5 LU of the BS.1770 value, and nothing measured from too little audio.
 */
class AudioFeaturesTest {

    private fun expectedLufs(spec: AudioFixtures.Spec): Double {
        // A sine of peak A has mean square A^2 / 2 per channel; channels add; -0.691 + the K-filter's gain at 1 kHz (about +0.69 dB).
        return spec.levelDbX10 / 10.0 - 3.0103 + 10.0 * Math.log10(spec.channels.toDouble())
    }

    @Test
    fun `accuracy on the fixtures`() {
        val report = StringBuilder("\nkind      truth    measured  conf   lufs\n")
        var tempoN = 0
        var tempoOk = 0
        var octave = 0
        val failures = ArrayList<String>()
        fun check(ok: Boolean, why: String) { if (!ok) failures.add(why) }
        for (spec in AudioFixtures.specs()) {
            val r = AudioFixtures.estimate(spec, AudioFixtures.render(spec))
            report.append(String.format("%-8s %7.1f %9.1f %5d %6.1f\n", spec.kind, spec.param / 10.0, r.bpmX10 / 10.0, r.tempoConf,
                if (r.loudnessKnown) r.lufsX10 / 10.0 else Double.NaN, r.onsetX1000))
            when (spec.kind) {
                AudioFixtures.KIND_CLICKS, AudioFixtures.KIND_GROOVE -> {
                    tempoN++
                    val truth = spec.param / 10.0
                    val got = r.bpmX10 / 10.0
                    if (Math.abs(got - truth) <= truth * 0.01) tempoOk++
                    else if (Math.abs(got - truth * 2) <= truth * 0.02 || Math.abs(got - truth / 2) <= truth * 0.005) octave++
                    check(r.tempoConf >= AudioFeatures.TRUST_CONF, "${spec.name}: confidence ${r.tempoConf}")
                }
                AudioFixtures.KIND_TONE -> {
                    if (spec.param == 1000) {
                        val want = expectedLufs(spec)
                        check(Math.abs(r.lufsX10 / 10.0 - want) <= 0.5, "${spec.name}: ${r.lufsX10 / 10.0} LUFS, want $want")
                    }
                    check(!r.tempoTrusted, "${spec.name}: a steady tone has no tempo to trust (${r.tempoConf})")
                }
                AudioFixtures.KIND_NOISE -> {
                    if (spec.seconds < 3) {
                        assertEquals(0, r.bpmX10)
                        assertEquals(AudioFeatures.LOUDNESS_UNKNOWN, r.lufsX10)
                    } else {
                        check(!r.tempoTrusted, "${spec.name}: noise has no tempo to trust (${r.tempoConf})")
                    }
                }
            }
        }
        report.append("tempo within 1%: $tempoOk / $tempoN, octave errors: $octave\n")
        println(report)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertEquals(tempoN, tempoOk + octave, "every rhythm fixture is within 1% or an octave off")
        assertTrue(tempoOk >= tempoN - 2, "at most two octave errors")
    }

    @Test
    fun `the result does not depend on how the samples are split into blocks`() {
        val spec = AudioFixtures.specs()[4]
        val pcm = AudioFixtures.render(spec)
        val whole = AudioFeatures.Estimator(spec.sampleRate, spec.channels).also { it.feed(pcm, 0, pcm.size / spec.channels) }.finish()
        val split = AudioFixtures.estimate(spec, pcm)
        assertEquals(whole.bpmX10, split.bpmX10)
        assertEquals(whole.tempoConf, split.tempoConf)
        assertEquals(whole.lufsX10, split.lufsX10)
    }

    @Test
    fun `tanSmall matches tan on the K-filter's range`() {
        var x = 0.0
        while (x < 0.15) {
            assertTrue(Math.abs(AudioFeatures.tanSmall(x) - Math.tan(x)) < 1e-12, "x = $x")
            x += 0.01
        }
    }

    @Test
    fun `a trusted measurement beats Deezer, Deezer beats an untrusted one`() {
        val strong = AudioFeatures.Result(1280, 800, -90, 40_000)
        val weak = AudioFeatures.Result(1280, 300, -90, 40_000)
        assertEquals(listOf(1280, TrackDna.TEMPO_ON_DEVICE), AudioFeatures.chooseTempo(1200, strong).toList())
        assertEquals(listOf(1200, TrackDna.TEMPO_DEEZER), AudioFeatures.chooseTempo(1200, weak).toList())
        assertEquals(listOf(1280, TrackDna.TEMPO_ON_DEVICE_WEAK), AudioFeatures.chooseTempo(0, weak).toList())
        assertEquals(listOf(0, TrackDna.TEMPO_UNKNOWN), AudioFeatures.chooseTempo(0, null).toList())
        assertEquals(listOf(-75, 0), AudioFeatures.chooseGain(-75, strong).toList())
        assertEquals(listOf(-90, 1), AudioFeatures.chooseGain(TrackDna.GAIN_UNKNOWN, strong).toList())
    }

    @Test
    fun `the window starts 20 percent in and never runs off the end`() {
        assertEquals(60_000L, AudioFeatures.windowStartMs(300_000L))
        assertEquals(10_000L, AudioFeatures.windowStartMs(50_000L))
        assertEquals(0L, AudioFeatures.windowStartMs(30_000L))
        assertEquals(0L, AudioFeatures.windowStartMs(0L))
    }
}

package com.theveloper.pixelplay.data.haptics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A small onset detector for music haptics. It runs on the audio thread, so it never allocates:
 * all state is primitive fields, input is read straight from the PCM buffer, and results leave
 * through [Listener] (primitive arguments, no boxing).
 *
 * Method: the mono signal is split with two one-pole filters into a LOW band (below ~140 Hz, where
 * kick drums and bass live) and a HIGH band (above ~1.5 kHz, where snares, claps and hats live).
 * Every ~10 ms hop the RMS energy of each band is compared with its own slow running average; an
 * onset is a hop whose energy jumps well above that average (a band-wise spectral-flux test: the
 * positive energy change relative to what the band normally holds). A kick must also be mostly
 * low-band energy and a snare mostly high-band energy, so a noise burst is never taken for a kick.
 * The smoothed low-band level is reported every ~50 ms as the "bass" level for the rumble.
 */
class BeatDetector(private val listener: Listener) {

    interface Listener {
        fun onKick(strength: Float)
        fun onSnare(strength: Float)

        /** Smoothed bass level, 0..1 relative to the recent peak. */
        fun onBass(level: Float)
    }

    private var sampleRate = 0
    private var hopSize = 1
    private var lowCoeff = 0f
    private var midCoeff = 0f
    private var avgAlpha = 0.02f
    private var peakDecay = 0.999f

    private var lowState = 0f
    private var midState = 0f
    private var sumLow = 0f
    private var sumHigh = 0f
    private var sumAll = 0f
    private var count = 0

    private var prevLow = 0f
    private var prevHigh = 0f
    private var avgLow = 0f
    private var avgHigh = 0f
    private var peakLow = 0f
    private var smoothLow = 0f
    private var hopsSinceKick = 1000
    private var hopsSinceSnare = 1000
    private var hopsSinceBass = 0

    /** Prepares for a stream of [sampleRateHz]; also clears all state. */
    fun configure(sampleRateHz: Int) {
        sampleRate = sampleRateHz.coerceAtLeast(8_000)
        hopSize = (sampleRate / 100).coerceAtLeast(1)
        lowCoeff = onePole(LOW_CUTOFF_HZ, sampleRate)
        midCoeff = onePole(HIGH_CUTOFF_HZ, sampleRate)
        val hopSeconds = hopSize.toFloat() / sampleRate
        avgAlpha = (hopSeconds / AVERAGE_SECONDS).coerceIn(0.001f, 0.5f)
        peakDecay = exp(-hopSeconds / PEAK_DECAY_SECONDS)
        reset()
    }

    fun reset() {
        lowState = 0f
        midState = 0f
        sumLow = 0f
        sumHigh = 0f
        sumAll = 0f
        count = 0
        prevLow = 0f
        prevHigh = 0f
        avgLow = 0f
        avgHigh = 0f
        peakLow = 0f
        smoothLow = 0f
        hopsSinceKick = 1000
        hopsSinceSnare = 1000
        hopsSinceBass = 0
    }

    /** Reads 16-bit interleaved PCM from [buffer]'s remaining bytes; does not consume it. */
    fun processPcm16(buffer: ByteBuffer, channels: Int) {
        if (sampleRate == 0 || channels <= 0) return
        val order = buffer.order()
        buffer.order(ByteOrder.nativeOrder())
        var index = buffer.position()
        val end = buffer.limit() - channels * 2 + 1
        val scale = 1f / (32768f * channels)
        while (index < end) {
            var acc = 0
            var c = 0
            while (c < channels) {
                acc += buffer.getShort(index + c * 2)
                c++
            }
            processSample(acc * scale)
            index += channels * 2
        }
        buffer.order(order)
    }

    /** Reads 32-bit float interleaved PCM; does not consume the buffer. */
    fun processFloat(buffer: ByteBuffer, channels: Int) {
        if (sampleRate == 0 || channels <= 0) return
        val order = buffer.order()
        buffer.order(ByteOrder.nativeOrder())
        var index = buffer.position()
        val end = buffer.limit() - channels * 4 + 1
        while (index < end) {
            var acc = 0f
            var c = 0
            while (c < channels) {
                acc += buffer.getFloat(index + c * 4)
                c++
            }
            processSample(acc / channels)
            index += channels * 4
        }
        buffer.order(order)
    }

    /** One mono sample in -1..1. */
    fun processSample(x: Float) {
        lowState += lowCoeff * (x - lowState)
        midState += midCoeff * (x - midState)
        val high = x - midState
        sumLow += lowState * lowState
        sumHigh += high * high
        sumAll += x * x
        count++
        if (count >= hopSize) endHop()
    }

    private fun endHop() {
        val n = count.toFloat()
        val eLow = sqrt(sumLow / n)
        val eHigh = sqrt(sumHigh / n)
        val eAll = sqrt(sumAll / n)
        sumLow = 0f
        sumHigh = 0f
        sumAll = 0f
        count = 0

        val fluxLow = eLow - prevLow
        val fluxHigh = eHigh - prevHigh
        hopsSinceKick++
        hopsSinceSnare++

        peakLow = max(eLow, peakLow * peakDecay)

        if (eAll > MIN_ENERGY && hopsSinceKick >= MIN_KICK_HOPS &&
            eLow > MIN_ENERGY && eLow > avgLow * KICK_RATIO && fluxLow > avgLow * KICK_FLUX &&
            eLow >= eAll * KICK_LOW_SHARE
        ) {
            listener.onKick(((eLow - avgLow) / (peakLow + EPS) * 1.5f + 0.35f).coerceIn(0.25f, 1f))
            hopsSinceKick = 0
        }
        if (eAll > MIN_ENERGY && hopsSinceSnare >= MIN_SNARE_HOPS &&
            eHigh > MIN_ENERGY && eHigh > avgHigh * SNARE_RATIO && fluxHigh > avgHigh * SNARE_FLUX &&
            eHigh >= eAll * SNARE_HIGH_SHARE
        ) {
            listener.onSnare(((eHigh - avgHigh) / (eHigh + EPS) + 0.3f).coerceIn(0.25f, 1f))
            hopsSinceSnare = 0
        }

        avgLow += (eLow - avgLow) * avgAlpha
        avgHigh += (eHigh - avgHigh) * avgAlpha
        smoothLow += (eLow - smoothLow) * 0.2f
        prevLow = eLow
        prevHigh = eHigh

        if (++hopsSinceBass >= BASS_REPORT_HOPS) {
            hopsSinceBass = 0
            listener.onBass(if (peakLow > MIN_ENERGY) (smoothLow / peakLow).coerceIn(0f, 1f) else 0f)
        }
    }

    private companion object {
        const val LOW_CUTOFF_HZ = 140f
        const val HIGH_CUTOFF_HZ = 1_500f
        const val AVERAGE_SECONDS = 0.5f
        const val PEAK_DECAY_SECONDS = 6f
        const val MIN_ENERGY = 0.01f
        const val EPS = 1e-6f
        const val MIN_KICK_HOPS = 12
        const val MIN_SNARE_HOPS = 10
        const val KICK_RATIO = 1.35f
        const val KICK_FLUX = 0.25f
        const val KICK_LOW_SHARE = 0.4f
        const val SNARE_RATIO = 1.5f
        const val SNARE_FLUX = 0.4f
        const val SNARE_HIGH_SHARE = 0.3f
        const val BASS_REPORT_HOPS = 5

        fun onePole(cutoffHz: Float, sampleRate: Int): Float =
            1f - exp(-2f * Math.PI.toFloat() * cutoffHz / sampleRate)
    }
}

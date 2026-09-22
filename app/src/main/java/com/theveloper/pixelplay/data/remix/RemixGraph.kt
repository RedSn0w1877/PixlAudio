package com.theveloper.pixelplay.data.remix

import com.theveloper.pixelplay.data.remix.dsp.BinauralPanner
import com.theveloper.pixelplay.data.remix.dsp.FdnReverb
import com.theveloper.pixelplay.data.remix.dsp.LoopReader
import com.theveloper.pixelplay.data.remix.dsp.MasterLimiter
import com.theveloper.pixelplay.data.remix.dsp.ParamSmoother
import com.theveloper.pixelplay.data.remix.dsp.RemixStemBuffer
import com.theveloper.pixelplay.data.remix.dsp.StateVariableFilter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The whole remix signal chain, with no Android in it.
 *
 * ```
 * per stem : loop reader (tape rate) -> binaural pan -> gain -> [dry sum, reverb send]
 * shared   : state-variable filter -> bit-crush/decimate -> + reverb -> limiter -> out
 * ```
 *
 * Being pure Kotlin is deliberate: it runs in unit tests at any block size, and the same object
 * can render a remix to a file faster than real time (the phase-2 "export" feature) without an
 * `AudioTrack` anywhere near it.
 *
 * The audio-thread contract: after [prepare], [process] allocates nothing, takes no locks, and
 * touches no `@Volatile` field except through the snapshot handed to it.
 */
class RemixGraph(
    private val sampleRate: Int,
    private val maxStems: Int = 4,
) {
    private val readers = arrayOfNulls<LoopReader>(maxStems)
    private val panners = Array(maxStems) { BinauralPanner(sampleRate) }
    private val stemGains = Array(maxStems) { ParamSmoother(1f, GAIN_TAU_MS) }
    private val stemSends = Array(maxStems) { ParamSmoother(0.2f, SEND_TAU_MS) }

    private val filterLeft = StateVariableFilter()
    private val filterRight = StateVariableFilter()
    private val cutoff = ParamSmoother(18_000f, CUTOFF_TAU_MS)
    private val resonance = ParamSmoother(0.707f, CUTOFF_TAU_MS)

    private val reverb = FdnReverb(sampleRate)
    private val reverbMix = ParamSmoother(0.15f, MIX_TAU_MS)
    private val master = ParamSmoother(1f, GAIN_TAU_MS)
    private val limiter = MasterLimiter(sampleRate)

    private var blockSize = 128
    private var decimCounter = 0
    private var heldLeft = 0f
    private var heldRight = 0f

    /** Peak per stem for the UI meters, written once per block. Read without synchronisation. */
    val stemPeaks = FloatArray(maxStems)

    /** True when the limiter pulled gain down during the last block. */
    @Volatile
    var clipping: Boolean = false
        private set

    /** Position of the loop playhead in frames, for the waveform scrubber. */
    @Volatile
    var playheadFrames: Float = 0f
        private set

    /** Length of the resident region, so the UI can turn [playheadFrames] into a fraction. */
    val regionFrames: Int
        get() = readers.firstOrNull { it != null }?.regionFrames ?: 0

    fun prepare(blockSize: Int) {
        this.blockSize = blockSize.coerceAtLeast(1)
        panners.forEach { it.prepare(this.blockSize) }
        stemGains.forEach { it.prepare(sampleRate, this.blockSize) }
        stemSends.forEach { it.prepare(sampleRate, this.blockSize) }
        cutoff.prepare(sampleRate, this.blockSize)
        resonance.prepare(sampleRate, this.blockSize)
        reverbMix.prepare(sampleRate, this.blockSize)
        master.prepare(sampleRate, this.blockSize)
        filterLeft.reset()
        filterRight.reset()
        reverb.reset()
        limiter.reset()
        decimCounter = 0
        heldLeft = 0f
        heldRight = 0f
    }

    /** Installs (or replaces) one stem's resident loop region. Call off the audio thread. */
    fun setStem(index: Int, buffer: RemixStemBuffer?, xfadeFrames: Int) {
        if (index !in 0 until maxStems) return
        readers[index] = buffer?.let { LoopReader(it, xfadeFrames) }
    }

    fun setCrossfade(xfadeFrames: Int) {
        readers.forEach { it?.setCrossfade(xfadeFrames) }
    }

    fun resetPlayhead() {
        readers.forEach { it?.reset() }
    }

    /**
     * Renders [frames] frames into [outLeft]/[outRight], overwriting them.
     *
     * [frames] should equal the block size passed to [prepare]; a shorter final block is fine.
     */
    fun process(params: RemixParamsSnapshot, outLeft: FloatArray, outRight: FloatArray, frames: Int) {
        // ---- per block: everything expensive happens here, once ----
        val count = params.stemCount.coerceIn(0, maxStems)
        for (i in 0 until maxStems) {
            if (i < count) {
                panners[i].update(params.stemX[i], params.stemY[i], params.stemZ[i], params.yaw, params.pitch)
                stemGains[i].beginBlock(params.stemGain[i])
                // Distance raises the send: a far source is heard mostly through the room, which
                // reads as distance far more strongly than level alone does.
                val distanceSend = (params.stemSend[i] * (1f + 0.35f * panners[i].distance)).coerceIn(0f, 1.5f)
                stemSends[i].beginBlock(distanceSend)
            }
            stemPeaks[i] = 0f
        }

        val cut = cutoff.beginBlock(params.cutoffHz)
        val res = resonance.beginBlock(params.resonance)
        filterLeft.setCutoff(cut, res, sampleRate)
        filterRight.setCutoff(cut, res, sampleRate)
        reverb.setParams(params.rt60, params.damp, params.preDelayMs)
        var mix = reverbMix.beginBlock(params.reverbMix)
        var gain = master.beginBlock(params.masterGain)

        val rate = params.rate.coerceIn(0.25f, 4f)
        val decim = params.decim.coerceIn(1, 16)
        val quantLevels = if (params.bits >= 16) 0f else 2f.pow(params.bits.coerceIn(2, 15)) - 1f
        val clockReader = readers.firstOrNull { it != null }
        val regionFrames = clockReader?.regionFrames ?: 0
        val decayFrames = params.decayFrames.coerceIn(0, if (regionFrames > 0) regionFrames / 2 else 0)

        java.util.Arrays.fill(outLeft, 0, frames, 0f)
        java.util.Arrays.fill(outRight, 0, frames, 0f)

        // ---- per sample ----
        for (n in 0 until frames) {
            var dryLeft = 0f
            var dryRight = 0f
            var send = 0f

            for (i in 0 until count) {
                val reader = readers[i] ?: continue
                reader.advance(rate)
                val panner = panners[i]
                panner.processSample(reader)
                val g = stemGains[i].value + stemGains[i].increment * n
                val l = panner.outLeft * g
                val r = panner.outRight * g
                dryLeft += l
                dryRight += r
                send += (l + r) * 0.5f * (stemSends[i].value + stemSends[i].increment * n)
                val magnitude = if (l < 0f) -l else l
                if (magnitude > stemPeaks[i]) stemPeaks[i] = magnitude
            }

            // Decay tail: fade the dry signal into the seam so a loop breathes instead of
            // stuttering. The reverb send is untouched, so the room rings across the seam.
            if (decayFrames > 0 && clockReader != null) {
                val remaining = regionFrames - clockReader.positionFrames
                if (remaining < decayFrames) {
                    val u = (1f - remaining / decayFrames).coerceIn(0f, 1f)
                    val fade = cos(u * HALF_PI)
                    dryLeft *= fade
                    dryRight *= fade
                }
            }

            filterLeft.process(dryLeft)
            filterRight.process(dryRight)
            var left = when (params.filterMode) {
                RemixParamsSnapshot.FILTER_BAND_PASS -> filterLeft.bandPass
                RemixParamsSnapshot.FILTER_HIGH_PASS -> filterLeft.highPass
                else -> filterLeft.lowPass
            }
            var right = when (params.filterMode) {
                RemixParamsSnapshot.FILTER_BAND_PASS -> filterRight.bandPass
                RemixParamsSnapshot.FILTER_HIGH_PASS -> filterRight.highPass
                else -> filterRight.lowPass
            }

            if (quantLevels > 0f) {
                left = (left * quantLevels).roundToInt() / quantLevels
                right = (right * quantLevels).roundToInt() / quantLevels
            }
            if (decim > 1) {
                if (decimCounter % decim == 0) {
                    heldLeft = left
                    heldRight = right
                }
                decimCounter++
                left = heldLeft
                right = heldRight
            }

            reverb.processSample(send)
            val dryGain = FdnReverb.dryGain(mix)
            val wetGain = FdnReverb.wetGain(mix)
            val mixedLeft = left * dryGain + reverb.outLeft * wetGain
            val mixedRight = right * dryGain + reverb.outRight * wetGain

            limiter.processFrame(mixedLeft * gain, mixedRight * gain)
            outLeft[n] = limiter.outLeft
            outRight[n] = limiter.outRight

            mix += reverbMix.increment
            gain += master.increment
        }

        // ---- close the block ----
        for (i in 0 until count) {
            panners[i].endBlock()
            stemGains[i].endBlock()
            stemSends[i].endBlock()
        }
        cutoff.endBlock()
        resonance.endBlock()
        reverbMix.endBlock()
        master.endBlock()
        clipping = limiter.limiting
        playheadFrames = clockReader?.positionFrames ?: 0f
    }

    private companion object {
        const val GAIN_TAU_MS = 10f
        const val SEND_TAU_MS = 100f
        const val CUTOFF_TAU_MS = 20f
        const val MIX_TAU_MS = 100f
        val HALF_PI = (PI / 2).toFloat()
    }
}

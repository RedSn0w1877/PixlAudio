package com.theveloper.pixelplay.data.remix.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Places one mono stem in space for headphones, parametrically — no HRIR dataset.
 *
 * Why parametric: a measured HRTF set is 1–20 MB, carries its own licence and attribution, needs
 * per-direction interpolation, and — the part that actually matters here — swishes audibly when
 * you interpolate it while dragging, unless you run two convolutions and crossfade them. For a
 * toy you drag around, a continuous model that is always smooth beats a more accurate one that
 * is only smooth when still.
 *
 * What it models, in the order the ear cares about:
 *  1. **ITD** — Woodworth spherical head, `Δt = (a/c)(θ + sin θ)`, driven by the **lateral**
 *     angle. Max ≈ 0.66 ms. Produced by tapping [LoopReader] at two fractional offsets, so it
 *     costs no delay line.
 *  2. **Head shadow** — the far ear loses *high* frequencies, not level across the board. Below
 *     ~1 kHz the head is acoustically transparent and ITD carries direction; above it the head
 *     shadows and level difference carries it. The model follows that split.
 *  3. **Distance** — a bounded inverse law, plus air absorption, plus (in the graph) a bigger
 *     reverb send. The send does more for perceived distance than the gain does.
 *  4. **Front/back** — a continuous top-end softening as a source moves behind you.
 *  5. **Elevation** — a pinna notch that deepens as a source leaves ear level.
 *
 * All parameters are smoothed ([ParamSmoother]) because every one of them multiplies or delays
 * the signal, and a step in any of them is a click.
 */
class BinauralPanner(private val sampleRate: Int) {

    private val delayLeft = ParamSmoother(0f, POSITION_TAU_MS)
    private val delayRight = ParamSmoother(0f, POSITION_TAU_MS)
    private val gainLeft = ParamSmoother(1f, GAIN_TAU_MS)
    private val gainRight = ParamSmoother(1f, GAIN_TAU_MS)
    private val shadowLeft = ParamSmoother(1f, GAIN_TAU_MS)
    private val shadowRight = ParamSmoother(1f, GAIN_TAU_MS)

    private var lpStateLeft = 0f
    private var lpStateRight = 0f

    private val elevationLeft = Biquad()
    private val elevationRight = Biquad()

    /**
     * Rate of the resident buffer the ITD taps read from. Tap offsets are in *its* frames, not the
     * device's — computing them at the device rate made every delay 8.8 % long on a 44.1 kHz stem
     * through a 48 kHz track. Set off the audio thread when a buffer is installed.
     */
    @Volatile
    var tapRateHz: Float = sampleRate.toFloat()

    /**
     * −1 for a mid/side "side" stem. S is a difference signal, not a source: it has to reach the
     * right ear inverted, so that the two stems at the same spot give L = M + S and R = M − S —
     * the record's own stereo image. Without the sign flip the pair can never reconstruct it, and
     * the default screen played a collapsed, comb-filtered version of every song.
     */
    @Volatile
    var rightPolarity: Float = 1f

    /** Distance in metres after the last [update]; the graph uses it to scale the reverb send. */
    var distance: Float = 1f
        private set

    /** Smoothed inter-aural delays, in frames. Read by tests and the debug overlay. */
    val itdLeftFrames: Float get() = delayLeft.value
    val itdRightFrames: Float get() = delayRight.value

    var outLeft: Float = 0f
        private set
    var outRight: Float = 0f
        private set

    private var dLeft = 0f
    private var dRight = 0f
    private var gLeft = 0f
    private var gRight = 0f
    private var sLeft = 0f
    private var sRight = 0f
    private var polarity = 1f

    /** The polarity captured for the current block — a plain field, safe to read per sample. */
    val blockPolarity: Float get() = polarity

    fun prepare(blockSize: Int) {
        delayLeft.prepare(sampleRate, blockSize)
        delayRight.prepare(sampleRate, blockSize)
        gainLeft.prepare(sampleRate, blockSize)
        gainRight.prepare(sampleRate, blockSize)
        shadowLeft.prepare(sampleRate, blockSize)
        shadowRight.prepare(sampleRate, blockSize)
        elevationLeft.reset()
        elevationRight.reset()
        lpStateLeft = 0f
        lpStateRight = 0f
    }

    /**
     * Recomputes the model from a source position in metres and the listener's orientation.
     * Call once per block, never per sample.
     *
     * Coordinates are listener-centred, right-handed: +x right, +y up, −z forward.
     *
     * **Must be paired with [endBlock]** after the block's samples are rendered: this opens a
     * smoothing block, and without the matching close the smoothers never commit and every
     * parameter stays frozen at its initial value.
     */
    fun update(x: Float, y: Float, z: Float, yaw: Float, pitch: Float) {
        val cosY = kotlin.math.cos(-yaw)
        val sinY = sin(-yaw)
        val rx = x * cosY - z * sinY
        val rz = x * sinY + z * cosY
        val cosP = kotlin.math.cos(-pitch)
        val sinP = sin(-pitch)
        val ry = y * cosP - rz * sinP
        val rz2 = y * sinP + rz * cosP

        val dist = sqrt(rx * rx + ry * ry + rz2 * rz2).coerceAtLeast(MIN_DISTANCE_M)
        distance = dist

        // LATERAL angle — how far off the median plane — not full-circle azimuth.
        //
        // Woodworth's (θ + sin θ) is only derived for |θ| ≤ π/2. It used to be fed atan2's ±π, so
        // it kept climbing past the ear: a source dead behind got 0.80 ms of one-ear delay (22 %
        // past the physical maximum) with *zero* level difference. Timing said "hard to one side",
        // level said "dead centre", and the brain, handed contradictory cues, localised nothing.
        // The default screen put the Sides stem exactly there.
        //
        // The lateral angle is front/back symmetric by construction — the cone of confusion the
        // ear really does hear — and the front/back cue below is what separates the two halves.
        val sinLat = (rx / dist).coerceIn(-1f, 1f)
        val lateral = asin(sinLat)
        val itdSeconds = (HEAD_RADIUS_M / SPEED_OF_SOUND) * (lateral + sinLat)
        val itdFrames = itdSeconds * tapRateHz
        // Positive lateral (source to the right) delays the LEFT ear.
        delayLeft.beginBlock(max(0f, itdFrames))
        delayRight.beginBlock(max(0f, -itdFrames))

        val distanceGain = (REFERENCE_DISTANCE_M / max(dist, NEAR_FIELD_M)).coerceAtMost(MAX_PROXIMITY_GAIN)
        val side = abs(sinLat)
        // A little broadband level on the far ear; most of the shadow is spectral, below.
        val shade = BROADBAND_SHADOW * side
        val leftShade = if (sinLat > 0f) shade else 0f
        val rightShade = if (sinLat < 0f) shade else 0f
        gainLeft.beginBlock(distanceGain * (1f - leftShade))
        gainRight.beginBlock(distanceGain * (1f - rightShade))

        // Head shadow, air absorption and the rear cue all mean "the top end goes away", so they
        // share one one-pole per ear rather than three cascaded ones.
        val airCutoff = 20_000f * exp(-AIR_ABSORPTION * dist)
        // Continuous, not a switch. It used to flip from 20 kHz to 9 kHz the moment a source
        // crossed the ear line — a tone control lurching as you drag, not a direction.
        val rearness = (rz2 / dist).coerceIn(0f, 1f)
        val rearCutoff = 20_000f - (20_000f - REAR_CUTOFF_HZ) * rearness
        val farCutoff = 20_000f - SHADOW_CUTOFF_RANGE * side
        val leftCutoff = minOf(if (sinLat > 0f) farCutoff else 20_000f, airCutoff, rearCutoff)
        val rightCutoff = minOf(if (sinLat < 0f) farCutoff else 20_000f, airCutoff, rearCutoff)
        shadowLeft.beginBlock(onePoleCoefficient(leftCutoff))
        shadowRight.beginBlock(onePoleCoefficient(rightCutoff))

        // A pinna notch only means something off ear level. At elevation 0 it used to cut 6 dB at
        // 7 kHz from every stem regardless of where it was — and the stage never sets elevation,
        // so it was dulling everything and modelling nothing. At 0 dB a peaking biquad is exactly
        // the identity, so this is free at ear level and continuous away from it.
        val elevation = asin((ry / dist).coerceIn(-1f, 1f))
        val notchDb = ELEVATION_GAIN_DB * abs(sin(elevation))
        val elevationHz = 7000f + 3000f * sin(elevation)
        elevationLeft.setPeaking(elevationHz, 1.2f, notchDb, sampleRate)
        elevationRight.setPeaking(elevationHz, 1.2f, notchDb, sampleRate)

        dLeft = delayLeft.value; dRight = delayRight.value
        gLeft = gainLeft.value; gRight = gainRight.value
        sLeft = shadowLeft.value; sRight = shadowRight.value
        polarity = rightPolarity
    }

    /** Reads one frame from [reader] and writes the binaural pair into [outLeft]/[outRight]. */
    fun processSample(reader: LoopReader) {
        val rawL = reader.tap(dLeft)
        val rawR = reader.tap(dRight)

        lpStateLeft += sLeft * (rawL - lpStateLeft)
        lpStateRight += sRight * (rawR - lpStateRight)
        // Flush denormals: a one-pole decaying toward zero on a silent stem walks into subnormal
        // range, and on ARM subnormal arithmetic is slow enough to cost a buffer.
        if (abs(lpStateLeft) < DENORMAL) lpStateLeft = 0f
        if (abs(lpStateRight) < DENORMAL) lpStateRight = 0f

        outLeft = elevationLeft.process(lpStateLeft) * gLeft
        outRight = elevationRight.process(lpStateRight) * gRight * polarity

        dLeft += delayLeft.increment
        dRight += delayRight.increment
        gLeft += gainLeft.increment
        gRight += gainRight.increment
        sLeft += shadowLeft.increment
        sRight += shadowRight.increment
    }

    fun endBlock() {
        delayLeft.endBlock(); delayRight.endBlock()
        gainLeft.endBlock(); gainRight.endBlock()
        shadowLeft.endBlock(); shadowRight.endBlock()
    }

    private fun onePoleCoefficient(cutoffHz: Float): Float {
        val f = cutoffHz.coerceIn(200f, sampleRate / 2f * 0.98f)
        return (1f - exp(-2.0 * PI * f / sampleRate)).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val HEAD_RADIUS_M = 0.0875f
        const val SPEED_OF_SOUND = 343f
        private const val MIN_DISTANCE_M = 0.25f

        /**
         * Distance law, bounded at both ends. It was a raw 1/d with a 0.25 m floor on a 4 m stage:
         * 24 dB of travel, and **+12 dB** for a puck dragged onto the listener — straight into the
         * always-on tanh. Now unity at the reference distance (about where pucks start), at most
         * +6 dB up close, and a gentle fall-off to the rim.
         */
        private const val REFERENCE_DISTANCE_M = 2.5f
        private const val NEAR_FIELD_M = 1.25f
        private const val MAX_PROXIMITY_GAIN = 2f

        /** Broadband far-ear loss: ~1.3 dB at 90°. The spectral shadow does the real work. */
        private const val BROADBAND_SHADOW = 0.14f

        /** Far-ear cutoff falls from 20 kHz to 3 kHz at 90°: a real head's high-frequency shadow. */
        private const val SHADOW_CUTOFF_RANGE = 17_000f
        private const val REAR_CUTOFF_HZ = 9_000f
        private const val ELEVATION_GAIN_DB = -6f
        private const val AIR_ABSORPTION = 0.08f
        private const val POSITION_TAU_MS = 60f
        private const val GAIN_TAU_MS = 10f
        private const val DENORMAL = 1e-20f

        /** Worst-case ITD in frames — the guard the loop buffer must allow for. Peaks at ±90°. */
        fun maxItdFrames(sampleRate: Int): Int =
            ((HEAD_RADIUS_M / SPEED_OF_SOUND) * ((PI / 2).toFloat() + 1f) * sampleRate).toInt() + 2
    }
}

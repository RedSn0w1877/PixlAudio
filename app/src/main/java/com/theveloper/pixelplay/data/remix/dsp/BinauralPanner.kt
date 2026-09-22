package com.theveloper.pixelplay.data.remix.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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
 *  1. **ITD** — Woodworth spherical head, `Δt = (a/c)(θ + sin θ)`, a = 8.75 cm. Max ≈ 0.66 ms
 *     (29 frames at 44.1 kHz). Produced by tapping [LoopReader] at two different fractional
 *     offsets, so it costs no delay line.
 *  2. **Head shadow / ILD** — the far ear loses level and high end.
 *  3. **Distance** — inverse-ish gain, plus air absorption, plus (in the graph) a bigger reverb
 *     send. The send does more for perceived distance than the gain does.
 *  4. **Elevation** — the pinna notch migrating up as a source rises.
 *  5. **Front/back** — a gentle top-end cut behind the listener. Parametric models are famously
 *     ambiguous here; head movement is what really resolves it.
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
        // Rotate the source into head-relative coordinates. Yaw first (the dominant cue), then
        // pitch; roll is deliberately ignored — it barely changes azimuth or elevation and costs
        // a full rotation matrix.
        val cosY = cos(-yaw)
        val sinY = sin(-yaw)
        val rx = x * cosY - z * sinY
        val rz = x * sinY + z * cosY
        val cosP = cos(-pitch)
        val sinP = sin(-pitch)
        val ry = y * cosP - rz * sinP
        val rz2 = y * sinP + rz * cosP

        val dist = sqrt(rx * rx + ry * ry + rz2 * rz2).coerceAtLeast(MIN_DISTANCE_M)
        distance = dist

        // azimuth: 0 ahead, +pi/2 to the right. elevation: +pi/2 overhead.
        val azimuth = atan2(rx, -rz2)
        val elevation = kotlin.math.asin((ry / dist).coerceIn(-1f, 1f))

        val sinAz = sin(azimuth)
        // Woodworth: the path difference around a sphere, not a plain sine — the difference
        // matters most at the sides, which is where localisation is sharpest.
        val itdSeconds = (HEAD_RADIUS_M / SPEED_OF_SOUND) * (azimuth + sinAz)
        val itdFrames = itdSeconds * sampleRate
        // Positive azimuth (source to the right) delays the LEFT ear.
        delayLeft.beginBlock(max(0f, itdFrames))
        delayRight.beginBlock(max(0f, -itdFrames))

        val distanceGain = 1f / max(dist, MIN_DISTANCE_M)
        val shade = SHADOW_DEPTH * abs(sinAz)
        val leftShade = if (sinAz > 0f) shade else 0f
        val rightShade = if (sinAz < 0f) shade else 0f
        gainLeft.beginBlock(distanceGain * (1f - leftShade))
        gainRight.beginBlock(distanceGain * (1f - rightShade))

        // Head shadow and air absorption share one one-pole per ear: both are "high end goes away",
        // and two cascaded one-poles would cost twice as much to say the same thing.
        val airCutoff = 20_000f * exp(-AIR_ABSORPTION * dist)
        val rearness = if (-rz2 < 0f) 1f else 0f  // source behind the listener
        val rearCutoff = if (rearness > 0f) REAR_CUTOFF_HZ else 20_000f
        val leftCutoff = minOf(20_000f - SHADOW_CUTOFF_RANGE * leftShade / SHADOW_DEPTH, airCutoff, rearCutoff)
        val rightCutoff = minOf(20_000f - SHADOW_CUTOFF_RANGE * rightShade / SHADOW_DEPTH, airCutoff, rearCutoff)
        shadowLeft.beginBlock(onePoleCoefficient(leftCutoff))
        shadowRight.beginBlock(onePoleCoefficient(rightCutoff))

        val elevationHz = 7000f + 3000f * sin(elevation)
        elevationLeft.setPeaking(elevationHz, 1.2f, ELEVATION_GAIN_DB, sampleRate)
        elevationRight.setPeaking(elevationHz, 1.2f, ELEVATION_GAIN_DB, sampleRate)

        dLeft = delayLeft.value; dRight = delayRight.value
        gLeft = gainLeft.value; gRight = gainRight.value
        sLeft = shadowLeft.value; sRight = shadowRight.value
    }

    /** Reads one frame from [reader] and writes the binaural pair into [outLeft]/[outRight]. */
    fun processSample(reader: LoopReader) {
        val rawL = reader.tap(dLeft)
        val rawR = reader.tap(dRight)

        lpStateLeft += sLeft * (rawL - lpStateLeft)
        lpStateRight += sRight * (rawR - lpStateRight)

        outLeft = elevationLeft.process(lpStateLeft) * gLeft
        outRight = elevationRight.process(lpStateRight) * gRight

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
        private const val SHADOW_DEPTH = 0.4f
        private const val SHADOW_CUTOFF_RANGE = 15_000f
        private const val REAR_CUTOFF_HZ = 9_000f
        private const val ELEVATION_GAIN_DB = -6f
        private const val AIR_ABSORPTION = 0.08f
        private const val POSITION_TAU_MS = 60f
        private const val GAIN_TAU_MS = 10f

        /** Worst-case ITD in frames — the guard the loop buffer must allow for. */
        fun maxItdFrames(sampleRate: Int): Int =
            ((HEAD_RADIUS_M / SPEED_OF_SOUND) * ((PI / 2).toFloat() + 1f) * sampleRate).toInt() + 2
    }
}

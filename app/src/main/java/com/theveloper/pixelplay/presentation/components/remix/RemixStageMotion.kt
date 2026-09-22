package com.theveloper.pixelplay.presentation.components.remix

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Everything on this screen that changes at audio rate, kept **out of composition**.
 *
 * The rule the whole screen hangs off: *nothing that changes at audio rate may be read during
 * composition.* [frame] is the only Compose state here, it is incremented once per frame, and it
 * is read **only** inside `drawWithCache { onDrawBehind { frame.intValue; … } }`. Touching it
 * there invalidates the draw phase of that one node and nothing above it.
 *
 * The old screen got this exactly backwards: `peaks = viewModel.stemPeaks.copyOf()` allocated a
 * FloatArray every frame and, because arrays compare by reference, invalidated composition
 * unconditionally — recomposing the whole list item and retargeting four `animateFloatAsState`s
 * at 60 Hz, next to a live audio engine.
 *
 * Every array here is allocated once and mutated in place. Nothing in this file allocates after
 * construction.
 */
@Stable
class RemixStageMotion {

    /** The only Compose state. Read it in draw lambdas; never in composition. */
    val frame = mutableIntStateOf(0)

    // ── per-stem ────────────────────────────────────────────────────────────

    /** Smoothed peaks: instant attack, exponential release, so meters do not flicker. */
    val peaks = FloatArray(MAX_STEMS)
    private val rawPrev = FloatArray(MAX_STEMS)

    /** Where each puck is drawn, chasing [tgtX]/[tgtY]. Separate so a drag can snap. */
    val curX = FloatArray(MAX_STEMS)
    val curY = FloatArray(MAX_STEMS)
    val tgtX = FloatArray(MAX_STEMS)
    val tgtY = FloatArray(MAX_STEMS)

    var stemCount = 0
    var dragIndex = -1

    // ── listener ────────────────────────────────────────────────────────────

    var playhead = 0f
    var yaw = 0f

    /** Rises when a pressure ring reaches the head, decays with the reverb tail. */
    var headPulse = 0f
    var loudness = 0f
    var timeSec = 0f

    // ── pressure rings ──────────────────────────────────────────────────────
    // A fixed pool written as a ring buffer. A pool cannot leak and cannot allocate; the ceiling
    // is also what stops a dense drum loop turning the floor into visual mud.

    val ringX = FloatArray(RING_CAP)
    val ringY = FloatArray(RING_CAP)
    val ringBirth = FloatArray(RING_CAP)
    val ringLife = FloatArray(RING_CAP)
    val ringStrength = FloatArray(RING_CAP)
    val ringStem = IntArray(RING_CAP)
    val ringRadius = FloatArray(RING_CAP)
    private val ringPrevRadius = FloatArray(RING_CAP)
    private var ringHead = 0

    private val lastSpawn = FloatArray(MAX_STEMS)

    /** True while this slot still has life left. */
    fun ringAlive(i: Int): Boolean = ringLife[i] > 0f && timeSec - ringBirth[i] < ringLife[i]

    fun ringAge(i: Int): Float = ((timeSec - ringBirth[i]) / ringLife[i]).coerceIn(0f, 1f)

    fun reset() {
        for (i in 0 until MAX_STEMS) {
            peaks[i] = 0f; rawPrev[i] = 0f; lastSpawn[i] = 0f
            curX[i] = 0f; curY[i] = 0f; tgtX[i] = 0f; tgtY[i] = 0f
        }
        for (i in 0 until RING_CAP) ringLife[i] = 0f
        headPulse = 0f; loudness = 0f; dragIndex = -1
    }

    /** Place a puck without animating — used when a fresh song loads. */
    fun snapStem(index: Int, x: Float, y: Float) {
        if (index !in 0 until MAX_STEMS) return
        tgtX[index] = x; tgtY[index] = y
        curX[index] = x; curY[index] = y
    }

    fun targetStem(index: Int, x: Float, y: Float) {
        if (index !in 0 until MAX_STEMS) return
        tgtX[index] = x; tgtY[index] = y
    }

    /**
     * Reads the engine's live peak array **in place**. Deliberately not `copyOf()`: this runs
     * every frame, and the copy is what used to drag composition along with it.
     */
    fun samplePeaks(live: FloatArray, dt: Float, reverbMix: Float) {
        var sum = 0f
        val release = 1f - exp(-dt * PEAK_RELEASE)
        for (i in 0 until MAX_STEMS) {
            val raw = if (i < live.size) live[i] else 0f
            // Instant attack so a transient is never missed; smooth release so it does not strobe.
            peaks[i] = if (raw > peaks[i]) raw else peaks[i] + (raw - peaks[i]) * release
            sum += peaks[i]

            val rising = raw - rawPrev[i]
            rawPrev[i] = raw
            if (i < stemCount &&
                rising > ONSET_DELTA &&
                raw > ONSET_FLOOR &&
                timeSec - lastSpawn[i] > ONSET_GATE_SECONDS
            ) {
                spawnRing(i, raw, reverbMix)
                lastSpawn[i] = timeSec
            }
        }
        loudness += (sum / MAX_STEMS - loudness) * (1f - exp(-dt * 2.5f))
    }

    private fun spawnRing(stem: Int, strength: Float, reverbMix: Float) {
        val slot = ringHead
        ringHead = (ringHead + 1) % RING_CAP
        ringX[slot] = curX[stem]
        ringY[slot] = curY[stem]
        ringStem[slot] = stem
        ringBirth[slot] = timeSec
        ringStrength[slot] = strength.coerceIn(0f, 1f)
        ringRadius[slot] = 0f
        ringPrevRadius[slot] = 0f
        // More echo, and the room visibly holds its ripples longer.
        ringLife[slot] = 0.55f + reverbMix.coerceIn(0f, 1f) * 1.05f
    }

    /**
     * Advance every live ring, and pulse the head when one arrives.
     *
     * The travel time *is* the information — you watch a kick you placed behind you cross the
     * floor and reach the back of your head a beat later. That is what teaches "distance" before
     * any label is read, so nothing here may shorten it to reduce clutter.
     */
    fun advanceRings(dt: Float, rate: Float, quantise: Boolean, burst: Boolean) {
        for (i in 0 until RING_CAP) {
            if (!ringAlive(i)) continue
            val age = timeSec - ringBirth[i]
            var r = age * RING_SPEED * rate
            // "Fuzz" makes the rings step outward instead of gliding, so the control has a
            // visible consequence in the room rather than only an audible one.
            if (quantise) r = floor(r * 6f) / 6f
            ringPrevRadius[i] = ringRadius[i]
            ringRadius[i] = r

            val stem = ringStem[i]
            val distance = hypot(ringX[i], ringY[i])
            if (ringPrevRadius[i] < distance && r >= distance) {
                headPulse = (headPulse + ringStrength[i]).coerceAtMost(1.4f)
            }
            if (stem in 0 until MAX_STEMS && burst) {
                // Stutter emits in pairs; handled by the spawn gate, nothing to do per-frame.
            }
        }
    }

    /** Critically damped follow. No springs, no retargeting storm, no recomposition. */
    fun stepPucks(dt: Float) {
        val k = 1f - exp(-dt * PUCK_FOLLOW)
        for (i in 0 until MAX_STEMS) {
            if (i == dragIndex) {
                curX[i] = tgtX[i]; curY[i] = tgtY[i]
                continue
            }
            if (abs(tgtX[i] - curX[i]) > EPS || abs(tgtY[i] - curY[i]) > EPS) {
                curX[i] += (tgtX[i] - curX[i]) * k
                curY[i] += (tgtY[i] - curY[i]) * k
            } else {
                curX[i] = tgtX[i]; curY[i] = tgtY[i]
            }
        }
    }

    fun decayHead(dt: Float, rt60: Float) {
        val decayK = HEAD_DECAY / (rt60.coerceAtLeast(0.3f) / 1.8f)
        headPulse *= exp(-dt * decayK)
        if (headPulse < 0.001f) headPulse = 0f
    }

    companion object {
        const val MAX_STEMS = 4
        const val RING_CAP = 28

        /** Stage units per second. Tuned so a far puck's ring takes most of a beat to arrive. */
        const val RING_SPEED = 1.15f

        private const val ONSET_DELTA = 0.13f
        private const val ONSET_FLOOR = 0.12f
        private const val ONSET_GATE_SECONDS = 0.09f
        private const val PEAK_RELEASE = 6f
        private const val PUCK_FOLLOW = 22f
        private const val HEAD_DECAY = 3.2f
        private const val EPS = 0.0005f
    }
}

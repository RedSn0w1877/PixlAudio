package com.theveloper.pixelplay.songtap.ring

import com.theveloper.pixelplay.songtap.SongTapTimeline

/**
 * The Pixl ring code's motion as a pure function of time, in integers (permille, micro-radians,
 * milliseconds), so Kotlin and Swift give the same number for the same millisecond. The screen reads one
 * frame per draw; there is no animation state. Only the draw phase moves things: a frame is eleven numbers
 * and [slotProgress] answers for one dot, nothing allocates.
 *
 * Times are the screen's clock `tMs` (milliseconds since the code was shown) plus the two moments the
 * session decides: [NONE] means "has not happened".
 * - `approachStartMs` / `approachEndMs`: the other phone is near (the Bluetooth detector's "near"), and it
 *   went away again.
 * - `beamStartMs`: the beam began (the same moment as the plain beam's T0).
 *
 * Idle: the whole code sways: it turns 1.32 rad one way in 6 s (0.22 rad/s on average, eased at both ends),
 * back, then the other way, period 24 s; the artwork breathes 2 percent (period 4 s). Approach (the other
 * phone is near): the code tightens by 8 percent (rigidly, with the start dot and the outline) and a glow
 * rises around the plate over 800 ms; if the phone goes away it relaxes over 400 ms. Beam: the dots peel
 * off the outer ring first and spiral into the art, 800 ms each, staggered; the plate fades; the art pulses
 * 10 percent, then lifts, shrinks to 0.38 and flies off the top; a small "done" pill fades in. Reduced
 * motion: nothing moves, the code cross-fades into the pill in 150 ms.
 *
 * Deviation from the design clip: there the five rings turn in alternating directions. Here the whole code
 * turns as one, because a scanner reads each dot's place from one start dot: rings turning against each
 * other would leave it no way to know where each ring's slot 0 is.
 */
class RingFrameBuffer {
    /** Rotation of the whole code (rings, outline, start dot), micro-radians, clockwise positive. */
    @JvmField var codeAngle = 0
    /** Scale of the code around the centre, 1000 = normal. */
    @JvmField var codeScale = 1000
    /** The soft glow around the plate, 0..700. */
    @JvmField var glow = 0
    @JvmField var plateAlpha = 1000
    /** Opacity of the whole code layer (dots, outline, start dot). Each dot also fades by its own progress. */
    @JvmField var codeAlpha = 1000
    @JvmField var artScale = 1000
    /** Fraction of the container height, 0 = in place, [SongTapTimeline.OFF_TOP] = clear off the top. */
    @JvmField var artTranslateY = 0
    @JvmField var artAlpha = 1000
    @JvmField var pillAlpha = 0
    /** Milliseconds since the beam began, or -1 before it: ask [RingCodeTimeline.slotProgress] with it. */
    @JvmField var beamMs = -1
    /** 0 idle, 1 approach, 2 beam, 3 done (the pill is showing and the code is gone). */
    @JvmField var phase = 0

    fun describe(): String =
        "angle=$codeAngle scale=$codeScale glow=$glow plate=$plateAlpha code=$codeAlpha art=$artScale ty=$artTranslateY " +
            "artAlpha=$artAlpha pill=$pillAlpha beam=$beamMs phase=$phase"
}

object RingCodeTimeline {
    const val NONE = Long.MIN_VALUE

    // Idle
    const val SWAY_AMPLITUDE_MICRO_RAD = 1_320_000L
    const val SWAY_SEGMENT_MS = 6_000L
    const val SWAY_PERIOD_MS = 4 * SWAY_SEGMENT_MS
    const val BREATH_PERIOD_MS = 4_000L
    const val BREATH_RANGE = 20

    // Approach
    const val APPROACH_RISE_MS = 800L
    const val APPROACH_FALL_MS = 400L
    const val APPROACH_TIGHTEN = 80
    const val GLOW_PEAK = 700

    // Beam
    const val DOT_MS = 800L
    const val RING_STAGGER_MS = 70L
    const val IN_RING_SPREAD_MS = 60L
    const val PLATE_FADE_START_MS = 100L
    const val PLATE_FADE_MS = 800L
    const val PULSE_START_MS = 700L
    const val PULSE_UP_MS = 300L
    const val PULSE_DOWN_MS = 300L
    const val PULSE_PEAK = 1100
    const val LIFT_START_MS = 1_300L
    const val LIFT_MS = 700L
    const val LIFT_END_SCALE = 380
    const val ART_FADE_START_MS = 1_700L
    const val ART_FADE_MS = 300L
    const val PILL_START_MS = 1_900L
    const val PILL_FADE_MS = 250L
    const val GLOW_FALL_START_MS = 900L
    const val GLOW_FALL_MS = 600L
    const val BEAM_TOTAL_MS = PILL_START_MS + PILL_FADE_MS

    const val REDUCED_FADE_MS = 150L

    private fun progress(t: Long, start: Long, dur: Long): Int = SongTapTimeline.progress(t, start, dur)
    private fun lerp(a: Int, b: Int, x: Int): Int = SongTapTimeline.lerp(a, b, x)
    private fun smooth(x: Int): Int = SongTapTimeline.smooth(x)

    /** Smoothstep in parts per million, so the slow sway has no visible steps. */
    private fun smoothPpm(x: Long): Long = x * x / 1_000_000L * (3_000_000L - 2L * x) / 1_000_000L

    /** The idle sway of the whole code, micro-radians: 0 -> +A -> 0 -> -A -> 0 over 24 s, each leg eased. */
    fun swayAngle(tMs: Long): Int {
        var t = tMs % SWAY_PERIOD_MS
        if (t < 0L) t += SWAY_PERIOD_MS
        val leg = (t / SWAY_SEGMENT_MS).toInt()
        val e = smoothPpm((t % SWAY_SEGMENT_MS) * 1_000_000L / SWAY_SEGMENT_MS)
        val up = SWAY_AMPLITUDE_MICRO_RAD * e / 1_000_000L
        val down = SWAY_AMPLITUDE_MICRO_RAD - up
        return when (leg) {
            0 -> up.toInt()
            1 -> down.toInt()
            2 -> (-up).toInt()
            else -> (-down).toInt()
        }
    }

    /** The artwork's idle breathing, permille: 990..1010, period 4 s. */
    fun breathScale(tMs: Long): Int {
        var t = tMs % BREATH_PERIOD_MS
        if (t < 0L) t += BREATH_PERIOD_MS
        val half = BREATH_PERIOD_MS / 2
        val e = if (t < half) smooth((t * 1000L / half).toInt()) else 1000 - smooth(((t - half) * 1000L / half).toInt())
        return 1000 + (e - 500) * BREATH_RANGE / 1000
    }

    /** How near the other phone is, 0..1000: rises over 800 ms, relaxes over 400 ms after [endMs]. */
    fun approachLevel(tMs: Long, startMs: Long, endMs: Long): Int {
        if (startMs == NONE || tMs <= startMs) return 0
        val rise = smooth(progress(tMs, startMs, APPROACH_RISE_MS))
        if (endMs == NONE || tMs <= endMs) return rise
        val atEnd = smooth(progress(endMs, startMs, APPROACH_RISE_MS))
        return atEnd - lerp(0, atEnd, smooth(progress(tMs, endMs, APPROACH_FALL_MS)))
    }

    /** When a dot starts to peel off, in ms after the beam: outer ring first, scrambled inside a ring. */
    fun slotStartMs(ring: Int, index: Int): Int {
        val n = RingCodeLayout.RING_SLOTS[ring]
        val scatter = ((index * 37) % n) * IN_RING_SPREAD_MS.toInt() / n
        return (RingCodeLayout.RINGS - 1 - ring) * RING_STAGGER_MS.toInt() + scatter
    }

    /** One dot's flight into the artwork, 0 = in place, 1000 = arrived (and gone), [beamMs] since the beam began. */
    fun slotProgress(ring: Int, index: Int, beamMs: Int): Int {
        if (beamMs < 0) return 0
        return progress(beamMs.toLong(), slotStartMs(ring, index).toLong(), DOT_MS)
    }

    /** The start dot leaves with the first dot of the outer ring. */
    fun markerProgress(beamMs: Int): Int = slotProgress(RingCodeLayout.RINGS - 1, 0, beamMs)

    /** The whole beam's length from [beamStartMs] to the pill resting, ms. */
    fun beamTotalMs(reducedMotion: Boolean): Long = if (reducedMotion) REDUCED_FADE_MS else BEAM_TOTAL_MS

    fun frame(tMs: Long, approachStartMs: Long, approachEndMs: Long, beamStartMs: Long, reducedMotion: Boolean): RingFrameBuffer =
        frameInto(tMs, approachStartMs, approachEndMs, beamStartMs, reducedMotion, RingFrameBuffer())

    /** [frame] without the allocation: fills [out] and returns it. */
    fun frameInto(
        tMs: Long,
        approachStartMs: Long,
        approachEndMs: Long,
        beamStartMs: Long,
        reducedMotion: Boolean,
        out: RingFrameBuffer,
    ): RingFrameBuffer {
        out.codeAngle = 0; out.codeScale = 1000; out.glow = 0; out.plateAlpha = 1000; out.codeAlpha = 1000
        out.artScale = 1000; out.artTranslateY = 0; out.artAlpha = 1000; out.pillAlpha = 0; out.beamMs = -1; out.phase = 0
        val beaming = beamStartMs != NONE && tMs >= beamStartMs
        if (reducedMotion) {
            if (beaming) {
                val f = smooth(progress(tMs, beamStartMs, REDUCED_FADE_MS))
                out.codeAlpha = 1000 - f; out.plateAlpha = 1000 - f; out.artAlpha = 1000 - f; out.pillAlpha = f
                out.beamMs = (tMs - beamStartMs).coerceAtMost(1_000_000L).toInt()
                out.phase = if (f >= 1000) 3 else 2
            }
            return out
        }
        var level = approachLevel(tMs, approachStartMs, approachEndMs)
        if (!beaming) {
            out.codeAngle = swayAngle(tMs)
            out.artScale = breathScale(tMs)
            out.codeScale = 1000 - APPROACH_TIGHTEN * level / 1000
            out.glow = GLOW_PEAK * level / 1000
            out.phase = if (level > 0) 1 else 0
            return out
        }
        val tb = tMs - beamStartMs
        val tbi = tb.coerceAtMost(1_000_000L).toInt()
        out.beamMs = tbi
        out.phase = if (tb >= BEAM_TOTAL_MS) 3 else 2
        out.codeAngle = swayAngle(beamStartMs)
        val flare = smooth(progress(tMs, beamStartMs, 400L))
        if (flare > level) level = flare
        out.codeScale = 1000 - APPROACH_TIGHTEN * level / 1000
        val fall = smooth(progress(tMs, beamStartMs + GLOW_FALL_START_MS, GLOW_FALL_MS))
        out.glow = lerp(GLOW_PEAK * level / 1000, 0, fall)
        out.plateAlpha = 1000 - smooth(progress(tMs, beamStartMs + PLATE_FADE_START_MS, PLATE_FADE_MS))
        // Art: pulse, then lift, shrink and fly off the top.
        val pulseUp = SongTapTimeline.easeOutCubic(progress(tMs, beamStartMs + PULSE_START_MS, PULSE_UP_MS))
        val pulseDown = smooth(progress(tMs, beamStartMs + PULSE_START_MS + PULSE_UP_MS, PULSE_DOWN_MS))
        val pulse = lerp(1000, PULSE_PEAK, pulseUp)
        val afterPulse = lerp(pulse, 1000, pulseDown)
        val lift = progress(tMs, beamStartMs + LIFT_START_MS, LIFT_MS)
        out.artScale = if (lift == 0) afterPulse else lerp(1000, LIFT_END_SCALE, smooth(lift))
        out.artTranslateY = lerp(0, SongTapTimeline.OFF_TOP, SongTapTimeline.easeInCubic(lift))
        out.artAlpha = 1000 - smooth(progress(tMs, beamStartMs + ART_FADE_START_MS, ART_FADE_MS))
        out.pillAlpha = smooth(progress(tMs, beamStartMs + PILL_START_MS, PILL_FADE_MS))
        return out
    }
}

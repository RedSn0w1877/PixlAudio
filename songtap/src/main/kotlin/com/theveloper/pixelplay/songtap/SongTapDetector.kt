package com.theveloper.pixelplay.songtap

/**
 * Tap detection: two phones touching is seen as a sudden, sustained jump of the other phone's signal
 * strength above its recent baseline, backed by a sharp jerk on either phone. Never a fixed dBm.
 *
 * Why relative: the same tap reads very differently on different phones (antenna, case, the three
 * advertising channels differ by up to 15 dB), so the detector compares a peer with itself a moment ago.
 * The only absolute numbers are [NEAR_ENTER_DBM] and [NEAR_EXIT_DBM], a loose floor for "this peer is within
 * reach" that decides the Near state and gates a fire; they are not the tap threshold.
 *
 * Every number is a named constant so the diagnostics screen (Developer mode > Song Tap diagnostics) can
 * show them next to the live signal and the owner can calibrate on the real phones. All arithmetic is in
 * integers (signal in 1/256 dB) so the Swift port matches bit for bit.
 */
object SongTapTuning {
    /** The baseline is a slow average: alpha = 1 / 2^BASELINE_SHIFT per sample. */
    const val BASELINE_SHIFT = 3

    /** The fast average the jump is read from: alpha = 1 / 2^FAST_SHIFT per sample. */
    const val FAST_SHIFT = 1

    /** A jump of at least this many dB above the baseline starts a candidate tap. */
    const val JUMP_DB = 15

    /** While the signal is this far above the baseline the baseline stops following it, so a tap is not absorbed. */
    const val FREEZE_DB = 6

    /** The jump must hold this long, with a jerk on either phone close by. */
    const val HOLD_MS = 150L

    /** Without a jerk, the jump must hold this long (a gentle tap). */
    const val DWELL_MS = 400L

    /** A jerk counts when it was within this long of the moment the jump has been held. */
    const val IMPULSE_WINDOW_MS = 400L

    /** After a fire, no new fire for this long. */
    const val COOLDOWN_MS = 2_000L

    /** Within reach (loose): the smoothed signal rises above this to become Near. */
    const val NEAR_ENTER_DBM = -70

    /** And falls below this to stop being Near. */
    const val NEAR_EXIT_DBM = -78

    /** Samples this far apart (ms) reset the detector for the peer: it was gone. */
    const val GAP_RESET_MS = 3_000L

    /** Glow: the smoothed signal at which it starts and at which it is full. */
    const val GLOW_MIN_DBM = -80
    const val GLOW_MAX_DBM = -45

    /** Accelerometer: the change between two readings (milli g, all three axes) that counts as a jerk. */
    const val JERK_MILLI_G = 1_800

    /** After a jerk, the next one is ignored for this long. */
    const val JERK_DEBOUNCE_MS = 300L
}

const val RSSI_SCALE = 256

enum class FireReason(val code: String) { NONE("none"), IMPULSE("impulse"), DWELL("dwell") }

/** One reading of a peer's signal strength, [tMs] on this phone's monotonic clock. */
data class RssiSample(val tMs: Long, val rssi: Int)

/**
 * The detector's memory for one peer. [baselineQ] and [fastQ] are dBm times 256; [heldSinceMs] is when the
 * signal first went [SongTapTuning.JUMP_DB] above the baseline and has stayed there (-1 when it has not).
 */
data class DetectorState(
    val started: Boolean,
    val lastMs: Long,
    val baselineQ: Int,
    val fastQ: Int,
    val near: Boolean,
    val heldSinceMs: Long,
    val lastFireMs: Long,
) {
    companion object {
        val INITIAL = DetectorState(false, -1L, 0, 0, false, -1L, -1L)
    }
}

data class DetectorStep(
    val state: DetectorState,
    val fire: FireReason,
    /** The glow strength 0..500 (permille of full alpha) for the approach, from the smoothed signal. */
    val glowPermille: Int,
)

object SongTapDetector {

    /**
     * Feeds one [sample]. [impulseMs] is the latest jerk, own or the peer's converted to this clock, or -1.
     */
    fun step(state: DetectorState, sample: RssiSample, impulseMs: Long): DetectorStep {
        val sampleQ = sample.rssi * RSSI_SCALE
        var s = state
        if (!s.started || (s.lastMs >= 0L && sample.tMs - s.lastMs > SongTapTuning.GAP_RESET_MS)) {
            s = DetectorState(true, sample.tMs, sampleQ, sampleQ, false, -1L, s.lastFireMs)
        }
        val fastQ = s.fastQ + ((sampleQ - s.fastQ) shr SongTapTuning.FAST_SHIFT)
        val deltaQ = fastQ - s.baselineQ
        val baselineQ = if (deltaQ < SongTapTuning.FREEZE_DB * RSSI_SCALE) {
            s.baselineQ + ((sampleQ - s.baselineQ) shr SongTapTuning.BASELINE_SHIFT)
        } else {
            s.baselineQ
        }
        val fastDbm = floorDiv(fastQ, RSSI_SCALE)
        val near = if (s.near) fastDbm >= SongTapTuning.NEAR_EXIT_DBM else fastDbm >= SongTapTuning.NEAR_ENTER_DBM
        var held = s.heldSinceMs
        held = if (deltaQ >= SongTapTuning.JUMP_DB * RSSI_SCALE) (if (held < 0L) sample.tMs else held) else -1L

        var fire = FireReason.NONE
        var lastFire = s.lastFireMs
        val cooled = lastFire < 0L || sample.tMs - lastFire >= SongTapTuning.COOLDOWN_MS
        if (held >= 0L && near && cooled) {
            val heldFor = sample.tMs - held
            val jerkNear = impulseMs >= 0L && absDiff(sample.tMs, impulseMs) <= SongTapTuning.IMPULSE_WINDOW_MS
            if (heldFor >= SongTapTuning.HOLD_MS && jerkNear) fire = FireReason.IMPULSE
            else if (heldFor >= SongTapTuning.DWELL_MS) fire = FireReason.DWELL
        }
        if (fire != FireReason.NONE) {
            lastFire = sample.tMs
            held = -1L
        }
        val next = DetectorState(true, sample.tMs, baselineQ, fastQ, near, held, lastFire)
        return DetectorStep(next, fire, glowPermille(fastQ))
    }

    /** 0..500 from the smoothed signal: nothing at [SongTapTuning.GLOW_MIN_DBM], half strength at [SongTapTuning.GLOW_MAX_DBM]. */
    fun glowPermille(fastQ: Int): Int {
        val lo = SongTapTuning.GLOW_MIN_DBM * RSSI_SCALE
        val hi = SongTapTuning.GLOW_MAX_DBM * RSSI_SCALE
        if (fastQ <= lo) return 0
        if (fastQ >= hi) return 500
        return ((fastQ - lo).toLong() * 500L / (hi - lo).toLong()).toInt()
    }

    fun floorDiv(a: Int, b: Int): Int {
        var q = a / b
        if (a % b != 0 && ((a < 0) != (b < 0))) q--
        return q
    }

    private fun absDiff(a: Long, b: Long): Long = if (a >= b) a - b else b - a
}

/** One accelerometer reading in milli g, [tMs] on this phone's monotonic clock. */
data class AccelSample(val tMs: Long, val xMg: Int, val yMg: Int, val zMg: Int)

/** The accelerometer's memory: the previous reading and the last jerk time. */
data class ImpulseState(val hasPrevious: Boolean, val prev: AccelSample, val lastImpulseMs: Long) {
    companion object {
        val INITIAL = ImpulseState(false, AccelSample(0L, 0, 0, 0), -1L)
    }
}

data class ImpulseStep(val state: ImpulseState, val impulse: Boolean)

/** A sharp jerk is a large change between two consecutive readings, on any axis combination. */
object SongTapImpulse {

    fun step(state: ImpulseState, sample: AccelSample): ImpulseStep {
        if (!state.hasPrevious) return ImpulseStep(ImpulseState(true, sample, state.lastImpulseMs), false)
        val dx = (sample.xMg - state.prev.xMg).toLong()
        val dy = (sample.yMg - state.prev.yMg).toLong()
        val dz = (sample.zMg - state.prev.zMg).toLong()
        val jerk = SongTapTuning.JERK_MILLI_G.toLong()
        val strong = dx * dx + dy * dy + dz * dz >= jerk * jerk
        val debounced = state.lastImpulseMs >= 0L && sample.tMs - state.lastImpulseMs < SongTapTuning.JERK_DEBOUNCE_MS
        val hit = strong && !debounced
        return ImpulseStep(ImpulseState(true, sample, if (hit) sample.tMs else state.lastImpulseMs), hit)
    }
}

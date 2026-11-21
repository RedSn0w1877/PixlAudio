package com.theveloper.pixelplay.songtap

/**
 * Tap detection: two phones touching is seen as a sudden, sustained jump of the other phone's signal
 * strength above its recent baseline, optionally backed by a sharp jerk on either phone. The tap itself is never a fixed dBm.
 *
 * Why relative: the same tap reads very differently on different phones (antenna, case, the three
 * advertising channels differ by up to 15 dB), so the detector compares a peer with itself a moment ago.
 *
 * The scale (spec 6). Every number here is on the iPhone's scale, the reference: touching reads about -33 dBm, resting 20 to 30 cm
 * apart about -60. An Android phone reads about 15 dB hotter for the same pose (the owner's two phones, 2026-11-20), so the app adds
 * an `rssiOffsetDb` (default -15 on a Pixel) to every raw reading BEFORE it reaches the detector, the nearby book and every gate;
 * this core only ever sees the normalised value. The absolute numbers are the coarse gates: [SongTapTuning.NEAR_ENTER_DBM] /
 * [SongTapTuning.NEAR_EXIT_DBM] (the tap near gate: a phone has to be this close before a receiver connects to it, before a sender
 * serves it the song, before a tap can fire and before the other phone's tap message is believed),
 * [SongTapTuning.LIST_ENTER_DBM] / [SongTapTuning.LIST_EXIT_DBM] (the broader gate of the "nearby phones" list, advertisements
 * only, never connects) and [SongTapTuning.CLOSE_HOLD_DBM] (a gentle hold, only a few cm away).
 *
 * The tuning that depends on how sensitive the listener wants the tap is a [SongTapSensitivity] profile: NORMAL is the iPhone's
 * tuning, HIGH (the default) reacts to a smaller, shorter jump.
 *
 * Every number is a named constant so the diagnostics screen (Developer mode > Song Tap diagnostics) can
 * show them next to the live signal. All arithmetic is in integers (signal in 1/256 dB) so the Swift port matches bit for bit.
 */
object SongTapTuning {
    /** A jump without a jerk fires (the dwell path) only when the smoothed signal is also at least this strong. */
    const val DWELL_MIN_DBM = -58

    /**
     * A jump that has held this long without firing is the new normal level, not a tap: the baseline catches up to it
     * (otherwise a phone parked at a new distance would keep the baseline frozen for good).
     */
    const val FREEZE_MAX_MS = 2_000L

    /**
     * The tap near gate, on the iPhone scale: the smoothed signal rises to this to become Near. Everything that costs something needs
     * it: connecting, serving the offer, firing, believing the other phone's tap message. (Spec 5 had -55 / -63 on the raw Android
     * scale: the same idea without calibration.)
     */
    const val NEAR_ENTER_DBM = -70

    /** And falls below this to stop being Near (hysteresis). */
    const val NEAR_EXIT_DBM = -78

    /**
     * The broader gate of the "nearby phones" list (the Song Drop picker). The list is built from advertisements only,
     * with no connection, so a phone a couple of metres away may be listed; connecting and beaming still need the
     * tap near gate above. Enter and leave are different numbers so an entry does not flicker.
     */
    const val LIST_ENTER_DBM = -85
    const val LIST_EXIT_DBM = -92

    /**
     * A gentle hold: the phones are brought together (no bump) and held. The smoothed, normalised signal at or above this for
     * [CLOSE_HOLD_MS] fires with reason HOLD, whatever the jump or the jerk did. Only inside the near gate. Touching reads about -33 and
     * resting 20 to 30 cm apart about -60, so -45 means "within a few cm" and never "at rest". (Spec 5 had -52 for 350 ms on the raw
     * scale: a Pixel resting 20 to 30 cm away reads about -44 raw and would have fired constantly.)
     */
    const val CLOSE_HOLD_DBM = -45
    const val CLOSE_HOLD_MS = 250L

    /**
     * After a hold fired, the hold can fire again only once the smoothed signal has fallen below this (the phones have come apart: resting
     * 20 to 30 cm away reads about -60). Spec 5 re-armed it when the signal left the near gate, which on the iPhone scale (-78) means
     * about a metre away: a second touch right after the first would not have counted.
     */
    const val CLOSE_RELEASE_DBM = -55

    /** Samples this far apart (ms) reset the detector for the peer: it was gone. */
    const val GAP_RESET_MS = 3_000L

    /** Glow: the smoothed signal at which it starts and at which it is full. */
    const val GLOW_MIN_DBM = -80
    const val GLOW_MAX_DBM = -45

    /** After a jerk, the next one is ignored for this long. */
    const val JERK_DEBOUNCE_MS = 300L
}

/**
 * How sensitive the tap is (spec 6). [NORMAL] is the tuning of the iPhone the owner calls perfect. [HIGH], the default, reads a
 * 9 dB jump held 200 ms (60 ms with a jerk of 900 milli g) as a tap and may fire again after 1.2 s. The jerk is only ever an optional
 * second factor, never required. Both ride the same gates; only how little it takes to fire differs.
 */
enum class SongTapSensitivity(
    val code: String,
    /** The baseline is a slow average: alpha = 1 / 2^baselineShift per sample. */
    val baselineShift: Int,
    /** The fast average the jump is read from: alpha = 1 / 2^fastShift per sample. */
    val fastShift: Int,
    /** A jump of at least this many dB above the baseline starts a candidate tap. */
    val jumpDb: Int,
    /** While the signal is this far above the baseline the baseline stops following it, so a tap is not absorbed. */
    val freezeDb: Int,
    /** The jump must hold this long, with a jerk on either phone close by. */
    val holdMs: Long,
    /** Without a jerk, the jump must hold this long (a gentle tap). */
    val dwellMs: Long,
    /** A jerk counts when it was within this long of the moment the jump has been held. */
    val impulseWindowMs: Long,
    /** The change between two accelerometer readings (milli g, all three axes) that counts as a jerk. */
    val jerkMilliG: Int,
    /** After a fire, no new fire for this long. */
    val cooldownMs: Long,
) {
    NORMAL("normal", 3, 1, 15, 6, 150L, 400L, 400L, 1_800, 2_000L),
    HIGH("high", 3, 1, 9, 6, 60L, 200L, 400L, 900, 1_200L),
    ;

    companion object {
        val DEFAULT = HIGH

        /** The profile for a stored [code]; the default for anything else. */
        fun fromCode(code: String?): SongTapSensitivity = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

const val RSSI_SCALE = 256

enum class FireReason(val code: String) { NONE("none"), IMPULSE("impulse"), DWELL("dwell"), HOLD("hold") }

/** One reading of a peer's signal strength (already on the iPhone scale), [tMs] on this phone's monotonic clock. */
data class RssiSample(val tMs: Long, val rssi: Int)

/**
 * The detector's memory for one peer. [baselineQ] and [fastQ] are dBm times 256; [heldSinceMs] is when the
 * signal first went [SongTapSensitivity.jumpDb] above the baseline and has stayed there (-1 when it has not).
 * [closeSinceMs] is when the smoothed signal first reached [SongTapTuning.CLOSE_HOLD_DBM] and has stayed there: -1 when
 * it is not that strong, [CLOSE_USED] after a fire (it must drop below the level before it can count again).
 */
data class DetectorState(
    val started: Boolean,
    val lastMs: Long,
    val baselineQ: Int,
    val fastQ: Int,
    val near: Boolean,
    val heldSinceMs: Long,
    val lastFireMs: Long,
    val closeSinceMs: Long = -1L,
) {
    companion object {
        val INITIAL = DetectorState(false, -1L, 0, 0, false, -1L, -1L, -1L)

        /** [closeSinceMs] after a fire: consumed until the signal falls below the hold level. */
        const val CLOSE_USED = -2L
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
     * Feeds one [sample] (already normalised to the iPhone scale). [impulseMs] is the latest jerk, own or the peer's converted to
     * this clock, or -1. [profile] is how sensitive the tap is; it may change between two samples (the next sample just uses the new one).
     */
    fun step(state: DetectorState, sample: RssiSample, impulseMs: Long, profile: SongTapSensitivity = SongTapSensitivity.DEFAULT): DetectorStep {
        val sampleQ = sample.rssi * RSSI_SCALE
        var s = state
        if (!s.started || (s.lastMs >= 0L && sample.tMs - s.lastMs > SongTapTuning.GAP_RESET_MS)) {
            s = DetectorState(true, sample.tMs, sampleQ, sampleQ, false, -1L, s.lastFireMs, -1L)
        }
        val fastQ = s.fastQ + ((sampleQ - s.fastQ) shr profile.fastShift)
        val deltaQ = fastQ - s.baselineQ
        var baselineQ = if (deltaQ < profile.freezeDb * RSSI_SCALE) {
            s.baselineQ + ((sampleQ - s.baselineQ) shr profile.baselineShift)
        } else {
            s.baselineQ
        }
        val fastDbm = floorDiv(fastQ, RSSI_SCALE)
        val near = if (s.near) fastDbm >= SongTapTuning.NEAR_EXIT_DBM else fastDbm >= SongTapTuning.NEAR_ENTER_DBM
        var held = s.heldSinceMs
        held = if (deltaQ >= profile.jumpDb * RSSI_SCALE) (if (held < 0L) sample.tMs else held) else -1L

        // The hold timer: running while the smoothed signal is at the hold level. After a fire it is consumed ([CLOSE_USED]) and
        // stays so until the phones have come apart (the signal fell below [SongTapTuning.CLOSE_RELEASE_DBM]), so one contact is one fire.
        var close = s.closeSinceMs
        close = when {
            !near -> -1L
            close == DetectorState.CLOSE_USED -> if (fastDbm < SongTapTuning.CLOSE_RELEASE_DBM) -1L else close
            fastDbm >= SongTapTuning.CLOSE_HOLD_DBM -> if (close == -1L) sample.tMs else close
            else -> -1L
        }

        var fire = FireReason.NONE
        var lastFire = s.lastFireMs
        val cooled = lastFire < 0L || sample.tMs - lastFire >= profile.cooldownMs
        if (held >= 0L && near && cooled) {
            val heldFor = sample.tMs - held
            val jerkNear = impulseMs >= 0L && absDiff(sample.tMs, impulseMs) <= profile.impulseWindowMs
            if (heldFor >= profile.holdMs && jerkNear) fire = FireReason.IMPULSE
            else if (heldFor >= profile.dwellMs && fastDbm >= SongTapTuning.DWELL_MIN_DBM) fire = FireReason.DWELL
        }
        if (fire == FireReason.NONE && near && cooled && close >= 0L && sample.tMs - close >= SongTapTuning.CLOSE_HOLD_MS) {
            fire = FireReason.HOLD
        }
        if (fire != FireReason.NONE) {
            lastFire = sample.tMs
            held = -1L
            close = DetectorState.CLOSE_USED
            // The phones stay together after a tap: the signal stays high, so the baseline must catch up or the
            // same contact would fire again as soon as the cooldown ends.
            baselineQ = fastQ
        } else if (held >= 0L && sample.tMs - held >= SongTapTuning.FREEZE_MAX_MS) {
            held = -1L
            baselineQ = fastQ
        }
        val next = DetectorState(true, sample.tMs, baselineQ, fastQ, near, held, lastFire, close)
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

    fun step(state: ImpulseState, sample: AccelSample, profile: SongTapSensitivity = SongTapSensitivity.DEFAULT): ImpulseStep {
        if (!state.hasPrevious) return ImpulseStep(ImpulseState(true, sample, state.lastImpulseMs), false)
        val dx = (sample.xMg - state.prev.xMg).toLong()
        val dy = (sample.yMg - state.prev.yMg).toLong()
        val dz = (sample.zMg - state.prev.zMg).toLong()
        val jerk = profile.jerkMilliG.toLong()
        val strong = dx * dx + dy * dy + dz * dz >= jerk * jerk
        val debounced = state.lastImpulseMs >= 0L && sample.tMs - state.lastImpulseMs < SongTapTuning.JERK_DEBOUNCE_MS
        val hit = strong && !debounced
        return ImpulseStep(ImpulseState(true, sample, if (hit) sample.tMs else state.lastImpulseMs), hit)
    }
}

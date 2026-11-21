package com.theveloper.pixelplay.data.songtap.ble

/**
 * The Android side of the signal scale (spec 6). The iPhone is the reference: touching it reads about [TOUCH_REFERENCE_DBM], resting 20 to 30
 * cm apart about -60. A Pixel 10 Pro reads about 15 dB hotter for the same pose (the owner's two phones, 2026-11-20), so every raw reading
 * is shifted by an `rssiOffsetDb` before smoothing, the baseline, the gates and the detector. Nothing here touches the core: iOS needs none
 * of it (its own readings are the reference).
 */
object RssiCalibration {

    /** What a phone touching an iPhone reads on the iPhone: the target the calibration moves the Android readings to. */
    const val TOUCH_REFERENCE_DBM = -33

    const val MIN_OFFSET_DB = -35
    const val MAX_OFFSET_DB = 0

    /** A Pixel (any model) reads about 15 dB hotter than an iPhone: measured on a Pixel 10 Pro, 2026-11-20. */
    const val PIXEL_DEFAULT_OFFSET_DB = -15

    /**
     * Any other Android model: a GUESS. Different antennas and chips read anywhere between about 5 and 20 dB hotter than an iPhone; -12 is
     * a middle value so that an uncalibrated phone is neither far too sensitive nor deaf. The Calibrate button in the diagnostics screen
     * replaces it with a measured value.
     */
    const val OTHER_DEFAULT_OFFSET_DB = -12

    /** The button is held this long, with the phones touching. */
    const val HOLD_MS = 2_000L

    /** Fewer smoothed readings than this in the hold is not a measurement (readings come about every 120 ms: about 16 in 2 s). */
    const val MIN_SAMPLES = 6

    /** The default offset for a device [model] (`Build.MODEL`): -15 for a Pixel, -12 (a guess) for everything else. */
    fun defaultOffsetFor(model: String?): Int =
        if (model != null && model.trim().startsWith("Pixel", ignoreCase = true)) PIXEL_DEFAULT_OFFSET_DB else OTHER_DEFAULT_OFFSET_DB

    fun clamp(offsetDb: Int): Int = offsetDb.coerceIn(MIN_OFFSET_DB, MAX_OFFSET_DB)

    /** The 90th percentile (nearest rank) of [values]; the lowest values (the approach) and a stray spike do not decide it. Null when empty. */
    fun percentile90(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = (sorted.size * 9 + 9) / 10 // ceil(0.9 * n)
        return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
    }

    /** offset = -(p90 - touching reference), clamped to [MIN_OFFSET_DB, MAX_OFFSET_DB]. [p90Raw] is the raw, smoothed, un-shifted reading. */
    fun offsetFor(p90Raw: Int): Int = clamp(-(p90Raw - TOUCH_REFERENCE_DBM))
}

/** What the Calibrate button is doing. */
sealed interface CalibrationState {
    data object Idle : CalibrationState

    /** The button is held: [heldMs] so far of [RssiCalibration.HOLD_MS], with [samples] smoothed readings collected. */
    data class Running(val heldMs: Long, val samples: Int) : CalibrationState

    /** Done: the new [offsetDb], from the 90th percentile [p90Raw] of [samples] smoothed raw readings. */
    data class Done(val offsetDb: Int, val p90Raw: Int, val samples: Int) : CalibrationState

    /** Not done: [reason] is "no_phone" (nothing was being followed), "too_few" (fewer than [RssiCalibration.MIN_SAMPLES] readings) or "released" (the button was let go early). */
    data class Failed(val reason: String) : CalibrationState
}

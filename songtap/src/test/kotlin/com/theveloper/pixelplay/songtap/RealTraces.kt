package com.theveloper.pixelplay.songtap

/**
 * The owner's two real calibration logs of 2026-11-20 (iPhone and Pixel 10 Pro, same session), as fixtures.
 * Source: builds/song-tap-2026-11-20/calibration/IPHONE-CALIBRATION-2026-11-20.md and PIXEL-CALIBRATION-2026-11-20.md.
 *
 * What is REAL here: every "listed" point (time on the phone's own boot clock, raw RSSI) copied from the logs' excerpts.
 * What is NOT: the logs list only an excerpt, so the long stretches of "resting 20 to 30 cm apart" are filled with a fixed, repeating
 * pattern that stays inside the range the logs state for that stretch (iPhone -55 to -65 around -60; Pixel -40 to -48 around -44), one
 * reading every [CADENCE_MS] (the logs say readings arrive about every 120 to 130 ms). The excerpts list only some readings, so where two
 * listed points are more than [DENSIFY_OVER_MS] apart the missing readings are filled by straight-line interpolation at the same cadence
 * (`densify`, applied to the touch and approach points only). Without it the 400 ms timing checks would measure the gaps of the excerpt, not
 * the detector. Nothing else is invented.
 */
object RealTraces {
    const val CADENCE_MS = 125L

    /** One raw reading as the phone's own radio gave it. */
    data class Pt(val t: Long, val raw: Int)

    /** Above this gap (ms) between two listed points, [densify] adds the readings the excerpt left out. */
    const val DENSIFY_OVER_MS = 200L

    /** Linear interpolation between listed points, one reading per [CADENCE_MS]; the listed points themselves are kept as they are. */
    fun densify(points: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>()
        for ((i, p) in points.withIndex()) {
            out += p
            val next = points.getOrNull(i + 1) ?: continue
            if (next.t - p.t <= DENSIFY_OVER_MS) continue
            var t = p.t + CADENCE_MS
            while (next.t - t >= CADENCE_MS / 2) {
                val raw = p.raw + ((next.raw - p.raw).toLong() * (t - p.t) / (next.t - p.t)).toInt()
                out += Pt(t, raw)
                t += CADENCE_MS
            }
        }
        return out
    }

    private fun rest(fromMs: Long, toMs: Long, cycle: IntArray): List<Pt> {
        val out = ArrayList<Pt>()
        var t = fromMs
        var i = 0
        while (t < toMs) {
            out += Pt(t, cycle[i % cycle.size])
            t += CADENCE_MS
            i++
        }
        return out
    }

    // ── iPhone (its own scale: the reference scale, offset 0) ────────────────────────

    /** Resting at 20 to 30 cm: -55 to -65, smoothed about -60 (the log's words). */
    val IPHONE_REST_CYCLE = intArrayOf(-60, -57, -62, -59, -63, -58, -61, -60, -64, -56)

    /** The iPhone's own baseline was about -64 just before the first tap. */
    private val IPHONE_REST_BEFORE_TAP_CYCLE = intArrayOf(-64, -61, -66, -63, -67, -62, -65, -64, -68, -60)

    /** "Signal trace around the first tap", the listed raw values only. */
    val IPHONE_TAP_1_LISTED: List<Pt> = listOf(
        Pt(20946591, -51), Pt(20946714, -48), Pt(20946843, -47), Pt(20946969, -53), Pt(20947090, -48),
        Pt(20947480, -37), Pt(20947600, -42), Pt(20947722, -37), Pt(20947851, -35), Pt(20947990, -35),
        Pt(20948110, -36), Pt(20948362, -34), Pt(20948996, -32), Pt(20949126, -45), Pt(20949766, -61),
        Pt(20950647, -68), Pt(20951142, -76),
    )

    val IPHONE_TAP_1: List<Pt> = densify(IPHONE_TAP_1_LISTED)

    /** Where the iPhone log says "signal of #159 jumped" the second time, and where it fired (dwell). */
    const val IPHONE_TAP_1_JUMP_MS = 20947480L
    const val IPHONE_TAP_1_FIRED_MS = 20947990L

    /** The rest before the first tap (the log: baseline -64) followed by the first tap's listed points. */
    val IPHONE_FIRST_TAP: List<Pt> = rest(20_941_000L, 20946591L, IPHONE_REST_BEFORE_TAP_CYCLE) + IPHONE_TAP_1

    /** "Second tap": the listed points only, after a rest at -60. */
    val IPHONE_TAP_2: List<Pt> = listOf(
        Pt(20953148, -60), Pt(20953898, -54), Pt(20954401, -44), Pt(20954652, -36), Pt(20955019, -34),
        Pt(20955143, -32), Pt(20955639, -46), Pt(20957141, -65),
    )

    /** "Third approach (idle near)": flat around -57 to -63 for 3 s; here 10 s, to be sure. */
    val IPHONE_IDLE: List<Pt> = rest(20_958_500L, 20_968_500L, IPHONE_REST_CYCLE)

    // ── Pixel 10 Pro (raw, its own scale: about 15 dB hotter) ────────────────────────

    /** Resting at 20 to 30 cm: raw -40 to -48, smoothed -44 (the log's words). */
    val PIXEL_REST_CYCLE = intArrayOf(-44, -42, -46, -43, -47, -42, -45, -41, -46, -44)

    /** The logged first touch, raw: "Idle near ... -42 -42 -42", then "Approach and touch" and "Apart again". */
    val PIXEL_TOUCH_1_LISTED: List<Pt> = listOf(
        Pt(701272664, -42), Pt(701274390, -40), Pt(701275801, -27), Pt(701276643, -20), Pt(701277071, -19),
        Pt(701277459, -18), Pt(701278213, -44), Pt(701279799, -45),
    )

    val PIXEL_TOUCH_1: List<Pt> = densify(PIXEL_TOUCH_1_LISTED)

    /** The second touch ("the beam attempt"), raw. The device fired (dwell) at 701297202, 448 ms after its jump was seen. */
    val PIXEL_TOUCH_2_LISTED: List<Pt> = listOf(
        Pt(701295763, -46), Pt(701296194, -30), Pt(701296754, -18), Pt(701297202, -24),
    )

    val PIXEL_TOUCH_2: List<Pt> = densify(PIXEL_TOUCH_2_LISTED)

    const val PIXEL_TOUCH_1_FIRST_RISE_MS = 701275801L
    const val PIXEL_TOUCH_2_JUMP_MS = 701296754L

    /** The first listed reading of the second touch that is 12 dB or more above the rest (raw -30, rest about -44). */
    const val PIXEL_TOUCH_2_FIRST_RISE_MS = 701296194L

    /** Ten seconds of rest, the first touch, a long rest, the second touch. */
    val PIXEL_SESSION: List<Pt> =
        rest(701_262_664L - 10_000L, 701_272_664L, PIXEL_REST_CYCLE) +
            PIXEL_TOUCH_1 +
            rest(701_279_799L + CADENCE_MS, 701_295_763L, PIXEL_REST_CYCLE) +
            PIXEL_TOUCH_2

    /** Resting only (no touch): twelve seconds, for the "never fires at rest" checks. */
    val PIXEL_IDLE: List<Pt> = rest(701_202_002L, 701_214_002L, PIXEL_REST_CYCLE)

    /** The same constant smoothed level the log states for the Pixel at rest: -44. */
    val PIXEL_IDLE_CONSTANT: List<Pt> = rest(701_202_002L, 701_214_002L, intArrayOf(-44))

    // ── Running them ─────────────────────────────────────────────────────────────────

    class Fire(val tMs: Long, val reason: FireReason)

    class Run(val fires: List<Fire>, val near: List<Pair<Long, Boolean>>, val finalState: DetectorState)

    /** The pipeline of the app: raw + offset, then the detector with [profile]. */
    fun run(points: List<Pt>, offsetDb: Int, profile: SongTapSensitivity, impulseAt: (Long) -> Long = { -1L }): Run {
        var state = DetectorState.INITIAL
        val fires = ArrayList<Fire>()
        val near = ArrayList<Pair<Long, Boolean>>()
        for (p in points) {
            val step = SongTapDetector.step(state, RssiSample(p.t, p.raw + offsetDb), impulseAt(p.t), profile)
            state = step.state
            near += p.t to state.near
            if (step.fire != FireReason.NONE) fires += Fire(p.t, step.fire)
        }
        return Run(fires, near, state)
    }
}

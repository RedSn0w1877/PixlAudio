package com.theveloper.pixelplay.brain

/**
 * The only real-number functions the brain uses (docs/brain-spec.md, 5.1, 10.2, 10.3).
 *
 * `exp`, `ln` and `pow` of a platform maths library are not bit-identical between the JVM, ART and
 * Swift; `+ - * /`, `sqrt` and `floor` are. So everything transcendental is written out here, and
 * THE EXPRESSION ORDER IN THIS FILE IS THE DEFINITION: the Swift port copies it operation by
 * operation (no fused multiply-add), and `math.jsonl` pins the output bits.
 *
 * Nothing here can produce NaN from a finite input.
 */
object BrainMath {

    // Constants as IEEE bit patterns, so no decimal parser has a say in them.
    /** ln 2, nearest double. */
    val LN2: Double = Double.fromBits(0x3FE62E42FEFA39EFL)

    /** The high part of ln 2 (its low 21 mantissa bits are zero, so `k * LN2_HI` is exact for |k| < 2^21). */
    val LN2_HI: Double = Double.fromBits(0x3FE62E42FEE00000L)

    /** ln 2 - LN2_HI. */
    val LN2_LO: Double = Double.fromBits(0x3DEA39EF35793C76L)

    /** 1 / ln 2. */
    val INV_LN2: Double = Double.fromBits(0x3FF71547652B82FEL)

    /** sqrt 2. */
    val SQRT2: Double = Double.fromBits(0x3FF6A09E667F3BCDL)

    /** 2^20: the scale of Q20 counters and of every ordering key. */
    const val Q20: Double = 1048576.0

    /** 2^52: doubles are clipped to this before they become integers (10.3, rule 11). */
    const val TWO_POW_52: Double = 4503599627370496.0

    /** 2^40: the ceiling of a Q20 counter's real value. */
    const val TWO_POW_40: Double = 1099511627776.0

    /** 2^54, to lift a subnormal into the normal range. */
    private const val TWO_POW_54: Double = 18014398509481984.0

    private const val MANTISSA_MASK = 0x000FFFFFFFFFFFFFL
    private const val EXPONENT_ONE = 0x3FF0000000000000L

    // Series coefficients. A quotient of two small integers is one correctly rounded IEEE division,
    // the same on every platform.
    private val C3 = 1.0 / 3.0
    private val C5 = 1.0 / 5.0
    private val C7 = 1.0 / 7.0
    private val C9 = 1.0 / 9.0
    private val C11 = 1.0 / 11.0
    private val C13 = 1.0 / 13.0
    private val C15 = 1.0 / 15.0
    private val C17 = 1.0 / 17.0
    private val C19 = 1.0 / 19.0

    private val F2 = 1.0 / 2.0
    private val F3 = 1.0 / 6.0
    private val F4 = 1.0 / 24.0
    private val F5 = 1.0 / 120.0
    private val F6 = 1.0 / 720.0
    private val F7 = 1.0 / 5040.0
    private val F8 = 1.0 / 40320.0
    private val F9 = 1.0 / 362880.0
    private val F10 = 1.0 / 3628800.0
    private val F11 = 1.0 / 39916800.0
    private val F12 = 1.0 / 479001600.0
    private val F13 = 1.0 / 6227020800.0

    fun isFinite(x: Double): Boolean = x == x && x != Double.POSITIVE_INFINITY && x != Double.NEGATIVE_INFINITY

    /** `max(lo, min(hi, x))`; NaN becomes [lo]. */
    fun clip(x: Double, lo: Double, hi: Double): Double {
        if (x != x) return lo
        if (x < lo) return lo
        if (x > hi) return hi
        return x
    }

    fun min(a: Double, b: Double): Double = if (a < b) a else b

    fun max(a: Double, b: Double): Double = if (a > b) a else b

    fun abs(x: Double): Double = if (x < 0.0) -x else x

    /** Natural logarithm. A value that is not finite or not above zero gives -700. */
    fun ln(x: Double): Double {
        if (!isFinite(x) || x <= 0.0) return -700.0
        var v = x
        var e = 0
        var bits = v.toRawBits()
        if ((bits ushr 52).toInt() == 0) {
            // Subnormal: scale into the normal range first.
            v *= TWO_POW_54
            e = -54
            bits = v.toRawBits()
        }
        e += ((bits ushr 52) and 0x7FFL).toInt() - 1023
        var m = Double.fromBits((bits and MANTISSA_MASK) or EXPONENT_ONE) // in [1, 2)
        if (m >= SQRT2) {
            m *= 0.5 // exact
            e += 1
        }
        // m in [sqrt(2)/2, sqrt(2)).
        val s = (m - 1.0) / (m + 1.0)
        val t = s * s
        val poly = 1.0 + t * (C3 + t * (C5 + t * (C7 + t * (C9 + t * (C11 + t * (C13 + t * (C15 + t * (C17 + t * C19))))))))
        return e.toDouble() * LN2 + 2.0 * s * poly
    }

    /** e^x, with x clipped to [-700, 700]. A non-finite x gives 0. */
    fun exp(x: Double): Double {
        if (!isFinite(x)) return 0.0
        val c = clip(x, -700.0, 700.0)
        val kd = Math.floor(c * INV_LN2 + 0.5)
        val k = kd.toInt() // |k| <= 1010
        val r = (c - kd * LN2_HI) - kd * LN2_LO
        var p = F13
        p = p * r + F12
        p = p * r + F11
        p = p * r + F10
        p = p * r + F9
        p = p * r + F8
        p = p * r + F7
        p = p * r + F6
        p = p * r + F5
        p = p * r + F4
        p = p * r + F3
        p = p * r + F2
        p = p * r + 1.0
        p = p * r + 1.0
        // 2^k from its exponent bits; k is within the normal range for the clipped x.
        val scale = Double.fromBits((k + 1023).toLong() shl 52)
        return p * scale
    }

    /** The logistic function. A non-finite x gives 0. */
    fun sigmoid(x: Double): Double {
        if (!isFinite(x)) return 0.0
        if (x >= 0.0) return 1.0 / (1.0 + exp(-x))
        val e = exp(x)
        return e / (1.0 + e)
    }

    fun log2(x: Double): Double = ln(x) * INV_LN2

    /** x^(-d) for x > 0. */
    fun powNeg(x: Double, d: Double): Double = exp(-d * ln(x))

    /** `ln(p / (1 - p))`. */
    fun logit(p: Double): Double = ln(p / (1.0 - p))

    fun sqrt(x: Double): Double = Math.sqrt(x)

    // ── Double to integer (10.3, rule 11) ────────────────────────────────────

    /** `floor(x)` as a Long: not finite gives 0; clipped to [-2^52, 2^52] first. */
    fun floorToLong(x: Double): Long {
        if (!isFinite(x)) return 0L
        return Math.floor(clip(x, -TWO_POW_52, TWO_POW_52)).toLong()
    }

    /** A real value as a Q20 counter: `floor(x * 2^20 + 0.5)`, x clipped to [0, 2^40] first. */
    fun toQ(x: Double): Long {
        if (!isFinite(x)) return 0L
        return Math.floor(clip(x, 0.0, TWO_POW_40) * Q20 + 0.5).toLong()
    }

    fun fromQ(q: Long): Double = q.toDouble() / Q20

    /** The ordering key of a score (10.3, rule 4): `floor(score * 2^20 + 0.5)`. */
    fun orderKey(score: Double): Long = floorToLong(score * Q20 + 0.5)

    // ── Integer division (10.3, rule 12) ─────────────────────────────────────

    fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        if ((a % b != 0L) && ((a < 0L) != (b < 0L))) q -= 1
        return q
    }

    fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

    const val DAY_MS: Long = 86_400_000L

    /** The local calendar day of [ms] (days since 1970-01-01 local). */
    fun localDay(ms: Long, tzOffsetMin: Int): Long = floorDiv(ms + tzOffsetMin * 60_000L, DAY_MS)

    /** 0 = Monday ... 6 = Sunday. 1970-01-01 was a Thursday. */
    fun weekday(localDay: Long): Int = floorMod(localDay + 3L, 7L).toInt()

    fun isWeekend(ms: Long, tzOffsetMin: Int): Boolean = weekday(localDay(ms, tzOffsetMin)) >= 5

    /** Unsigned comparison of two 64-bit hashes held in signed Longs (10.3, rule 10). */
    fun compareUnsigned(a: Long, b: Long): Int = java.lang.Long.compareUnsigned(a, b)
}

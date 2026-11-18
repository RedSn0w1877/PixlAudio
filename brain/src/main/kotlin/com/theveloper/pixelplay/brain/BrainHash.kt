package com.theveloper.pixelplay.brain

/**
 * The only hashing and the only randomness the brain uses (docs/brain-spec.md, 10.3 rules 5, 10, 14).
 *
 * Hashes are unsigned 64-bit values held in a signed [Long]: use [java.lang.Long.remainderUnsigned]
 * and [java.lang.Long.compareUnsigned] on them, never `%` or `<`. A hash becomes an id by reusing
 * its bits as a signed Long.
 */
object BrainHash {

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    private const val SPLITMIX_GAMMA = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
    private const val SPLITMIX_M1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
    private const val SPLITMIX_M2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB

    /** 2^-53, the weight of one unit in the last place of a double in [0, 1). */
    private const val TWO_POW_MINUS_53 = 1.0 / 9007199254740992.0

    /** FNV-1a, 64-bit, over raw bytes. */
    fun fnv1a64(bytes: ByteArray): Long {
        var h = FNV_OFFSET
        for (b in bytes) {
            h = h xor (b.toLong() and 0xffL)
            h *= FNV_PRIME
        }
        return h
    }

    /** FNV-1a over the UTF-8 bytes of [text]. Lone surrogates encode as `?` (0x3F), as the JVM does. */
    fun fnv1a64(text: String): Long = fnv1a64(text.toByteArray(Charsets.UTF_8))

    /** The SplitMix64 output for the state AFTER it has been advanced by the gamma. */
    fun mix(advancedState: Long): Long {
        var z = advancedState
        z = (z xor (z ushr 30)) * SPLITMIX_M1
        z = (z xor (z ushr 27)) * SPLITMIX_M2
        return z xor (z ushr 31)
    }

    /** One SplitMix64 step from [state]: advance by the gamma, then mix. Stateless form. */
    fun splitmix64(state: Long): Long = mix(state + SPLITMIX_GAMMA)

    /** The top 53 bits of [h] as a number in [0, 1): `(h >>> 11) * 2^-53`. */
    fun unit(h: Long): Double = (h ushr 11).toDouble() * TWO_POW_MINUS_53

    /** A SplitMix64 stream. [uniform] advances the state once; [gaussian12] twelve times. */
    class Rng(seed: Long) {
        var state: Long = seed
            private set

        fun nextLong(): Long {
            state += SPLITMIX_GAMMA
            return mix(state)
        }

        /** Uniform in [0, 1): the top 53 bits times 2^-53. */
        fun uniform(): Double = (nextLong() ushr 11).toDouble() * TWO_POW_MINUS_53

        /** Sum of twelve uniforms minus six: mean 0, variance 1, no transcendental function. */
        fun gaussian12(): Double {
            var sum = 0.0
            repeat(12) { sum += uniform() }
            return sum - 6.0
        }
    }
}

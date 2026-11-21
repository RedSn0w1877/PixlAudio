package com.theveloper.pixelplay.songtap.ring

/** What [ReedSolomon.decode] found. [codeword] is the corrected word (25 bytes) when [ok]. */
class RsDecode(val ok: Boolean, val codeword: ByteArray?, val corrected: Int, val erasuresUsed: Int) {
    companion object {
        val FAILED = RsDecode(false, null, 0, 0)
    }
}

/**
 * Reed-Solomon RS(25, 11) over GF(256): 11 data bytes, 14 parity bytes, minimum distance 15. It corrects
 * `e` unknown byte errors and `s` known-position erasures whenever `2e + s <= 14`: 7 errors, or 14
 * erasures, or any mix. Systematic: a codeword is the data followed by the parity. The generator has the
 * roots alpha^0 .. alpha^13. Integers only, no allocation beyond the small work arrays.
 *
 * The scanner uses the erasure form: a slot it could not see (a smudge, a glare patch) is flagged, and a
 * flagged byte costs one parity byte instead of two.
 */
object ReedSolomon {
    const val N = 25
    const val K = 11
    const val NSYM = N - K

    /** Generator polynomial, highest degree first, monic, degree 14 (15 coefficients). */
    private val generator: IntArray = run {
        var g = intArrayOf(1)
        for (i in 0 until NSYM) {
            val root = Gf256.pow(i)
            val next = IntArray(g.size + 1)
            for (j in g.indices) {
                next[j] = next[j] xor g[j]
                next[j + 1] = next[j + 1] xor Gf256.mul(g[j], root)
            }
            g = next
        }
        g
    }

    /** The 14 parity bytes of [data] (11 bytes), as a new 25-byte codeword. */
    fun encode(data: ByteArray): ByteArray {
        require(data.size == K) { "RS(25,11) takes 11 data bytes, got ${data.size}" }
        val rem = IntArray(NSYM)
        for (i in 0 until K) {
            val feedback = (data[i].toInt() and 0xFF) xor rem[0]
            for (j in 0 until NSYM - 1) rem[j] = rem[j + 1] xor Gf256.mul(feedback, generator[j + 1])
            rem[NSYM - 1] = Gf256.mul(feedback, generator[NSYM])
        }
        val out = ByteArray(N)
        for (i in 0 until K) out[i] = data[i]
        for (i in 0 until NSYM) out[K + i] = rem[i].toByte()
        return out
    }

    private fun syndromes(word: IntArray, out: IntArray): Boolean {
        var clean = true
        for (i in 0 until NSYM) {
            val x = Gf256.pow(i)
            var acc = 0
            for (j in 0 until N) acc = Gf256.mul(acc, x) xor word[j]
            out[i] = acc
            if (acc != 0) clean = false
        }
        return clean
    }

    /** True when [word] (25 bytes) is a codeword. */
    fun isCodeword(word: ByteArray): Boolean {
        if (word.size != N) return false
        val w = IntArray(N) { word[it].toInt() and 0xFF }
        return syndromes(w, IntArray(NSYM))
    }

    /**
     * Corrects [received] (25 bytes). [erasures] lists byte positions (0..24, no duplicates) the caller
     * does not trust; the values there are ignored. Returns [RsDecode.FAILED] when the word is further
     * than the code can repair; the result is verified (its syndromes are zero) before it is returned.
     */
    fun decode(received: ByteArray, erasures: IntArray = IntArray(0)): RsDecode {
        if (received.size != N) return RsDecode.FAILED
        val s = erasures.size
        if (s > NSYM) return RsDecode.FAILED
        val seen = BooleanArray(N)
        for (p in erasures) {
            if (p !in 0 until N || seen[p]) return RsDecode.FAILED
            seen[p] = true
        }
        val word = IntArray(N) { if (seen[it]) 0 else received[it].toInt() and 0xFF }
        val synd = IntArray(NSYM)
        if (syndromes(word, synd)) return RsDecode(true, received.copyOf().also { for (p in erasures) it[p] = word[p].toByte() }, 0, s)

        // Erasure locator, lowest degree first: product of (1 + alpha^p x), p = N - 1 - position.
        var gamma = intArrayOf(1)
        for (pos in erasures) {
            val x = Gf256.pow(N - 1 - pos)
            val next = IntArray(gamma.size + 1)
            for (i in gamma.indices) {
                next[i] = next[i] xor gamma[i]
                next[i + 1] = next[i + 1] xor Gf256.mul(gamma[i], x)
            }
            gamma = next
        }

        // Modified syndromes T(x) = S(x) * Gamma(x) mod x^14; the error-only recurrence lives in T[s..13].
        val t = IntArray(NSYM)
        for (i in 0 until NSYM) {
            var acc = 0
            for (j in gamma.indices) if (i - j >= 0) acc = acc xor Gf256.mul(gamma[j], synd[i - j])
            t[i] = acc
        }

        // Berlekamp-Massey on T[s..13].
        val m = NSYM - s
        var c = intArrayOf(1)
        var b = intArrayOf(1)
        var l = 0
        var shift = 1
        var bd = 1
        for (n in 0 until m) {
            var d = t[s + n]
            for (i in 1..l) if (i < c.size) d = d xor Gf256.mul(c[i], t[s + n - i])
            if (d == 0) {
                shift++
            } else {
                val coef = Gf256.div(d, bd)
                val size = maxOf(c.size, b.size + shift)
                val nc = IntArray(size)
                for (i in c.indices) nc[i] = c[i]
                for (i in b.indices) nc[i + shift] = nc[i + shift] xor Gf256.mul(coef, b[i])
                if (2 * l <= n) {
                    b = c
                    bd = d
                    l = n + 1 - l
                    shift = 1
                } else {
                    shift++
                }
                c = nc
            }
        }
        if (2 * l > m) return RsDecode.FAILED

        // Total locator Psi = Lambda * Gamma (lowest degree first), trimmed.
        val psiRaw = IntArray(c.size + gamma.size - 1)
        for (i in c.indices) for (j in gamma.indices) psiRaw[i + j] = psiRaw[i + j] xor Gf256.mul(c[i], gamma[j])
        var degree = psiRaw.size - 1
        while (degree > 0 && psiRaw[degree] == 0) degree--
        if (degree == 0) return RsDecode.FAILED
        val psi = psiRaw.copyOf(degree + 1)

        // Chien search: position j has locator X = alpha^(N-1-j); it is a root when Psi(X^-1) = 0.
        val errorPos = IntArray(degree)
        var found = 0
        for (j in 0 until N) {
            val xInv = Gf256.pow(-(N - 1 - j))
            var acc = 0
            for (i in degree downTo 0) acc = Gf256.mul(acc, xInv) xor psi[i]
            if (acc == 0) {
                if (found == degree) return RsDecode.FAILED
                errorPos[found++] = j
            }
        }
        if (found != degree) return RsDecode.FAILED
        for (p in erasures) {
            var has = false
            for (k in 0 until found) if (errorPos[k] == p) has = true
            if (!has) return RsDecode.FAILED
        }

        // Evaluator Omega = S * Psi mod x^14; Forney (first root alpha^0): e = X * Omega(X^-1) / Psi'(X^-1).
        val omega = IntArray(NSYM)
        for (i in 0 until NSYM) {
            var acc = 0
            for (j in psi.indices) if (i - j >= 0) acc = acc xor Gf256.mul(psi[j], synd[i - j])
            omega[i] = acc
        }
        val fixed = word.copyOf()
        for (k in 0 until found) {
            val pos = errorPos[k]
            val x = Gf256.pow(N - 1 - pos)
            val xInv = Gf256.inv(x)
            var num = 0
            for (i in NSYM - 1 downTo 0) num = Gf256.mul(num, xInv) xor omega[i]
            var den = 0
            var i = 1
            while (i <= degree) {
                if (i < psi.size) den = den xor Gf256.mul(psi[i], powOf(xInv, i - 1))
                i += 2
            }
            if (den == 0) return RsDecode.FAILED
            val magnitude = Gf256.mul(x, Gf256.div(num, den))
            fixed[pos] = fixed[pos] xor magnitude
        }
        if (!syndromes(fixed, IntArray(NSYM))) return RsDecode.FAILED
        val out = ByteArray(N) { fixed[it].toByte() }
        var changed = 0
        for (k in 0 until found) {
            val pos = errorPos[k]
            if (!seen[pos] && (out[pos].toInt() and 0xFF) != (received[pos].toInt() and 0xFF)) changed++
        }
        return RsDecode(true, out, changed, s)
    }

    private fun powOf(x: Int, n: Int): Int {
        var r = 1
        for (i in 0 until n) r = Gf256.mul(r, x)
        return r
    }
}

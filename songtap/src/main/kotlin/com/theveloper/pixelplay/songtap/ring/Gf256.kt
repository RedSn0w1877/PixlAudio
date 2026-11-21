package com.theveloper.pixelplay.songtap.ring

/**
 * GF(2^8) with the primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D) and the generator alpha = 2,
 * the field of QR codes and CD audio. Integers only; the tables are built from the polynomial, so the
 * Swift port reproduces them without a copied table.
 */
object Gf256 {
    const val PRIMITIVE = 0x11D

    /** exp[i] = alpha^i for i in 0..509 (doubled, so a product of two logs needs no modulo). */
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor PRIMITIVE
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    fun add(a: Int, b: Int): Int = a xor b

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    fun div(a: Int, b: Int): Int {
        require(b != 0) { "division by zero in GF(256)" }
        return if (a == 0) 0 else exp[log[a] + 255 - log[b]]
    }

    fun inv(a: Int): Int {
        require(a != 0) { "zero has no inverse" }
        return exp[255 - log[a]]
    }

    /** alpha^n for any integer n (negative allowed). */
    fun pow(n: Int): Int {
        var k = n % 255
        if (k < 0) k += 255
        return exp[k]
    }

    fun logOf(a: Int): Int {
        require(a != 0) { "log of zero" }
        return log[a]
    }
}

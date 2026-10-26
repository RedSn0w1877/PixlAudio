package com.theveloper.pixelplay.data.ai.claude

import com.theveloper.pixelplay.BuildConfig

/**
 * The built-in Claude key. CI puts the ANTHROPIC_API_KEY secret into BuildConfig as two XOR
 * shares (app/build.gradle.kts): [BuildConfig.ANTHROPIC_KEY_A] = key XOR pad and
 * [BuildConfig.ANTHROPIC_KEY_B] = pad, both hex. They are joined only here, in memory, and the
 * result is never logged, shown or written anywhere.
 *
 * No secret at build time (forks, pull requests, local builds without it) means [get] is null:
 * cloud AI is unavailable and every feature uses the on-device model.
 */
object BuiltInClaudeKey {
    @Volatile
    private var resolved = false

    @Volatile
    private var cached: String? = null

    fun get(): String? {
        if (!resolved) {
            cached = decode(BuildConfig.ANTHROPIC_KEY_A, BuildConfig.ANTHROPIC_KEY_B)
            resolved = true
        }
        return cached
    }

    fun isPresent(): Boolean = get() != null

    /** Joins the two shares; null when either is missing or malformed. Pure (BuiltInClaudeKeyTest). */
    internal fun decode(masked: String, pad: String): String? {
        if (masked.isEmpty() || masked.length != pad.length || masked.length % 2 != 0) return null
        val a = hexToBytes(masked) ?: return null
        val b = hexToBytes(pad) ?: return null
        val plain = ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
        return String(plain, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

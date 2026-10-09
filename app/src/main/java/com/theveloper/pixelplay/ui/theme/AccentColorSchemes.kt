package com.theveloper.pixelplay.ui.theme

import com.theveloper.pixelplay.presentation.viewmodel.ColorSchemePair
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide memo of [generateAccentColorSchemePair], keyed by the opaque seed.
 *
 * Two reasons. A pair costs a few milliseconds to build, and the app, the widgets (MusicService) and
 * the watch transfer each ask for the same one. And material3's `ColorScheme` has no `equals`, so a
 * rebuilt pair is a "new" scheme to every `remember` key and `distinctUntilChanged` that sees it: the
 * same seed must hand back the same instances, or an unrelated settings write would recompose the
 * whole app (the root `LocalColorScheme` is static).
 *
 * A plain [ConcurrentHashMap] cleared past [MAX_ENTRIES], not `android.util.LruCache` (a stub in JVM
 * tests). Only presets and the few custom colours someone applies land here; the custom picker's
 * live preview calls [generateAccentColorSchemePair] directly so drag steps never fill it. Call off
 * the main thread.
 */
object AccentColorSchemes {
    private const val MAX_ENTRIES = 24
    private val cache = ConcurrentHashMap<Int, ColorSchemePair>()

    /**
     * Version of the maths in [generateAccentColorSchemePair]. The generated pair is also kept on
     * disk (see [AccentSchemeCodec]) so a cold start does not rebuild it through material-color-
     * utilities; BUMP THIS whenever that function's output could change, so old caches are ignored.
     */
    const val ALGORITHM_VERSION = 1

    /** The pair already built in this process for [seedArgb], or null. Never generates. */
    fun memoized(seedArgb: Int): ColorSchemePair? = cache[seedArgb or (0xFF shl 24)]

    /** Records a pair built elsewhere (decoded from the disk cache); returns the instance to use. */
    fun prime(seedArgb: Int, pair: ColorSchemePair): ColorSchemePair {
        val key = seedArgb or (0xFF shl 24)
        cache[key]?.let { return it }
        if (cache.size >= MAX_ENTRIES) cache.clear()
        return cache.putIfAbsent(key, pair) ?: pair
    }

    fun pairFor(seedArgb: Int): ColorSchemePair {
        val key = seedArgb or (0xFF shl 24)
        cache[key]?.let { return it }
        val pair = generateAccentColorSchemePair(key)
        if (cache.size >= MAX_ENTRIES) cache.clear()
        // Two threads may build the same seed at once; both get the first one stored.
        return cache.putIfAbsent(key, pair) ?: pair
    }
}

/**
 * The accent pair as one string for the settings store: `version;#RRGGBB;<48 light ARGB>;<48 dark ARGB>`.
 * Decoding is a plain [ColorScheme] constructor call, no material-color-utilities, so a cold start
 * with a custom accent no longer spends ~100 ms (class loading + interpreted maths) rebuilding it
 * before the splash can go. The values are the very ints the generator produced, so the colours are
 * identical. Anything missing, stale (other accent or algorithm version) or malformed decodes to null
 * and the caller just generates as before.
 */
object AccentSchemeCodec {
    fun encode(hex: String, pair: ColorSchemePair): String =
        buildString {
            append(AccentColorSchemes.ALGORITHM_VERSION).append(';').append(hex).append(';')
            append(pair.light.toArgbArray().joinToString(","))
            append(';')
            append(pair.dark.toArgbArray().joinToString(","))
        }

    fun decode(raw: String?, hex: String): ColorSchemePair? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split(';')
        if (parts.size != 4) return null
        if (parts[0] != AccentColorSchemes.ALGORITHM_VERSION.toString() || parts[1] != hex) return null
        val light = parseColors(parts[2]) ?: return null
        val dark = parseColors(parts[3]) ?: return null
        return ColorSchemePair(light = colorSchemeFromArgb(light), dark = colorSchemeFromArgb(dark))
    }

    private fun parseColors(text: String): IntArray? {
        val values = text.split(',')
        if (values.size != COLOR_SCHEME_ROLE_COUNT) return null
        val out = IntArray(values.size)
        for (i in values.indices) out[i] = values[i].toIntOrNull() ?: return null
        return out
    }
}

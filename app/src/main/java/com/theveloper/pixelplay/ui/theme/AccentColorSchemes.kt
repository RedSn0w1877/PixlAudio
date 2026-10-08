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

    fun pairFor(seedArgb: Int): ColorSchemePair {
        val key = seedArgb or (0xFF shl 24)
        cache[key]?.let { return it }
        val pair = generateAccentColorSchemePair(key)
        if (cache.size >= MAX_ENTRIES) cache.clear()
        // Two threads may build the same seed at once; both get the first one stored.
        return cache.putIfAbsent(key, pair) ?: pair
    }
}

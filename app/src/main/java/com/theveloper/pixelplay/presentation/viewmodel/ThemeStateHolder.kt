package com.theveloper.pixelplay.presentation.viewmodel

import android.os.SystemClock
import android.net.Uri
import android.content.ComponentCallbacks2
import android.os.Trace
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.theveloper.pixelplay.data.preferences.AccentColor
import com.theveloper.pixelplay.data.preferences.AlbumArtColorAccuracy
import com.theveloper.pixelplay.data.preferences.AlbumArtPaletteStyle
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.di.AppScope
import com.theveloper.pixelplay.ui.theme.AccentColorSchemes
import com.theveloper.pixelplay.ui.theme.AccentSchemeCodec
import com.theveloper.pixelplay.ui.theme.LightColorScheme
import com.theveloper.pixelplay.ui.theme.DarkColorScheme
import com.theveloper.pixelplay.ui.theme.clearExtractedColorCache
import com.theveloper.pixelplay.ui.theme.hasSameColorsAs
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-wide accent (Settings › Appearance › Accent Color) as stored ([hex], `"#RRGGBB"` or `""`)
 * and its scheme pair. A null [pair] is the default, "Dynamic": Material You on API 31+, the static
 * scheme on API 30. Pairs come from `AccentColorSchemes`, so equal accents are equal instances.
 */
@Immutable
data class AccentScheme(val hex: String, val pair: ColorSchemePair?) {
    companion object {
        val Default = AccentScheme(AccentColor.DEFAULT, null)
    }
}

@Singleton
class ThemeStateHolder @Inject constructor(
    private val colorSchemeProcessor: ColorSchemeProcessor,
    private val themePreferencesRepository: ThemePreferencesRepository,
    @AppScope private val appScope: CoroutineScope
) {

    /**
     * The app-wide accent scheme. Null until the first DataStore read resolves: MainActivity keeps
     * the splash up until then (bounded by its deadline), so a chosen accent is on the first frame
     * instead of a Material You flash. Built on Default (a pair costs a few ms, ~100 ms the very
     * first time) and shared process-wide, independent of [initialize], so the activity can read it
     * before PlayerViewModel exists. A restored backup re-emits through the DataStore, so the app
     * re-tints live. A failed read falls back to the default rather than holding the splash.
     */
    val accentScheme: StateFlow<AccentScheme?> = themePreferencesRepository.accentColorFlow
        .map { hex ->
            val seed = AccentColor.seedOrNull(hex)
            if (seed == null) AccentScheme.Default else AccentScheme(hex, accentPairFor(hex, seed))
        }
        .flowOn(Dispatchers.Default)
        .catch { emit(AccentScheme.Default) }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    /**
     * The pair for a chosen accent. A cold start used to rebuild it through material-color-utilities
     * every time (~100 ms with the splash waiting); now the pair saved the first time is decoded
     * with the plain ColorScheme constructor. Order: the in-process memo, then the saved copy, then
     * generate (and save, off the splash's critical path, for the next launch).
     */
    private suspend fun accentPairFor(hex: String, seed: Int): ColorSchemePair {
        val saved = runCatching {
            AccentSchemeCodec.decode(themePreferencesRepository.accentSchemeCache(), hex)
        }.getOrNull()
        val pair = AccentColorSchemes.memoized(seed)
            ?: saved?.let { AccentColorSchemes.prime(seed, it) }
            ?: AccentColorSchemes.pairFor(seed)
        // generateAccentColorSchemePair swallows failures into the static scheme: never save that one.
        if (saved == null && pair.light !== LightColorScheme) {
            appScope.launch {
                runCatching { themePreferencesRepository.setAccentSchemeCache(AccentSchemeCodec.encode(hex, pair)) }
            }
        }
        return pair
    }

    private var scope: CoroutineScope? = null
    @Volatile
    private var currentPaletteStyle: AlbumArtPaletteStyle = AlbumArtPaletteStyle.default
    @Volatile
    private var currentPaletteAccuracy: Int = AlbumArtColorAccuracy.DEFAULT

    private val _currentAlbumArtColorSchemePair = MutableStateFlow<ColorSchemePair?>(null)
    val currentAlbumArtColorSchemePair: StateFlow<ColorSchemePair?> = _currentAlbumArtColorSchemePair.asStateFlow()
    private val _currentAlbumArtUri = MutableStateFlow<String?>(null)
    val currentAlbumArtUri: StateFlow<String?> = _currentAlbumArtUri.asStateFlow()

    private val _lavaLampColors = MutableStateFlow<ImmutableList<Color>>(persistentListOf())
    val lavaLampColors: StateFlow<ImmutableList<Color>> = _lavaLampColors.asStateFlow()

    private val playerThemePreference = themePreferencesRepository.playerThemePreferenceFlow

    private val _activePlayerColorSchemePair = MutableStateFlow<ColorSchemePair?>(null)
    val activePlayerColorSchemePair: StateFlow<ColorSchemePair?> = _activePlayerColorSchemePair.asStateFlow()

    fun initialize(scope: CoroutineScope) {
        this.scope = scope

        // Drive activePlayerColorSchemePair from the proper lifecycle-scoped coroutine
        // instead of the orphaned placeholder CoroutineScope used during field initialisation.
        scope.launch {
            combine(playerThemePreference, _currentAlbumArtColorSchemePair) { playerPref, albumScheme ->
                when (playerPref) {
                    com.theveloper.pixelplay.data.preferences.ThemePreference.ALBUM_ART -> albumScheme
                    else -> null
                }
            }.collect { _activePlayerColorSchemePair.value = it }
        }

        scope.launch {
            combine(
                themePreferencesRepository.albumArtPaletteStyleFlow,
                themePreferencesRepository.albumArtColorAccuracyFlow
            ) { style, accuracy -> style to accuracy }
                .collect { (style, accuracy) ->
                    val paletteChanged =
                        currentPaletteStyle != style || currentPaletteAccuracy != accuracy
                    currentPaletteStyle = style
                    currentPaletteAccuracy = accuracy

                    if (!paletteChanged) return@collect

                    val uri = _currentAlbumArtUri.value ?: return@collect
                    val refreshedScheme = colorSchemeProcessor.getOrGenerateColorScheme(
                        albumArtUri = uri,
                        paletteStyle = style,
                        colorAccuracyLevel = accuracy
                    )
                    _currentAlbumArtColorSchemePair.value = refreshedScheme
                    synchronized(individualAlbumColorSchemes) { individualAlbumColorSchemes[uri] }?.value = refreshedScheme
                }
        }

        scope.launch {
            activePlayerColorSchemePair.collect { schemePair ->
                 updateLavaLampColors(schemePair)
            }
        }
    }

    suspend fun extractAndGenerateColorScheme(albumArtUriAsUri: Uri?, currentSongUriString: String?, isPreload: Boolean = false) {
        Trace.beginSection("ThemeStateHolder.extractAndGenerateColorScheme")
        try {
            if (albumArtUriAsUri == null) {
                if (!isPreload && currentSongUriString == null) {
                    _currentAlbumArtColorSchemePair.value = null
                    _currentAlbumArtUri.value = null
                }
                return
            }

            val uriString = albumArtUriAsUri.toString()
            // Use the optimized ColorSchemeProcessor with LRU cache
            val schemePair = colorSchemeProcessor.getOrGenerateColorScheme(
                albumArtUri = uriString,
                paletteStyle = currentPaletteStyle,
                colorAccuracyLevel = currentPaletteAccuracy
            )

            if (!isPreload && currentSongUriString == uriString) {
                // Consecutive tracks of one album give a new ColorSchemePair with exactly the same
                // colours (ColorScheme has no equals, so it looks "new"). Handing that out would
                // restart the player's colour fade and recompose every themed widget for ~40 frames
                // without changing a pixel: keep the instance already published, move only the URI.
                val published = _currentAlbumArtColorSchemePair.value
                if (published == null || schemePair == null || !published.hasSameColorsAs(schemePair)) {
                    _currentAlbumArtColorSchemePair.value = schemePair
                }
                _currentAlbumArtUri.value = uriString
            }
        } catch (e: Exception) {
            if (!isPreload && albumArtUriAsUri != null && currentSongUriString == albumArtUriAsUri.toString()) {
                _currentAlbumArtColorSchemePair.value = null
                _currentAlbumArtUri.value = null
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun updateLavaLampColors(schemePair: ColorSchemePair?) {
        val schemeForLava = schemePair?.dark ?: DarkColorScheme
        _lavaLampColors.update {
            listOf(schemeForLava.primary, schemeForLava.secondary, schemeForLava.tertiary).distinct().toImmutableList()
        }
    }

    // LRU Cache for individual album schemes. Access-ordered, so even get() mutates it: every
    // access goes through synchronized(individualAlbumColorSchemes) because it is touched from
    // composition (main) and from background collectors.
    private val individualAlbumColorSchemes = object : LinkedHashMap<String, MutableStateFlow<ColorSchemePair?>>(
        32, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableStateFlow<ColorSchemePair?>>?): Boolean {
            return size > 96
        }
    }

    private val emptyAlbumColorScheme = MutableStateFlow<ColorSchemePair?>(null).asStateFlow()
    private val pendingAlbumColorSchemeLock = Any()
    private val pendingAlbumColorSchemeTargets = mutableMapOf<String, MutableSet<MutableStateFlow<ColorSchemePair?>>>()

    // How many composed tiles currently show each album's scheme (see retainAlbumColorSchemeTile).
    // Composition lifetime, not collection: a collector pauses while the app is in the
    // background, but a composed tile still needs its colours when the app comes back.
    private val composedTileCounts = HashMap<String, Int>()

    /** Called by a list/grid tile when it enters composition; pair with [releaseAlbumColorSchemeTile]. */
    fun retainAlbumColorSchemeTile(uriString: String) {
        if (uriString.isBlank()) return
        synchronized(pendingAlbumColorSchemeLock) {
            composedTileCounts[uriString] = (composedTileCounts[uriString] ?: 0) + 1
        }
    }

    fun releaseAlbumColorSchemeTile(uriString: String) {
        if (uriString.isBlank()) return
        synchronized(pendingAlbumColorSchemeLock) {
            val remaining = (composedTileCounts[uriString] ?: 0) - 1
            if (remaining > 0) composedTileCounts[uriString] = remaining else composedTileCounts.remove(uriString)
        }
    }

    // Pending requests that a non-droppable caller also waits for; never skipped.
    private val pinnedAlbumColorSchemeRequests = HashSet<String>()

    /** True while a composed tile still shows [uriString], or a non-droppable caller waits for it. */
    private fun isAlbumColorSchemeStillWanted(uriString: String): Boolean =
        synchronized(pendingAlbumColorSchemeLock) {
            uriString in pinnedAlbumColorSchemeRequests || (composedTileCounts[uriString] ?: 0) > 0
        }

    /**
     * @param droppable true for list/grid tiles that call [retainAlbumColorSchemeTile] while
     * composed: if the request waited for a generation slot for longer than a frame or two and no
     * composed tile shows it any more (it scrolled away), the decode + quantization is skipped.
     * The flow stays null, so a tile that is composed again asks again.
     */
    private fun requestAlbumColorSchemeGeneration(
        uriString: String,
        targetFlow: MutableStateFlow<ColorSchemePair?>,
        droppable: Boolean = false
    ) {
        if (uriString.isBlank()) return

        val shouldStartRequest = synchronized(pendingAlbumColorSchemeLock) {
            // Anyone who can't be dropped (album detail, the metadata editor...) pins the request,
            // even if it joined one a grid tile started.
            if (!droppable) pinnedAlbumColorSchemeRequests.add(uriString)
            val existingTargets = pendingAlbumColorSchemeTargets[uriString]
            if (existingTargets != null) {
                existingTargets.add(targetFlow)
                false
            } else {
                pendingAlbumColorSchemeTargets[uriString] = mutableSetOf(targetFlow)
                true
            }
        }

        if (!shouldStartRequest) return

        val requestScope = scope
        if (requestScope == null) {
            synchronized(pendingAlbumColorSchemeLock) {
                pendingAlbumColorSchemeTargets.remove(uriString)
                pinnedAlbumColorSchemeRequests.remove(uriString)
            }
            return
        }

        val requestedAtMs = SystemClock.uptimeMillis()
        requestScope.launch(Dispatchers.IO) {
            var scheme: ColorSchemePair? = null
            try {
                scheme = colorSchemeProcessor.getOrGenerateColorScheme(
                    albumArtUri = uriString,
                    paletteStyle = currentPaletteStyle,
                    colorAccuracyLevel = currentPaletteAccuracy,
                    throttled = true,
                    stillWanted = if (droppable) {
                        {
                            // A tile registers (retainAlbumColorSchemeTile) within a frame of
                            // asking, so only a request that has waited well past that can be
                            // judged abandoned.
                            SystemClock.uptimeMillis() - requestedAtMs < DROPPABLE_REQUEST_GRACE_MS ||
                                isAlbumColorSchemeStillWanted(uriString)
                        }
                    } else {
                        null
                    }
                )
            } catch (_: Exception) {
                // Ignore or log
            } finally {
                val targets = synchronized(pendingAlbumColorSchemeLock) {
                    pinnedAlbumColorSchemeRequests.remove(uriString)
                    pendingAlbumColorSchemeTargets.remove(uriString)?.toList().orEmpty()
                }
                targets.forEach { it.value = scheme }
            }
        }
    }

    /**
     * @param droppable pass true only from list/grid tiles that also call
     * [retainAlbumColorSchemeTile] / [releaseAlbumColorSchemeTile] around their composition;
     * see [requestAlbumColorSchemeGeneration].
     */
    fun getAlbumColorSchemeFlow(
        uriString: String,
        eager: Boolean = true,
        droppable: Boolean = false
    ): StateFlow<ColorSchemePair?> {
        if (uriString.isBlank()) return emptyAlbumColorScheme

        val flow = synchronized(individualAlbumColorSchemes) {
            individualAlbumColorSchemes.getOrPut(uriString) { MutableStateFlow(peekMemoryScheme(uriString)) }
        }
        if (eager && flow.value == null) {
            requestAlbumColorSchemeGeneration(uriString, flow, droppable)
        }
        return flow.asStateFlow()
    }

    /**
     * A scheme already in the processor's memory cache, read synchronously. Seeding a new tile flow
     * with it lets the tile compose once with its final colours instead of default colours first
     * and a recomposition (or a 280 ms colour animation) a frame later.
     */
    private fun peekMemoryScheme(uriString: String): ColorSchemePair? =
        colorSchemeProcessor.peekMemory(
            albumArtUri = uriString,
            paletteStyle = currentPaletteStyle,
            colorAccuracyLevel = currentPaletteAccuracy
        )

    fun ensureAlbumColorScheme(uriString: String) {
        if (uriString.isBlank()) return

        val targetFlow = synchronized(individualAlbumColorSchemes) {
            individualAlbumColorSchemes.getOrPut(uriString) { MutableStateFlow(peekMemoryScheme(uriString)) }
        }

        if (targetFlow.value != null) return
        requestAlbumColorSchemeGeneration(uriString, targetFlow)
    }
    
    /** The scheme for [uriString] from the memory or Room cache only; never decodes or generates. */
    suspend fun peekCachedColorScheme(uriString: String): ColorSchemePair? =
        colorSchemeProcessor.peekCachedColorScheme(
            albumArtUri = uriString,
            paletteStyle = currentPaletteStyle,
            colorAccuracyLevel = currentPaletteAccuracy
        )

    suspend fun getOrGenerateColorScheme(uriString: String): ColorSchemePair? {
         return colorSchemeProcessor.getOrGenerateColorScheme(
             albumArtUri = uriString,
             paletteStyle = currentPaletteStyle,
             colorAccuracyLevel = currentPaletteAccuracy
         )
    }

    suspend fun forceRegenerateColorScheme(
        uriString: String?,
        regenerateAllStyles: Boolean = false
    ) {
         if (uriString == null) {
             _currentAlbumArtColorSchemePair.value = null
             _currentAlbumArtUri.value = null
             return
         }

         android.util.Log.d("ThemeStateHolder", "forceRegenerateColorScheme called for: $uriString")
         android.util.Log.d("ThemeStateHolder", "Current tracked global URI: ${_currentAlbumArtUri.value}")
         
         colorSchemeProcessor.invalidateScheme(uriString)

         val newScheme = if (regenerateAllStyles) {
             var selectedStyleScheme: ColorSchemePair? = null
             AlbumArtPaletteStyle.entries.forEach { style ->
                 val generated = colorSchemeProcessor.getOrGenerateColorScheme(
                     albumArtUri = uriString,
                     paletteStyle = style,
                     colorAccuracyLevel = currentPaletteAccuracy,
                     forceRefresh = true
                 )
                 if (style == currentPaletteStyle) {
                     selectedStyleScheme = generated
                 }
             }
             selectedStyleScheme
         } else {
             colorSchemeProcessor.getOrGenerateColorScheme(
                 albumArtUri = uriString,
                 paletteStyle = currentPaletteStyle,
                 colorAccuracyLevel = currentPaletteAccuracy,
                 forceRefresh = true
             )
         }

         // Iterate if there is an active flow for this URI and update it
         val activeFlow = synchronized(individualAlbumColorSchemes) { individualAlbumColorSchemes[uriString] }
         if (activeFlow != null) {
             activeFlow.value = newScheme
         }
         
         // Also update the main current album art scheme if it matches the one we are tracking
         // We use equality check. If they are the same string object or equal content.
         if (_currentAlbumArtUri.value == uriString) {
             android.util.Log.d("ThemeStateHolder", "Updating global color scheme flow directly.")
             _currentAlbumArtColorSchemePair.value = newScheme
         } else {
             android.util.Log.d("ThemeStateHolder", "Global URI did not match. Skipping global update.")
         }
    }

    @Suppress("DEPRECATION")
    fun trimMemory(level: Int) {
        // Going to the launcher (UI_HIDDEN) is not memory pressure. The scheme caches are tiny
        // (a scheme pair is ~100 longs), and dropping them there made every visible tile re-read
        // Room and re-parse its colours on the way back.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return

        colorSchemeProcessor.clearMemoryCache()
        clearExtractedColorCache()

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            synchronized(individualAlbumColorSchemes) { individualAlbumColorSchemes.clear() }
        }

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            synchronized(pendingAlbumColorSchemeLock) {
                pendingAlbumColorSchemeTargets.clear()
                pinnedAlbumColorSchemeRequests.clear()
            }
        }
    }

    fun onCleared() {
        scope = null
    }

    private companion object {
        const val DROPPABLE_REQUEST_GRACE_MS = 120L
    }

}

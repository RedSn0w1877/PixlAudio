package com.theveloper.pixelplay.presentation.lyrics

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.preferences.dataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The user's lyrics look preferences, read once from DataStore. Shared by the lyrics sheet and
 * the sync editor's Preview, so what the user approves before saving is what the sheet shows.
 */
@Immutable
data class LyricsAppearancePrefs(
    /** "left", "center" or "right". */
    val alignment: String = "left",
    val showTranslation: Boolean = true,
    val showRomanization: Boolean = true,
    val animatedBlurEnabled: Boolean = true,
    val disableBlurAllOver: Boolean = false,
    /** The "animated lyrics blur strength" preference (default 1.2 = the spec's σ table). */
    val blurStrength: Float = DEFAULT_BLUR_STRENGTH_PREF,
    /** Increased contrast (API 34+, `UiModeManager.contrast ≥ 0.5`): §1.2's high-contrast lyrics. */
    val highContrast: Boolean = false,
) {
    /** The [KaraokeLyricsView] look for these preferences. */
    fun toAppearance(fontFamily: FontFamily?, textSize: TextUnit, brightArt: Boolean) = KaraokeLyricsAppearance(
        fontFamily = fontFamily,
        textScale = if (textSize.isSp) textSize.value / DEFAULT_LYRICS_TEXT_SP else 1f,
        alignment = when (alignment) {
            "center" -> KaraokeAlignment.CENTER
            "right" -> KaraokeAlignment.END
            else -> KaraokeAlignment.START
        },
        brightArt = brightArt,
        highContrast = highContrast,
        blurEnabled = animatedBlurEnabled && !disableBlurAllOver,
        blurStrength = blurStrength / DEFAULT_BLUR_STRENGTH_PREF,
        showTranslation = showTranslation,
        showRomanization = showRomanization,
    )

    companion object {
        /** The size the app's lyrics text style is designed at (`titleLarge`): text scale 1. */
        const val DEFAULT_LYRICS_TEXT_SP = 22f
        const val DEFAULT_BLUR_STRENGTH_PREF = 1.2f
    }
}

@Composable
fun rememberLyricsAppearancePrefs(): State<LyricsAppearancePrefs> {
    val context = LocalContext.current
    val initial = remember(context) { LyricsAppearancePrefs(highContrast = isIncreasedContrast(context)) }
    val flow = remember(context) {
        val highContrast = initial.highContrast
        context.dataStore.data.map { p ->
            LyricsAppearancePrefs(
                alignment = p[stringPreferencesKey("lyrics_alignment")] ?: "left",
                showTranslation = p[booleanPreferencesKey("show_lyrics_translation")] ?: true,
                showRomanization = p[booleanPreferencesKey("show_lyrics_romanization")] ?: true,
                animatedBlurEnabled = p[booleanPreferencesKey("animated_lyrics_blur_enabled")] ?: true,
                disableBlurAllOver = p[booleanPreferencesKey("disable_blur_all_over")] ?: false,
                blurStrength = p[floatPreferencesKey("animated_lyrics_blur_strength")] ?: LyricsAppearancePrefs.DEFAULT_BLUR_STRENGTH_PREF,
                highContrast = highContrast,
            )
        }.distinctUntilChanged()
    }
    return flow.collectAsStateWithLifecycle(initialValue = initial)
}

private fun isIncreasedContrast(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val uiModeManager = context.getSystemService(android.app.UiModeManager::class.java) ?: return false
    return uiModeManager.contrast >= 0.5f
}

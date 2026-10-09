package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemePreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val PLAYER_THEME_PREFERENCE = stringPreferencesKey("player_theme_preference_v2")
        val ALBUM_ART_PALETTE_STYLE = stringPreferencesKey("album_art_palette_style_v1")
        val ALBUM_ART_COLOR_ACCURACY = intPreferencesKey("album_art_color_accuracy_v1")
        val APP_THEME_MODE = stringPreferencesKey("app_theme_mode")
        /** `"#RRGGBB"`, absent = the default ([AccentColor.DEFAULT]). Same key and format as iOS. */
        val ACCENT_COLOR = stringPreferencesKey("accent_color_v1")
        val ACCENT_SCHEME_CACHE = stringPreferencesKey(ACCENT_SCHEME_CACHE_KEY)
    }

    companion object {
        /**
         * Derived data (the generated accent scheme as ARGB ints, see `AccentSchemeCodec`): device-local,
         * rebuilt on demand, so it is kept out of backups.
         */
        const val ACCENT_SCHEME_CACHE_KEY = "accent_scheme_cache_v1"
    }

    val appThemeModeFlow: Flow<String> = dataStore.prefFlow { preferences ->
        preferences[Keys.APP_THEME_MODE] ?: AppThemeMode.FOLLOW_SYSTEM
    }

    val playerThemePreferenceFlow: Flow<String> = dataStore.prefFlow { preferences ->
        preferences[Keys.PLAYER_THEME_PREFERENCE] ?: ThemePreference.ALBUM_ART
    }

    /**
     * Settings › Appearance › Accent Color, normalised (`"#RRGGBB"` or [AccentColor.DEFAULT]): a junk value
     * from a hand-edited or foreign backup reads as the default. Distinct, so an unrelated settings write
     * doesn't rebuild the app's scheme.
     */
    val accentColorFlow: Flow<String> =
        dataStore.prefFlow { preferences -> AccentColor.normalize(preferences[Keys.ACCENT_COLOR]) }

    val albumArtPaletteStyleFlow: Flow<AlbumArtPaletteStyle> = dataStore.prefFlow { preferences ->
        AlbumArtPaletteStyle.fromStorageKey(preferences[Keys.ALBUM_ART_PALETTE_STYLE])
    }

    val albumArtColorAccuracyFlow: Flow<Int> = dataStore.prefFlow { preferences ->
        AlbumArtColorAccuracy.clamp(preferences[Keys.ALBUM_ART_COLOR_ACCURACY] ?: AlbumArtColorAccuracy.DEFAULT)
    }

    suspend fun setPlayerThemePreference(themeMode: String) =
        dataStore.edit { preferences ->
            preferences[Keys.PLAYER_THEME_PREFERENCE] = themeMode
        }

    suspend fun setAppThemeMode(themeMode: String) =
        dataStore.edit { preferences ->
            preferences[Keys.APP_THEME_MODE] = themeMode
        }

    suspend fun initializeAppThemeMode(themeMode: String) =
        dataStore.edit { preferences ->
            if (preferences[Keys.APP_THEME_MODE] == null) {
                preferences[Keys.APP_THEME_MODE] = themeMode
            }
        }

    /** The stored, encoded accent scheme (see `AccentSchemeCodec`); null when none was saved yet. */
    suspend fun accentSchemeCache(): String? = dataStore.data.first()[Keys.ACCENT_SCHEME_CACHE]

    suspend fun setAccentSchemeCache(encoded: String) {
        dataStore.edit { preferences -> preferences[Keys.ACCENT_SCHEME_CACHE] = encoded }
    }

    /** Stores [hex] normalised; the default (or anything that isn't a colour) removes the key. */
    suspend fun setAccentColor(hex: String) =
        dataStore.edit { preferences ->
            val normalized = AccentColor.normalize(hex)
            if (normalized == AccentColor.DEFAULT) {
                preferences.remove(Keys.ACCENT_COLOR)
            } else {
                preferences[Keys.ACCENT_COLOR] = normalized
            }
        }

    suspend fun setAlbumArtPaletteStyle(style: AlbumArtPaletteStyle) =
        dataStore.edit { preferences ->
            preferences[Keys.ALBUM_ART_PALETTE_STYLE] = style.storageKey
        }

    suspend fun setAlbumArtColorAccuracy(level: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.ALBUM_ART_COLOR_ACCURACY] = AlbumArtColorAccuracy.clamp(level)
        }

    suspend fun setAlbumArtPaletteSettings(
        style: AlbumArtPaletteStyle,
        accuracyLevel: Int
    ) = dataStore.edit { preferences ->
        preferences[Keys.ALBUM_ART_PALETTE_STYLE] = style.storageKey
        preferences[Keys.ALBUM_ART_COLOR_ACCURACY] = AlbumArtColorAccuracy.clamp(accuracyLevel)
    }
}

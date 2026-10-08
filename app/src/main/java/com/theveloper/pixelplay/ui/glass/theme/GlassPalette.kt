package com.theveloper.pixelplay.ui.glass.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified

/**
 * Glass mode's colour tokens (owner decision G6).
 *
 * Dark values are NexHome's verbatim (`theme/Color.kt`). NexHome is always dark, so the light values
 * come from the Kyant catalog components NexHome ported (their `isLightTheme` branches: tab bar
 * `#FAFAFA@0.4`, tab blob wash Black@0.1, track `#787878@0.2`, black content) plus a few derived
 * tints marked *derived* below, which need tuning on the device. Content text over glass is always
 * [primary], never the accent.
 *
 * The accent (see [rootAccent] / [playerAccent]): app-wide it is the album-art scheme's primary while
 * Settings › Appearance › Accent Color is "Dynamic" (the default, today's behaviour), and the chosen
 * accent once one is picked. The player sheet gets its own palette, so it keeps the album colours.
 */
@Immutable
data class GlassPalette(
    val isDark: Boolean,
    /** Content emphasis levels (text / icons). */
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val quaternary: Color,
    /** Blobs, slider fill, toggle on, chip selection, light-spill emitters. */
    val accent: Color,
    /** NexHome `GlassTint`: default panels, cards, tiles. */
    val tint: Color,
    /** NexHome `GlassTintDark`: heavy panels (sections, hero, sheet header, segmented bar). */
    val tintStrong: Color,
    /** NexHome `GlassTintLight`: rows, stat pills, small capsules and orbs. */
    val tintSubtle: Color,
    /** Bottom tab bar container. */
    val bar: Color,
    /** Toggle / slider track. */
    val track: Color,
    /** NexHome `Dim`. */
    val dim: Color,
    /** Tab blob wash at rest. */
    val blobRest: Color,
    /** Toggle / slider thumb at rest (white in both themes, as in the catalog). */
    val thumb: Color,
    /** `GlassIconOrb` base. */
    val orbSurface: Color,
    /** Floating top bar / mini player / search capsule tint. */
    val topBar: Color,
    /** Scrubber well (NexHome MediaScrubber track). */
    val well: Color,
    /** Sheet scrim fill drawn over the dimmed ambient. */
    val scrim: Color,
    /** Sheet scrim `colorControls` brightness / saturation. */
    val scrimBrightness: Float,
    val scrimSaturation: Float,
) {
    companion object {
        /** NexHome Cyan: the accent when there is no album or app scheme to take one from. */
        val FallbackAccentDark = Color(0xFF6FE3FF)

        /** Catalog light accent (`#0088FF`). */
        val FallbackAccentLight = Color(0xFF0088FF)

        val Dark = GlassPalette(
            isDark = true,
            primary = Color.White,
            secondary = Color.White.copy(alpha = 0.72f),
            tertiary = Color.White.copy(alpha = 0.48f),
            quaternary = Color.White.copy(alpha = 0.28f),
            accent = FallbackAccentDark,
            tint = Color.Black.copy(alpha = 0.05f),
            tintStrong = Color.Black.copy(alpha = 0.18f),
            tintSubtle = Color.White.copy(alpha = 0.08f),
            bar = Color(0xFF121212).copy(alpha = 0.4f),
            track = Color(0xFF787880).copy(alpha = 0.36f),
            dim = Color.Black.copy(alpha = 0.38f),
            blobRest = Color.White.copy(alpha = 0.1f),
            thumb = Color.White,
            orbSurface = Color.Black.copy(alpha = 0.12f),
            topBar = Color.Black.copy(alpha = 0.22f),
            well = Color.Black.copy(alpha = 0.16f),
            scrim = Color.Black.copy(alpha = 0.32f),
            scrimBrightness = -0.1f,
            scrimSaturation = 1.25f,
        )

        val Light = GlassPalette(
            isDark = false,
            primary = Color.Black,
            // Mirrors the dark emphasis levels (derived).
            secondary = Color.Black.copy(alpha = 0.72f),
            tertiary = Color.Black.copy(alpha = 0.48f),
            quaternary = Color.Black.copy(alpha = 0.28f),
            accent = FallbackAccentLight,
            tint = Color.White.copy(alpha = 0.10f), // derived
            tintStrong = Color.White.copy(alpha = 0.30f), // derived (catalog light button surface)
            tintSubtle = Color.White.copy(alpha = 0.18f), // derived
            bar = Color(0xFFFAFAFA).copy(alpha = 0.4f),
            track = Color(0xFF787878).copy(alpha = 0.2f),
            dim = Color.White.copy(alpha = 0.38f), // derived
            blobRest = Color.Black.copy(alpha = 0.1f),
            thumb = Color.White,
            orbSurface = Color.White.copy(alpha = 0.25f), // derived
            topBar = Color(0xFFFAFAFA).copy(alpha = 0.4f), // derived
            well = Color.White.copy(alpha = 0.24f), // derived
            scrim = Color.White.copy(alpha = 0.28f), // derived
            scrimBrightness = 0.06f, // derived
            scrimSaturation = 1.2f, // derived
        )

        /**
         * The palette for [isDark] with [accent] as the accent (the album-art scheme's primary, else
         * the app scheme's). An unspecified accent keeps the fallback.
         */
        fun of(isDark: Boolean, accent: Color): GlassPalette {
            val base = if (isDark) Dark else Light
            return if (accent.isSpecified && accent != base.accent) base.copy(accent = accent) else base
        }

        /**
         * The app-wide glass accent (tab bar, toggles, sliders, chips, settings icon discs). With the
         * default "Dynamic" accent it follows the playing album, as before the accent setting existed;
         * a chosen accent ([accentChosen]) wins over the album, or it would be repainted on every track.
         */
        fun rootAccent(accentChosen: Boolean, albumPrimary: Color?, appPrimary: Color): Color =
            if (accentChosen) appPrimary else albumPrimary ?: appPrimary

        /**
         * The player sheet's glass accent: the player's album scheme ([playerAlbumPrimary], present only
         * under Player Theme › Album Art) else the app scheme's primary, which is what Player Theme ›
         * Accent Color means (Material You while the accent is Dynamic). Matches the Material player.
         */
        fun playerAccent(playerAlbumPrimary: Color?, appPrimary: Color): Color =
            playerAlbumPrimary ?: appPrimary
    }
}

/**
 * The current glass palette. Dynamic (not static): the accent follows the playing track, and only
 * the surfaces that read the palette should recompose when it does.
 */
val LocalGlassPalette = compositionLocalOf { GlassPalette.Dark }

/** Remembers the palette for the app's own dark setting and the current accent. */
@Composable
fun rememberGlassPalette(isDark: Boolean, accent: Color): GlassPalette =
    remember(isDark, accent) { GlassPalette.of(isDark, accent) }

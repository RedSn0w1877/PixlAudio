package com.theveloper.pixelplay.presentation.viewmodel

import android.net.Uri
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.ui.theme.AccentSchemeCodec
import com.theveloper.pixelplay.ui.theme.generateAccentColorSchemePair
import com.theveloper.pixelplay.ui.theme.hasSameColorsAs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ThemeStateHolderSchemeReuseTest {

    private fun uri(value: String): Uri = mockk { every { toString() } returns value }

    private fun pairOf(primary: Color) = ColorSchemePair(
        light = lightColorScheme(primary = primary),
        dark = lightColorScheme(primary = primary)
    )

    @Test
    fun `the next track with identical album colours keeps the published scheme instance`() = runTest {
        val dir = Files.createTempDirectory("theme-state-holder-reuse-test")
        try {
            val processor = mockk<ColorSchemeProcessor>()
            val holder = ThemeStateHolder(
                colorSchemeProcessor = processor,
                themePreferencesRepository = ThemePreferencesRepository(
                    PreferenceDataStoreFactory.create(
                        scope = backgroundScope,
                        produceFile = { dir.resolve("settings.preferences_pb").toFile() }
                    )
                ),
                appScope = backgroundScope
            )

            val first = pairOf(Color(0xFF3478F6))
            val sameColorsNewInstance = pairOf(Color(0xFF3478F6))
            val otherColors = pairOf(Color(0xFFBD0E12))
            coEvery { processor.getOrGenerateColorScheme(albumArtUri = "art://1", any(), any(), any(), any(), any()) } returns first
            coEvery { processor.getOrGenerateColorScheme(albumArtUri = "art://2", any(), any(), any(), any(), any()) } returns sameColorsNewInstance
            coEvery { processor.getOrGenerateColorScheme(albumArtUri = "art://3", any(), any(), any(), any(), any()) } returns otherColors

            holder.extractAndGenerateColorScheme(uri("art://1"), "art://1")
            assertSame(first, holder.currentAlbumArtColorSchemePair.value)
            assertEquals("art://1", holder.currentAlbumArtUri.value)

            // Same colours, new instance (and a new cover URI): nothing observable should restart.
            assertNotSame(first, sameColorsNewInstance)
            assertTrue(first.hasSameColorsAs(sameColorsNewInstance))
            holder.extractAndGenerateColorScheme(uri("art://2"), "art://2")
            assertSame(first, holder.currentAlbumArtColorSchemePair.value)
            // ...but the URI the player checks against still moves to the new song's art.
            assertEquals("art://2", holder.currentAlbumArtUri.value)

            // Genuinely different colours replace the scheme as before.
            holder.extractAndGenerateColorScheme(uri("art://3"), "art://3")
            assertSame(otherColors, holder.currentAlbumArtColorSchemePair.value)
            assertEquals("art://3", holder.currentAlbumArtUri.value)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a saved accent scheme is used on the next launch instead of rebuilding it`() = runTest {
        val dir = Files.createTempDirectory("theme-state-holder-accent-cache-test")
        try {
            val themes = ThemePreferencesRepository(
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { dir.resolve("settings.preferences_pb").toFile() }
                )
            )
            // Save a scheme for #123456 that is NOT what the generator would make for it, so using
            // the saved copy (rather than regenerating) is observable.
            val marker = generateAccentColorSchemePair(0xFFBD0E12.toInt())
            themes.setAccentSchemeCache(AccentSchemeCodec.encode("#123456", marker))
            themes.setAccentColor("#123456")

            val holder = ThemeStateHolder(
                colorSchemeProcessor = mockk(relaxed = true),
                themePreferencesRepository = themes,
                appScope = backgroundScope
            )
            val resolved = holder.accentScheme.filterNotNull().first { it.hex == "#123456" }

            assertNotNull(resolved.pair)
            assertTrue(marker.hasSameColorsAs(resolved.pair!!), "expected the saved scheme, not a regenerated one")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a first launch with a custom accent saves the scheme for the next one`() = runTest {
        val dir = Files.createTempDirectory("theme-state-holder-accent-save-test")
        try {
            val themes = ThemePreferencesRepository(
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { dir.resolve("settings.preferences_pb").toFile() }
                )
            )
            themes.setAccentColor("#654321")
            val holder = ThemeStateHolder(
                colorSchemeProcessor = mockk(relaxed = true),
                themePreferencesRepository = themes,
                appScope = backgroundScope
            )
            val resolved = holder.accentScheme.filterNotNull().first { it.hex == "#654321" }

            // The save runs off the resolve path, on a real background thread: wait for it.
            var saved: String? = null
            val deadline = System.nanoTime() + 5_000_000_000L
            while (saved == null && System.nanoTime() < deadline) {
                saved = themes.accentSchemeCache()
                if (saved == null) Thread.sleep(20)
            }
            val decoded = AccentSchemeCodec.decode(saved, "#654321")
            assertNotNull(decoded, "the generated accent scheme was not saved")
            assertTrue(resolved.pair!!.hasSameColorsAs(decoded!!))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

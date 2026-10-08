package com.theveloper.pixelplay.presentation.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.theveloper.pixelplay.data.preferences.AccentColor
import com.theveloper.pixelplay.data.preferences.AccentPreset
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.ui.theme.AccentColorSchemes
import io.mockk.mockk
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * The app-wide accent state the root theme, the splash and glass mode read
 * ([ThemeStateHolder.accentScheme]): non-null once the store is read (the splash waits for that),
 * the default as a null pair (Material You stays), a chosen accent as the memoised pair (the same
 * instances every time, since `ColorScheme` has no `equals`), and live updates from the store.
 */
class ThemeStateHolderAccentTest {

    @Test
    fun `the accent scheme follows the stored accent with memoised pairs`() = runTest {
        val dir = Files.createTempDirectory("theme-state-holder-test")
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { dir.resolve("settings.preferences_pb").toFile() }
            )
            val themes = ThemePreferencesRepository(store)
            val holder = ThemeStateHolder(
                colorSchemeProcessor = mockk(relaxed = true),
                themePreferencesRepository = themes,
                appScope = backgroundScope
            )

            // Nothing stored: resolved (so the splash can go) to the default, with no pair.
            val initial = holder.accentScheme.filterNotNull().first()
            assertEquals(AccentScheme.Default, initial)
            assertNull(initial.pair)

            themes.setAccentColor(AccentPreset.RED.hex.lowercase())
            val red = holder.accentScheme.filterNotNull().first { it.hex == AccentPreset.RED.hex }
            assertNotNull(red.pair)
            assertSame(AccentColorSchemes.pairFor(requireNotNull(AccentColor.seedOrNull(AccentPreset.RED.hex))), red.pair)

            // Back to Dynamic: the default again, so PixelPlayTheme returns to Material You.
            themes.setAccentColor(AccentColor.DEFAULT)
            val back = holder.accentScheme.filterNotNull().first { it.hex.isEmpty() }
            assertEquals(AccentScheme.Default, back)

            // Choosing Red again hands back the very same pair: no app-wide recomposition for a
            // scheme that only looks new.
            themes.setAccentColor(AccentPreset.RED.hex)
            val redAgain = holder.accentScheme.filterNotNull().first { it.hex == AccentPreset.RED.hex }
            assertSame(red.pair, redAgain.pair)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

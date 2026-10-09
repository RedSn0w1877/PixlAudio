package com.theveloper.pixelplay.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.theveloper.pixelplay.presentation.viewmodel.ColorSchemePair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ColorSchemeColorsTest {

    private val base = lightColorScheme()
    private val changed = Color(0xFF123456)

    /** One mutation per colour role: if hasSameColorsAs forgot a role, its case below fails. */
    private val mutations: List<Pair<String, (ColorScheme) -> ColorScheme>> = listOf(
        "primary" to { s -> s.copy(primary = changed) },
        "onPrimary" to { s -> s.copy(onPrimary = changed) },
        "primaryContainer" to { s -> s.copy(primaryContainer = changed) },
        "onPrimaryContainer" to { s -> s.copy(onPrimaryContainer = changed) },
        "inversePrimary" to { s -> s.copy(inversePrimary = changed) },
        "secondary" to { s -> s.copy(secondary = changed) },
        "onSecondary" to { s -> s.copy(onSecondary = changed) },
        "secondaryContainer" to { s -> s.copy(secondaryContainer = changed) },
        "onSecondaryContainer" to { s -> s.copy(onSecondaryContainer = changed) },
        "tertiary" to { s -> s.copy(tertiary = changed) },
        "onTertiary" to { s -> s.copy(onTertiary = changed) },
        "tertiaryContainer" to { s -> s.copy(tertiaryContainer = changed) },
        "onTertiaryContainer" to { s -> s.copy(onTertiaryContainer = changed) },
        "background" to { s -> s.copy(background = changed) },
        "onBackground" to { s -> s.copy(onBackground = changed) },
        "surface" to { s -> s.copy(surface = changed) },
        "onSurface" to { s -> s.copy(onSurface = changed) },
        "surfaceVariant" to { s -> s.copy(surfaceVariant = changed) },
        "onSurfaceVariant" to { s -> s.copy(onSurfaceVariant = changed) },
        "surfaceTint" to { s -> s.copy(surfaceTint = changed) },
        "inverseSurface" to { s -> s.copy(inverseSurface = changed) },
        "inverseOnSurface" to { s -> s.copy(inverseOnSurface = changed) },
        "error" to { s -> s.copy(error = changed) },
        "onError" to { s -> s.copy(onError = changed) },
        "errorContainer" to { s -> s.copy(errorContainer = changed) },
        "onErrorContainer" to { s -> s.copy(onErrorContainer = changed) },
        "outline" to { s -> s.copy(outline = changed) },
        "outlineVariant" to { s -> s.copy(outlineVariant = changed) },
        "scrim" to { s -> s.copy(scrim = changed) },
        "surfaceBright" to { s -> s.copy(surfaceBright = changed) },
        "surfaceDim" to { s -> s.copy(surfaceDim = changed) },
        "surfaceContainer" to { s -> s.copy(surfaceContainer = changed) },
        "surfaceContainerHigh" to { s -> s.copy(surfaceContainerHigh = changed) },
        "surfaceContainerHighest" to { s -> s.copy(surfaceContainerHighest = changed) },
        "surfaceContainerLow" to { s -> s.copy(surfaceContainerLow = changed) },
        "surfaceContainerLowest" to { s -> s.copy(surfaceContainerLowest = changed) },
        "primaryFixed" to { s -> s.copy(primaryFixed = changed) },
        "primaryFixedDim" to { s -> s.copy(primaryFixedDim = changed) },
        "onPrimaryFixed" to { s -> s.copy(onPrimaryFixed = changed) },
        "onPrimaryFixedVariant" to { s -> s.copy(onPrimaryFixedVariant = changed) },
        "secondaryFixed" to { s -> s.copy(secondaryFixed = changed) },
        "secondaryFixedDim" to { s -> s.copy(secondaryFixedDim = changed) },
        "onSecondaryFixed" to { s -> s.copy(onSecondaryFixed = changed) },
        "onSecondaryFixedVariant" to { s -> s.copy(onSecondaryFixedVariant = changed) },
        "tertiaryFixed" to { s -> s.copy(tertiaryFixed = changed) },
        "tertiaryFixedDim" to { s -> s.copy(tertiaryFixedDim = changed) },
        "onTertiaryFixed" to { s -> s.copy(onTertiaryFixed = changed) },
        "onTertiaryFixedVariant" to { s -> s.copy(onTertiaryFixedVariant = changed) },
    )

    @Test
    fun `a colour-identical copy is the same colours, even though it is a new instance`() {
        val copy = base.copy()
        assertNotSame(base, copy)
        assertTrue(base.hasSameColorsAs(copy))
        assertTrue(copy.hasSameColorsAs(base))
        assertTrue(base.hasSameColorsAs(base))
    }

    @Test
    fun `changing any single role is detected`() {
        assertEquals(COLOR_SCHEME_ROLE_COUNT, mutations.size, "one mutation per role")
        for ((role, mutate) in mutations) {
            val mutated = mutate(base)
            assertFalse(base.hasSameColorsAs(mutated), "role $role was not compared")
            assertFalse(mutated.hasSameColorsAs(base), "role $role was not compared (reversed)")
        }
    }

    @Test
    fun `pairs compare light and dark`() {
        val pair = ColorSchemePair(light = base, dark = lightColorScheme(primary = Color.Red))
        assertTrue(pair.hasSameColorsAs(ColorSchemePair(base.copy(), pair.dark.copy())))
        assertFalse(pair.hasSameColorsAs(ColorSchemePair(base.copy(primary = changed), pair.dark)))
        assertFalse(pair.hasSameColorsAs(ColorSchemePair(base, pair.dark.copy(primary = changed))))
    }

    @Test
    fun `argb round trip rebuilds exactly the same colours`() {
        val argb = base.toArgbArray()
        assertEquals(COLOR_SCHEME_ROLE_COUNT, argb.size)
        assertTrue(base.hasSameColorsAs(colorSchemeFromArgb(argb)))
        for ((role, mutate) in mutations) {
            val mutated = mutate(base)
            assertTrue(mutated.hasSameColorsAs(colorSchemeFromArgb(mutated.toArgbArray())), "round trip of $role")
        }
    }

    @Test
    fun `the saved accent scheme decodes to the colours the generator produced`() {
        for (seed in listOf(0xFF3478F6.toInt(), 0xFFBD0E12.toInt(), 0xFF808080.toInt(), 0xFF00C7BE.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val hex = String.format("#%06X", seed and 0xFFFFFF)
            val generated = generateAccentColorSchemePair(seed)
            val encoded = AccentSchemeCodec.encode(hex, generated)
            val decoded = AccentSchemeCodec.decode(encoded, hex)
            assertNotNull(decoded, "decode of $hex")
            assertTrue(generated.hasSameColorsAs(decoded!!), "decoded colours differ from generated for $hex")
        }
    }

    @Test
    fun `a saved scheme for another accent, another algorithm version or garbage is ignored`() {
        val seed = 0xFF3478F6.toInt()
        val generated = generateAccentColorSchemePair(seed)
        val encoded = AccentSchemeCodec.encode("#3478F6", generated)

        assertNull(AccentSchemeCodec.decode(encoded, "#BD0E12"), "other accent")
        assertNull(AccentSchemeCodec.decode(encoded.replaceFirst("${AccentColorSchemes.ALGORITHM_VERSION};", "999;"), "#3478F6"), "other version")
        assertNull(AccentSchemeCodec.decode(null, "#3478F6"))
        assertNull(AccentSchemeCodec.decode("", "#3478F6"))
        assertNull(AccentSchemeCodec.decode("garbage", "#3478F6"))
        assertNull(AccentSchemeCodec.decode(encoded.substringBeforeLast(','), "#3478F6"), "truncated")
        assertNull(AccentSchemeCodec.decode(encoded.replaceFirst(Regex("\\d+"), "x"), "#3478F6"), "non-numeric version")
        assertNull(AccentSchemeCodec.decode(encoded.dropLast(1) + "x", "#3478F6"), "non-numeric colour")
    }
}

package com.theveloper.pixelplay.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Contrast
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.theveloper.pixelplay.data.preferences.AccentColor
import com.theveloper.pixelplay.data.preferences.AccentPreset
import com.theveloper.pixelplay.presentation.viewmodel.ColorSchemePair
import org.junit.Test
import java.util.Locale
import com.google.android.material.color.utilities.ColorUtils as MaterialColorUtils

/**
 * The app-wide accent scheme (Settings › Appearance › Accent Color): every preset and any custom
 * pick stays legible, the light primaries keep the pick's punch ("vivid"), Graphite is pure grey,
 * the surfaces stay TonalSpot's and the memo hands back the same instances.
 *
 * The pinned colours come from Google's own colour utilities (MDC 1.14.0, the jar the app ships)
 * run with TonalSpot palettes and the primary at max(36, seed chroma); they match iOS's
 * AccentPairTests digit for digit. A material upgrade that changes the maths fails here first.
 */
class AccentColorSchemeTest {

    private val coloredPresets = AccentPreset.entries.filter { it.hex.isNotEmpty() && it != AccentPreset.GRAPHITE }

    private fun seed(preset: AccentPreset): Int = requireNotNull(AccentColor.seedOrNull(preset.hex))

    private fun hex(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    private fun contrast(a: Int, b: Int): Double =
        Contrast.ratioOfTones(MaterialColorUtils.lstarFromArgb(a), MaterialColorUtils.lstarFromArgb(b))

    /** WCAG AA (4.5:1) for the pairs the app draws on the accent, in light and dark. */
    private fun legibilityFailures(name: String, pair: ColorSchemePair): List<String> {
        val failures = mutableListOf<String>()
        for ((mode, s) in listOf("light" to pair.light, "dark" to pair.dark)) {
            val checks = listOf(
                "primary/onPrimary" to contrast(s.primary.toArgb(), s.onPrimary.toArgb()),
                "primary/background" to contrast(s.primary.toArgb(), s.background.toArgb()),
                "primary/surface" to contrast(s.primary.toArgb(), s.surface.toArgb()),
                "primaryContainer/onPrimaryContainer" to
                    contrast(s.primaryContainer.toArgb(), s.onPrimaryContainer.toArgb())
            )
            for ((label, ratio) in checks) {
                if (ratio < 4.5) failures += "$name $mode $label ${"%.2f".format(Locale.ROOT, ratio)}"
            }
        }
        return failures
    }

    @Test
    fun everyPresetIsLegibleInLightAndDark() {
        val failures = AccentPreset.entries
            .filter { it.hex.isNotEmpty() }
            .flatMap { legibilityFailures(it.name, generateAccentColorSchemePair(seed(it))) }
        assertThat(failures).isEmpty()
    }

    @Test
    fun anyCustomPickIsLegible() {
        val failures = mutableListOf<String>()
        var hue = 0.0
        while (hue < 360.0) {
            for (chroma in listOf(4.0, 20.0, 48.0, 90.0, 150.0)) {
                for (tone in listOf(5.0, 30.0, 50.0, 70.0, 95.0)) {
                    val pick = Hct.from(hue, chroma, tone).toInt()
                    failures += legibilityFailures(hex(pick), generateAccentColorSchemePair(pick))
                }
            }
            hue += 15.0
        }
        for (pick in listOf(0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF)) {
            failures += legibilityFailures(hex(pick.toInt()), generateAccentColorSchemePair(pick.toInt()))
        }
        assertThat(failures).isEmpty()
    }

    @Test
    fun primariesMatchTheReferenceUtilities() {
        val expectedLight = mapOf(
            AccentPreset.BLUE to "#005DB8",
            AccentPreset.INDIGO to "#4D4AD5",
            AccentPreset.PURPLE to "#9026C3",
            AccentPreset.PINK to "#BE0036",
            AccentPreset.RED to "#BD0E12",
            AccentPreset.ORANGE to "#885200",
            AccentPreset.YELLOW to "#705D00",
            AccentPreset.GREEN to "#006E28",
            AccentPreset.MINT to "#006A65"
        )
        val expectedDark = mapOf(
            AccentPreset.BLUE to "#AAC7FF",
            AccentPreset.INDIGO to "#C2C1FF",
            AccentPreset.PURPLE to "#E9B3FF",
            AccentPreset.PINK to "#FFB3B5",
            AccentPreset.RED to "#FFB4AA",
            AccentPreset.ORANGE to "#FFB868",
            AccentPreset.YELLOW to "#E9C400",
            AccentPreset.GREEN to "#53E16F",
            AccentPreset.MINT to "#39DCD2"
        )
        for (preset in coloredPresets) {
            val pair = generateAccentColorSchemePair(seed(preset))
            assertWithMessage("${preset.name} light").that(hex(pair.light.primary.toArgb()))
                .isEqualTo(expectedLight.getValue(preset))
            assertWithMessage("${preset.name} dark").that(hex(pair.dark.primary.toArgb()))
                .isEqualTo(expectedDark.getValue(preset))
        }
    }

    @Test
    fun primaryKeepsTheSeedsChroma() {
        for (preset in coloredPresets) {
            val source = Hct.fromInt(seed(preset))
            val accent = generateAccentColorSchemePair(seed(preset))
            for ((isDark, scheme) in listOf(false to accent.light, true to accent.dark)) {
                val tonal = SchemeTonalSpot(source, isDark, 0.0).primary
                // >=, not >: dark tones and Mint are gamut-limited to the same colour.
                assertThat(Hct.fromInt(scheme.primary.toArgb()).chroma)
                    .isAtLeast(Hct.fromInt(tonal).chroma - 1e-6)
            }
        }
        // The saturated presets are clearly more colourful than Material You's TonalSpot in light mode.
        for (preset in listOf(
            AccentPreset.BLUE, AccentPreset.INDIGO, AccentPreset.PURPLE,
            AccentPreset.PINK, AccentPreset.RED, AccentPreset.GREEN
        )) {
            val accent = Hct.fromInt(generateAccentColorSchemePair(seed(preset)).light.primary.toArgb()).chroma
            val tonal = Hct.fromInt(SchemeTonalSpot(Hct.fromInt(seed(preset)), false, 0.0).primary).chroma
            assertThat(accent).isGreaterThan(tonal + 15.0)
        }
    }

    @Test
    fun graphiteIsPureGreyAtTheRoleTones() {
        val pair = generateAccentColorSchemePair(seed(AccentPreset.GRAPHITE))
        for (scheme in listOf(pair.light, pair.dark)) {
            val roles = listOf(
                scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer,
                scheme.secondary, scheme.tertiary, scheme.background, scheme.surface,
                scheme.surfaceContainer, scheme.outline
            )
            assertThat(roles.map { it.toArgb() }.filterNot(::isGrey).map(::hex)).isEmpty()
            // Errors stay red.
            assertThat(isGrey(scheme.error.toArgb())).isFalse()
        }
        assertThat(hex(pair.light.primary.toArgb())).isEqualTo("#5E5E5E")
        assertThat(hex(pair.dark.primary.toArgb())).isEqualTo("#C6C6C6")
        assertThat(pair.light.primary.toArgb()).isEqualTo(MaterialColorUtils.argbFromLstar(40.0))
        assertThat(pair.dark.primary.toArgb()).isEqualTo(MaterialColorUtils.argbFromLstar(80.0))
    }

    @Test
    fun surfacesSecondaryAndTertiaryStayTonalSpot() {
        for (preset in coloredPresets) {
            val source = Hct.fromInt(seed(preset))
            val accent = generateAccentColorSchemePair(seed(preset))
            for ((isDark, scheme) in listOf(false to accent.light, true to accent.dark)) {
                val tonal = SchemeTonalSpot(source, isDark, 0.0)
                assertWithMessage("${preset.name} dark=$isDark")
                    .that(scheme.unchangedRoles())
                    .containsExactly(
                        tonal.background, tonal.onBackground, tonal.surface, tonal.onSurface,
                        tonal.surfaceVariant, tonal.onSurfaceVariant, tonal.surfaceContainer,
                        tonal.surfaceContainerLow, tonal.surfaceContainerHigh, tonal.surfaceContainerHighest,
                        tonal.outline, tonal.outlineVariant, tonal.secondary, tonal.secondaryContainer,
                        tonal.tertiary, tonal.tertiaryContainer, tonal.error, tonal.errorContainer
                    )
                    .inOrder()
            }
        }
    }

    @Test
    fun memoReturnsTheSameInstancesForTheSameSeed() {
        val red = seed(AccentPreset.RED)
        val first = AccentColorSchemes.pairFor(red)
        assertThat(AccentColorSchemes.pairFor(red)).isSameInstanceAs(first)
        // The alpha byte doesn't make a different accent.
        assertThat(AccentColorSchemes.pairFor(red and 0x00FFFFFF)).isSameInstanceAs(first)
        assertThat(AccentColorSchemes.pairFor(seed(AccentPreset.BLUE))).isNotSameInstanceAs(first)
    }

    private fun ColorScheme.unchangedRoles(): List<Int> = listOf(
        background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, surfaceContainer,
        surfaceContainerLow, surfaceContainerHigh, surfaceContainerHighest, outline, outlineVariant,
        secondary, secondaryContainer, tertiary, tertiaryContainer, error, errorContainer
    ).map { it.toArgb() }

    private fun isGrey(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r == g && g == b
    }
}

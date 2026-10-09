package com.theveloper.pixelplay.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.theveloper.pixelplay.presentation.viewmodel.ColorSchemePair

/** How many colour roles [ColorScheme] has in this app (the same 48 the theme cache stores). */
internal const val COLOR_SCHEME_ROLE_COUNT = 48

/**
 * Material3's [ColorScheme] has no `equals`, so two schemes with identical colours are "different"
 * to every `remember` key, `distinctUntilChanged` and animation target that sees them. This is the
 * structural comparison: every role, same packed colour. Used to keep the existing instance when a
 * new song's album art produces the very same colours (consecutive tracks of one album), so the
 * player does not run a colour fade that changes no pixel.
 */
internal fun ColorScheme.hasSameColorsAs(other: ColorScheme): Boolean =
    this === other || (
        primary == other.primary && onPrimary == other.onPrimary &&
            primaryContainer == other.primaryContainer && onPrimaryContainer == other.onPrimaryContainer &&
            inversePrimary == other.inversePrimary &&
            secondary == other.secondary && onSecondary == other.onSecondary &&
            secondaryContainer == other.secondaryContainer && onSecondaryContainer == other.onSecondaryContainer &&
            tertiary == other.tertiary && onTertiary == other.onTertiary &&
            tertiaryContainer == other.tertiaryContainer && onTertiaryContainer == other.onTertiaryContainer &&
            background == other.background && onBackground == other.onBackground &&
            surface == other.surface && onSurface == other.onSurface &&
            surfaceVariant == other.surfaceVariant && onSurfaceVariant == other.onSurfaceVariant &&
            surfaceTint == other.surfaceTint &&
            inverseSurface == other.inverseSurface && inverseOnSurface == other.inverseOnSurface &&
            error == other.error && onError == other.onError &&
            errorContainer == other.errorContainer && onErrorContainer == other.onErrorContainer &&
            outline == other.outline && outlineVariant == other.outlineVariant && scrim == other.scrim &&
            surfaceBright == other.surfaceBright && surfaceDim == other.surfaceDim &&
            surfaceContainer == other.surfaceContainer && surfaceContainerHigh == other.surfaceContainerHigh &&
            surfaceContainerHighest == other.surfaceContainerHighest &&
            surfaceContainerLow == other.surfaceContainerLow && surfaceContainerLowest == other.surfaceContainerLowest &&
            primaryFixed == other.primaryFixed && primaryFixedDim == other.primaryFixedDim &&
            onPrimaryFixed == other.onPrimaryFixed && onPrimaryFixedVariant == other.onPrimaryFixedVariant &&
            secondaryFixed == other.secondaryFixed && secondaryFixedDim == other.secondaryFixedDim &&
            onSecondaryFixed == other.onSecondaryFixed && onSecondaryFixedVariant == other.onSecondaryFixedVariant &&
            tertiaryFixed == other.tertiaryFixed && tertiaryFixedDim == other.tertiaryFixedDim &&
            onTertiaryFixed == other.onTertiaryFixed && onTertiaryFixedVariant == other.onTertiaryFixedVariant
        )

internal fun ColorSchemePair.hasSameColorsAs(other: ColorSchemePair): Boolean =
    this === other || (light.hasSameColorsAs(other.light) && dark.hasSameColorsAs(other.dark))

/** Every role as an ARGB int, in the fixed order [colorSchemeFromArgb] reads them back. */
internal fun ColorScheme.toArgbArray(): IntArray = intArrayOf(
    primary.toArgb(), onPrimary.toArgb(), primaryContainer.toArgb(), onPrimaryContainer.toArgb(),
    inversePrimary.toArgb(),
    secondary.toArgb(), onSecondary.toArgb(), secondaryContainer.toArgb(), onSecondaryContainer.toArgb(),
    tertiary.toArgb(), onTertiary.toArgb(), tertiaryContainer.toArgb(), onTertiaryContainer.toArgb(),
    background.toArgb(), onBackground.toArgb(),
    surface.toArgb(), onSurface.toArgb(), surfaceVariant.toArgb(), onSurfaceVariant.toArgb(),
    surfaceTint.toArgb(), inverseSurface.toArgb(), inverseOnSurface.toArgb(),
    error.toArgb(), onError.toArgb(), errorContainer.toArgb(), onErrorContainer.toArgb(),
    outline.toArgb(), outlineVariant.toArgb(), scrim.toArgb(),
    surfaceBright.toArgb(), surfaceDim.toArgb(),
    surfaceContainer.toArgb(), surfaceContainerHigh.toArgb(), surfaceContainerHighest.toArgb(),
    surfaceContainerLow.toArgb(), surfaceContainerLowest.toArgb(),
    primaryFixed.toArgb(), primaryFixedDim.toArgb(), onPrimaryFixed.toArgb(), onPrimaryFixedVariant.toArgb(),
    secondaryFixed.toArgb(), secondaryFixedDim.toArgb(), onSecondaryFixed.toArgb(), onSecondaryFixedVariant.toArgb(),
    tertiaryFixed.toArgb(), tertiaryFixedDim.toArgb(), onTertiaryFixed.toArgb(), onTertiaryFixedVariant.toArgb(),
)

/** Rebuilds a scheme from [toArgbArray]'s output with the plain constructor (no material-color-utilities). */
internal fun colorSchemeFromArgb(argb: IntArray): ColorScheme {
    require(argb.size == COLOR_SCHEME_ROLE_COUNT) { "expected $COLOR_SCHEME_ROLE_COUNT colours, got ${argb.size}" }
    return ColorScheme(
        primary = Color(argb[0]),
        onPrimary = Color(argb[1]),
        primaryContainer = Color(argb[2]),
        onPrimaryContainer = Color(argb[3]),
        inversePrimary = Color(argb[4]),
        secondary = Color(argb[5]),
        onSecondary = Color(argb[6]),
        secondaryContainer = Color(argb[7]),
        onSecondaryContainer = Color(argb[8]),
        tertiary = Color(argb[9]),
        onTertiary = Color(argb[10]),
        tertiaryContainer = Color(argb[11]),
        onTertiaryContainer = Color(argb[12]),
        background = Color(argb[13]),
        onBackground = Color(argb[14]),
        surface = Color(argb[15]),
        onSurface = Color(argb[16]),
        surfaceVariant = Color(argb[17]),
        onSurfaceVariant = Color(argb[18]),
        surfaceTint = Color(argb[19]),
        inverseSurface = Color(argb[20]),
        inverseOnSurface = Color(argb[21]),
        error = Color(argb[22]),
        onError = Color(argb[23]),
        errorContainer = Color(argb[24]),
        onErrorContainer = Color(argb[25]),
        outline = Color(argb[26]),
        outlineVariant = Color(argb[27]),
        scrim = Color(argb[28]),
        surfaceBright = Color(argb[29]),
        surfaceDim = Color(argb[30]),
        surfaceContainer = Color(argb[31]),
        surfaceContainerHigh = Color(argb[32]),
        surfaceContainerHighest = Color(argb[33]),
        surfaceContainerLow = Color(argb[34]),
        surfaceContainerLowest = Color(argb[35]),
        primaryFixed = Color(argb[36]),
        primaryFixedDim = Color(argb[37]),
        onPrimaryFixed = Color(argb[38]),
        onPrimaryFixedVariant = Color(argb[39]),
        secondaryFixed = Color(argb[40]),
        secondaryFixedDim = Color(argb[41]),
        onSecondaryFixed = Color(argb[42]),
        onSecondaryFixedVariant = Color(argb[43]),
        tertiaryFixed = Color(argb[44]),
        tertiaryFixedDim = Color(argb[45]),
        onTertiaryFixed = Color(argb[46]),
        onTertiaryFixedVariant = Color(argb[47]),
    )
}

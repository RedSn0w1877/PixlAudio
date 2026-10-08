package com.theveloper.pixelplay.data.preferences

import androidx.annotation.StringRes
import com.theveloper.pixelplay.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Settings › Appearance › Accent Color (2026-10-07 batch, DECISIONS › Accent colour): the presets of
 * the swatch grid, in grid order. Custom (any colour) is the grid's last cell and has no preset.
 *
 * The seeds are the iOS app's, so a backup moved between the two apps picks the same preset. The
 * swatches show the seed itself; the app shows the accent scheme's tone of it (deeper in light mode,
 * pastel in dark mode, so text on it stays legible). See `generateAccentColorSchemePair`.
 */
enum class AccentPreset(val hex: String, @StringRes val labelRes: Int) {
    /** The default: Material You from the wallpaper on API 31+, the static scheme on API 30 (today's look). */
    DYNAMIC(AccentColor.DEFAULT, R.string.settings_accent_color_dynamic),
    BLUE("#0A84FF", R.string.settings_accent_color_blue),
    INDIGO("#5E5CE6", R.string.settings_accent_color_indigo),
    PURPLE("#BF5AF2", R.string.settings_accent_color_purple),
    PINK("#FF2D55", R.string.settings_accent_color_pink),
    RED("#FF453A", R.string.settings_accent_color_red),
    ORANGE("#FF9F0A", R.string.settings_accent_color_orange),
    YELLOW("#FFD60A", R.string.settings_accent_color_yellow),
    GREEN("#34C759", R.string.settings_accent_color_green),
    MINT("#00C7BE", R.string.settings_accent_color_mint),
    GRAPHITE("#8E8E93", R.string.settings_accent_color_graphite),
}

/**
 * The accent's stored form and the pure colour maths its settings row needs.
 *
 * Stored under `accent_color_v1` in the shared `settings` DataStore as `"#RRGGBB"` (upper case),
 * the same key and format as iOS. [DEFAULT] (`""`) means each platform's own default: [AccentPreset.DYNAMIC]
 * here. Anything unreadable (a hand-edited or foreign backup) reads as [DEFAULT], never as a crash.
 *
 * Plain Kotlin on purpose (no `android.graphics.Color`), so the JVM unit tests run it as is.
 */
object AccentColor {
    const val DEFAULT = ""

    /**
     * The opaque ARGB seed of a stored value: an optional leading `#` and exactly six hex digits, any
     * case, surrounding spaces ignored. `""` and anything else is null (the default).
     */
    fun seedOrNull(hex: String?): Int? {
        val digits = hex?.trim()?.removePrefix("#") ?: return null
        if (digits.length != 6 || !digits.all(::isHexDigit)) return null
        return OPAQUE or digits.toInt(16)
    }

    /** `"#RRGGBB"` (upper case, alpha dropped). [Locale.ROOT], so a Turkish or Arabic locale can't change the digits. */
    fun toHex(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    /** The canonical stored form of [hex]: `"#RRGGBB"`, or [DEFAULT] when it isn't a colour. */
    fun normalize(hex: String?): String = seedOrNull(hex)?.let(::toHex) ?: DEFAULT

    /** The preset [hex] names ([AccentPreset.DYNAMIC] for `""` or junk), or null for a custom colour. */
    fun presetFor(hex: String?): AccentPreset? {
        val seed = seedOrNull(hex) ?: return AccentPreset.DYNAMIC
        return AccentPreset.entries.firstOrNull { it.hex.isNotEmpty() && seedOrNull(it.hex) == seed }
    }

    /** True when [hex] is a colour that no preset names (the grid's Custom cell is selected). */
    fun isCustom(hex: String?): Boolean = presetFor(hex) == null

    /**
     * Whether a check mark on a swatch of [argb] should be dark: white unless the swatch is light
     * enough that white falls under ~3:1 (relative luminance above 0.30, as on iOS). White on blue,
     * red, pink, purple and graphite; black on orange, yellow, green and mint.
     */
    fun prefersDarkContent(argb: Int): Boolean = relativeLuminance(argb) > 0.30

    /** WCAG relative luminance of an sRGB colour (alpha ignored). */
    fun relativeLuminance(argb: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear((argb shr 16) and 0xFF) +
            0.7152 * linear((argb shr 8) and 0xFF) +
            0.0722 * linear(argb and 0xFF)
    }

    /**
     * HSV of an sRGB colour for the custom picker: `[hue 0..360), saturation 0..1, value 0..1]`.
     * The same maths as `android.graphics.Color.colorToHSV`, which is a stub in JVM tests.
     */
    fun argbToHsv(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        var hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        if (hue < 0f) hue += 360f
        if (hue >= 360f) hue -= 360f
        val saturation = if (max == 0f) 0f else delta / max
        return floatArrayOf(hue, saturation, max)
    }

    /** The opaque ARGB colour of an HSV triple (inputs are wrapped / clamped into range). No allocation. */
    fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val r: Float
        val g: Float
        val b: Float
        when ((h / 60f).toInt()) {
            0 -> { r = c; g = x; b = 0f }
            1 -> { r = x; g = c; b = 0f }
            2 -> { r = 0f; g = c; b = x }
            3 -> { r = 0f; g = x; b = c }
            4 -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        return OPAQUE or (channel(r + m) shl 16) or (channel(g + m) shl 8) or channel(b + m)
    }

    private fun channel(fraction: Float): Int = (fraction * 255f).roundToInt().coerceIn(0, 255)

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private const val OPAQUE = -0x1000000 // 0xFF000000
}

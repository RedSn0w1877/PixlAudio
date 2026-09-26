package com.theveloper.pixelplay.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import kotlin.math.roundToInt


private val montserrat = GoogleFont("Montserrat")
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage   = "com.google.android.gms",
    certificates      = R.array.com_google_android_gms_fonts_certs
)

val MontserratFamily = FontFamily(
    Font(googleFont = montserrat, fontProvider = provider, weight = FontWeight.Black),
    Font(googleFont = montserrat, fontProvider = provider, weight = FontWeight.ExtraBold),
    Font(googleFont = montserrat, fontProvider = provider, weight = FontWeight.Bold),
    Font(googleFont = montserrat, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = montserrat, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = montserrat, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = montserrat, fontProvider = provider, weight = FontWeight.Light),
)

val ExpTitleTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = MontserratFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 60.sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 1.5f),
        letterSpacing = (-0.02).em,
        lineHeight = 0.95.em,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    ),
    displayMedium = TextStyle(
        fontFamily = MontserratFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 50.sp,
        //textGeometricTransform = TextGeometricTransform(scaleX = 1f),
        letterSpacing = (-0.02).em,
        lineHeight = 0.95.em,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    ),
    titleMedium = TextStyle(
        fontFamily = MontserratFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        textGeometricTransform = TextGeometricTransform(scaleX = 1.3f),
        letterSpacing = (-0.02).em,
        lineHeight = 0.95.em,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
)

// Google Sans Flex variable font with rounded axis for Google Sans Rounded-like appearance.
private const val GoogleSansFlexRond = 100f

@OptIn(ExperimentalTextApi::class)
val GoogleSansRounded = FontFamily(
    androidx.compose.ui.text.font.Font(
        resId = R.font.gflex_variable,
        weight = FontWeight.Light,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Light.weight),
            FontVariation.Setting("ROND", GoogleSansFlexRond)
        )
    ),
    androidx.compose.ui.text.font.Font(
        resId = R.font.gflex_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Normal.weight),
            FontVariation.Setting("ROND", GoogleSansFlexRond)
        )
    ),
    androidx.compose.ui.text.font.Font(
        resId = R.font.gflex_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Medium.weight),
            FontVariation.Setting("ROND", GoogleSansFlexRond)
        )
    ),
    androidx.compose.ui.text.font.Font(
        resId = R.font.gflex_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.SemiBold.weight),
            FontVariation.Setting("ROND", GoogleSansFlexRond)
        )
    ),
    androidx.compose.ui.text.font.Font(
        resId = R.font.gflex_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Bold.weight),
            FontVariation.Setting("ROND", GoogleSansFlexRond)
        )
    ),
)

/**
 * ROND axis of the karaoke lyrics face. 0 = plain Google Sans Flex (lyrics read best un-rounded,
 * unlike the app's [GoogleSansRounded] at 100). This is the one switch to flip for rounded lyrics.
 */
const val LyricsDisplayRond = 0f

/** Optical size of [LyricsDisplayFamily]: the 34 sp main line. Other sizes use [lyricsDisplayFamily]. */
private const val LyricsDisplayOpticalSizeSp = 34

@OptIn(ExperimentalTextApi::class)
private fun lyricsDisplayFont(weight: FontWeight, opticalSizeSp: Int) = androidx.compose.ui.text.font.Font(
    resId = R.font.gflex_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.Setting("ROND", LyricsDisplayRond),
        FontVariation.width(100f),
        FontVariation.opticalSizing(opticalSizeSp.sp)
    )
)

/**
 * The karaoke lyrics face: the bundled Google Sans Flex (OFL-1.1) at weight 500 (plain lyrics),
 * 600 (translation / pronunciation) and 700 (the sung line), width 100, opsz 34, ROND
 * [LyricsDisplayRond]. Scripts it does not cover fall back to the system font (family only,
 * never the size) — see `LyricsSheet`.
 */
val LyricsDisplayFamily: FontFamily = buildLyricsDisplayFamily(LyricsDisplayOpticalSizeSp)

private fun buildLyricsDisplayFamily(opticalSizeSp: Int) = FontFamily(
    lyricsDisplayFont(FontWeight.Medium, opticalSizeSp),
    lyricsDisplayFont(FontWeight.SemiBold, opticalSizeSp),
    lyricsDisplayFont(FontWeight.Bold, opticalSizeSp),
)

private val lyricsFamiliesBySize = HashMap<Int, FontFamily>()

/**
 * The lyrics face with its optical-size axis at the size it is drawn at (opsz = the rendered
 * size, spec §0): small text gets the text cut, not the tighter display cut. Cached per whole sp.
 */
fun lyricsDisplayFamily(size: TextUnit): FontFamily {
    val sp = if (size.isSp) size.value.roundToInt().coerceIn(LYRICS_OPSZ_MIN, LYRICS_OPSZ_MAX) else LyricsDisplayOpticalSizeSp
    if (sp == LyricsDisplayOpticalSizeSp) return LyricsDisplayFamily
    return synchronized(lyricsFamiliesBySize) {
        lyricsFamiliesBySize.getOrPut(sp) { buildLyricsDisplayFamily(sp) }
    }
}

/**
 * [family] re-cut for [size] when it is the lyrics face; any other family (the system fallback
 * for scripts the app font lacks, `null`) is returned as-is.
 */
fun lyricsFamilyAtSize(family: FontFamily?, size: TextUnit): FontFamily? =
    if (family === LyricsDisplayFamily) lyricsDisplayFamily(size) else family

// Google Sans Flex's opsz axis range.
private const val LYRICS_OPSZ_MIN = 6
private const val LYRICS_OPSZ_MAX = 144

// Tipografía - Usar fuentes amigables y modernas.
// Considerar añadir fuentes personalizadas en res/font para un look más único.
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

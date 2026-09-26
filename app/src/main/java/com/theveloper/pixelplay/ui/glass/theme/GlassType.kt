package com.theveloper.pixelplay.ui.glass.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded

/**
 * Glass mode's text styles: NexHome's `NexType` sizes, weights, letter spacing and line heights
 * verbatim, set in the app's own UI family (NexHome used the system default). The lyrics keep their
 * own font; nothing here touches them.
 */
object GlassType {
    val Display = TextStyle(fontFamily = GoogleSansRounded, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp, lineHeight = 44.sp)
    val Headline = TextStyle(fontFamily = GoogleSansRounded, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, lineHeight = 32.sp)
    val Title = TextStyle(fontFamily = GoogleSansRounded, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, lineHeight = 24.sp)
    val Body = TextStyle(fontFamily = GoogleSansRounded, fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
    val BodyStrong = TextStyle(fontFamily = GoogleSansRounded, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    val Label = TextStyle(fontFamily = GoogleSansRounded, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
    val Caption = TextStyle(fontFamily = GoogleSansRounded, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp, lineHeight = 14.sp)
    val Numeric = TextStyle(fontFamily = GoogleSansRounded, fontSize = 48.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp, fontFeatureSettings = "tnum")
}

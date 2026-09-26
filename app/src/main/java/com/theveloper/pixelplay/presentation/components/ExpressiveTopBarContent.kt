package com.theveloper.pixelplay.presentation.components

import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlin.math.ceil
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import android.graphics.Paint
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.text.style.TextOverflow
import com.theveloper.pixelplay.R
import kotlin.math.roundToInt

private const val DefaultTopBarTitleWidthAxis = 100f
private const val DefaultTopBarTitleCompressedWidthAxis = 78f
private const val TopBarTitleRoundedAxis = 100f
private const val TopBarTitleXtraAxis = 520f
private const val TopBarTitleYopqAxis = 90f
private const val TopBarTitleYtlcAxis = 505f

@Composable
fun ExpressiveTopBarContent(
    title: String,
    collapseFraction: Float,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    collapsedTitleStartPadding: Dp = 56.dp, // Default safe for standard Nav Icon
    expandedTitleStartPadding: Dp = 16.dp,
    collapsedTitleEndPadding: Dp = 24.dp,
    expandedTitleEndPadding: Dp = 24.dp,
    containerHeightRange: Pair<Dp, Dp> = 88.dp to 56.dp,
    collapsedTitleVerticalBias: Float = -1f,
    titleStyle: TextStyle = MaterialTheme.typography.headlineMedium,
    titleScaleRange: Pair<Float, Float> = 1.2f to 0.8f,
    titleFontSizeRange: Pair<TextUnit, TextUnit>? = null,
    maxLines: Int = 2,
    collapsedSubtitleMaxLines: Int = 1,
    expandedSubtitleMaxLines: Int = 1,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fadeSubtitleOnCollapse: Boolean = true,
    enableCollapsedTitleWidthCompression: Boolean = true,
    enableExpandedTitleWidthCompression: Boolean = true,
    titleWidthCompressionThreshold: Dp? = null,
    titleMinWidthAxis: Float = DefaultTopBarTitleCompressedWidthAxis,
    supportingContent: (@Composable () -> Unit)? = null
) {
    val clampedFraction = collapseFraction.coerceIn(0f, 1f)
    val titleScale = lerp(titleScaleRange.first, titleScaleRange.second, clampedFraction)
    val titlePaddingStart = lerp(expandedTitleStartPadding, collapsedTitleStartPadding, clampedFraction)
    val titlePaddingEnd = lerp(expandedTitleEndPadding, collapsedTitleEndPadding, clampedFraction)
    val titleVerticalBias = lerp(1f, collapsedTitleVerticalBias, clampedFraction)
    val animatedTitleAlignment = BiasAlignment(horizontalBias = -1f, verticalBias = titleVerticalBias)
    val titleContainerHeight = lerp(containerHeightRange.first, containerHeightRange.second, clampedFraction)
    val subtitleAlpha = if (fadeSubtitleOnCollapse) 1f - clampedFraction else 1f
    val subtitleMaxLines = if (clampedFraction < 0.5f) expandedSubtitleMaxLines else collapsedSubtitleMaxLines
    val titleFontSize = (titleFontSizeRange?.let { lerp(it.first, it.second, clampedFraction) } ?: titleStyle.fontSize) * titleScale
    val density = LocalDensity.current
    val context = LocalContext.current
    val textMeasurer = rememberTextMeasurer()
    val sanitizedMinWidthAxis = titleMinWidthAxis.coerceIn(1f, DefaultTopBarTitleWidthAxis)
    val expandedMinWidthAxis = if (enableExpandedTitleWidthCompression) sanitizedMinWidthAxis else DefaultTopBarTitleWidthAxis
    val collapsedMinWidthAxis = if (enableCollapsedTitleWidthCompression) sanitizedMinWidthAxis else DefaultTopBarTitleWidthAxis
    val currentMinWidthAxis = lerp(expandedMinWidthAxis, collapsedMinWidthAxis, clampedFraction)
    val titleFontWeight = FontWeight.Bold

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableTitleWidthPx = with(density) {
            (maxWidth - titlePaddingStart - titlePaddingEnd).coerceAtLeast(0.dp).roundToPx()
        }
        val thresholdWidthPx = with(density) {
            titleWidthCompressionThreshold?.coerceAtLeast(0.dp)?.roundToPx()
        }
        val compressionTargetWidthPx = thresholdWidthPx
            ?.coerceAtMost(availableTitleWidthPx)
            ?: availableTitleWidthPx
        val naturalWidthFontFamily = TopBarTitleFonts.fontFamily(
            context = context,
            fontWeight = titleFontWeight,
            widthAxis = DefaultTopBarTitleWidthAxis
        )
        val baseTitleStyle = titleStyle.copy(
            fontSize = titleFontSize,
            lineHeight = titleFontSize * 1.1f,
            fontWeight = titleFontWeight,
            fontFamily = naturalWidthFontFamily
        )
        // The title's natural (uncompressed) width is measured once per title at the two ends of
        // the collapse, then interpolated for the current font size. Glyph advances scale linearly
        // with the size, so this matches a fresh measurement to within a pixel, and scrolling no
        // longer runs a full paragraph layout on every frame.
        val expandedTitleFontSize = (titleFontSizeRange?.first ?: titleStyle.fontSize) * titleScaleRange.first
        val collapsedTitleFontSize = (titleFontSizeRange?.second ?: titleStyle.fontSize) * titleScaleRange.second
        val naturalWidthModel = remember(
            title, titleStyle, expandedTitleFontSize, collapsedTitleFontSize, naturalWidthFontFamily, textMeasurer
        ) {
            fun measureAt(size: TextUnit): Float = textMeasurer.measure(
                text = AnnotatedString(title),
                style = titleStyle.copy(
                    fontSize = size,
                    lineHeight = size * 1.1f,
                    fontWeight = titleFontWeight,
                    fontFamily = naturalWidthFontFamily
                ),
                overflow = TextOverflow.Clip,
                softWrap = false,
                maxLines = 1
            ).multiParagraph.intrinsics.maxIntrinsicWidth
            val expandedWidth = measureAt(expandedTitleFontSize)
            val collapsedWidth = if (collapsedTitleFontSize == expandedTitleFontSize) {
                expandedWidth
            } else {
                measureAt(collapsedTitleFontSize)
            }
            TitleNaturalWidthModel(
                startSize = expandedTitleFontSize.value,
                startWidthPx = expandedWidth,
                endSize = collapsedTitleFontSize.value,
                endWidthPx = collapsedWidth
            )
        }
        val naturalTitleWidthPx = naturalWidthModel.widthPxAt(titleFontSize.value)

        // Long titles sweep the width axis while collapsing; build those typefaces off the main
        // thread up front so the first collapse doesn't create them one per frame.
        val mayCompressTitle = expandedMinWidthAxis < DefaultTopBarTitleWidthAxis ||
            collapsedMinWidthAxis < DefaultTopBarTitleWidthAxis
        val titleCanOverflow = mayCompressTitle && availableTitleWidthPx > 0 &&
            naturalWidthModel.maxWidthPx > compressionTargetWidthPx
        if (titleCanOverflow) {
            val warmFromAxis = minOf(expandedMinWidthAxis, collapsedMinWidthAxis)
            LaunchedEffect(warmFromAxis, titleFontWeight) {
                TopBarTitleFonts.warmUp(context.applicationContext, titleFontWeight, warmFromAxis)
            }
        }
        val shouldCompressTitle = currentMinWidthAxis < DefaultTopBarTitleWidthAxis &&
                availableTitleWidthPx > 0 &&
                naturalTitleWidthPx > compressionTargetWidthPx
        val resolvedWidthAxis = if (shouldCompressTitle && naturalTitleWidthPx > 0) {
            (DefaultTopBarTitleWidthAxis * compressionTargetWidthPx.toFloat() / naturalTitleWidthPx.toFloat())
                .coerceIn(currentMinWidthAxis, DefaultTopBarTitleWidthAxis)
                .roundToInt()
                .toFloat()
        } else {
            DefaultTopBarTitleWidthAxis
        }
        val resolvedTitleStyle = baseTitleStyle.copy(
            fontFamily = TopBarTitleFonts.fontFamily(
                context = context,
                fontWeight = titleFontWeight,
                widthAxis = resolvedWidthAxis
            )
        )

        Box(
            modifier = Modifier
                .align(animatedTitleAlignment)
                .height(titleContainerHeight)
                .fillMaxWidth()
                .padding(start = titlePaddingStart, end = titlePaddingEnd)
        ) {
            Column(
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Text(
                    text = title,
                    style = resolvedTitleStyle,
                    color = contentColor,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        // Removed scaleX/scaleY scaling from graphicsLayer to allow proper ellipsis during layout.
                        // Scaling font size directly ensures Text component is measured with correct constraints.
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) // Scale from left center
                    }
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = subtitleColor,
                        maxLines = subtitleMaxLines,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.alpha(subtitleAlpha)
                    )
                }
                if (supportingContent != null) {
                    Box(modifier = Modifier.alpha(1f - clampedFraction)) {
                        supportingContent()
                    }
                }
            }
        }
    }
}

/** Natural title width as a linear function of font size, from two measured points. */
private class TitleNaturalWidthModel(
    private val startSize: Float,
    private val startWidthPx: Float,
    private val endSize: Float,
    private val endWidthPx: Float,
) {
    val maxWidthPx: Int get() = ceil(maxOf(startWidthPx, endWidthPx)).toInt()

    fun widthPxAt(size: Float): Int {
        val width = if (endSize == startSize) {
            startWidthPx
        } else {
            startWidthPx + (endWidthPx - startWidthPx) * (size - startSize) / (endSize - startSize)
        }
        return ceil(width).toInt()
    }
}

/**
 * Process-wide cache of the title's Google Sans Flex instances, one per (weight, integer width axis).
 *
 * Compose keeps only a small LRU of resolved typefaces, so sweeping the width axis 78..100 while a
 * long title collapses used to re-create variable-font typefaces on the main thread every time.
 * Each instance here is built the way Compose loads a variable [androidx.compose.ui.text.font.Font]
 * resource (the resource typeface with the variation settings applied through a Paint), so the
 * glyphs are identical, and it is built only once per process.
 */
private object TopBarTitleFonts {
    private val cache = ConcurrentHashMap<Int, FontFamily>()

    private fun key(fontWeight: FontWeight, axis: Int): Int = fontWeight.weight * 1000 + axis

    fun fontFamily(context: Context, fontWeight: FontWeight, widthAxis: Float): FontFamily {
        val axis = widthAxis.coerceIn(1f, DefaultTopBarTitleWidthAxis).roundToInt()
        return cache.getOrPut(key(fontWeight, axis)) { build(context, fontWeight, axis) }
    }

    suspend fun warmUp(context: Context, fontWeight: FontWeight, fromAxis: Float) {
        val start = fromAxis.coerceIn(1f, DefaultTopBarTitleWidthAxis).roundToInt()
        withContext(Dispatchers.Default) {
            for (axis in DefaultTopBarTitleWidthAxis.toInt() downTo start) {
                ensureActive()
                val cacheKey = key(fontWeight, axis)
                if (!cache.containsKey(cacheKey)) {
                    cache.putIfAbsent(cacheKey, build(context, fontWeight, axis))
                }
            }
        }
    }

    private fun build(context: Context, fontWeight: FontWeight, axis: Int): FontFamily {
        val variationSettings = listOf(
            "wght" to fontWeight.weight.toFloat(),
            "wdth" to axis.toFloat(),
            "ROND" to TopBarTitleRoundedAxis,
            "XTRA" to TopBarTitleXtraAxis,
            "YOPQ" to TopBarTitleYopqAxis,
            "YTLC" to TopBarTitleYtlcAxis
        ).joinToString(", ") { (tag, value) -> "'" + tag + "' " + value }
        val baseTypeface = context.resources.getFont(R.font.gflex_variable)
        val paint = Paint()
        paint.typeface = baseTypeface
        paint.setFontVariationSettings(variationSettings)
        return FontFamily(ComposeTypeface(paint.typeface ?: baseTypeface))
    }
}

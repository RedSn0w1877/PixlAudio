package com.theveloper.pixelplay.presentation.components.hero

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.size.Size
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.lyrics.background.ArtworkSpriteBaker
import com.theveloper.pixelplay.presentation.lyrics.background.SpriteBlur
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/*
 * The progressively blurred hero shared by the new UI's full-bleed screens (now playing, artist,
 * playlist): the picture fills the top edge to edge, sharp at the top, then a pre-blurred copy of
 * it is masked in and a background-colour scrim melts it into the page under the title.
 *
 * Why a baked bitmap and not a `RenderEffect` blur on API 31+: HWUI applies a RenderEffect as an
 * image filter every time the node is drawn, and these screens redraw every frame while something
 * moves (the player's progress wave, a collapsing header), so the blur would be recomputed 60-120
 * times a second for a picture that never changes. The baked 96 px bitmap is blurred once (a few
 * ms on a background thread), costs one bilinear-filtered textured rect per frame, and looks the
 * same on API 30 (minSdk), where RenderEffect does not exist. The upscale itself is part of the
 * softening. One blur layer per hero, never one per element.
 */

/**
 * The blurred copy of one picture: a 96 px, opaque, pre-blurred and slightly saturated bitmap, plus
 * the mean luminance of its bottom third (where titles end up sitting).
 */
@Immutable
internal class HeroBlur(val key: String, val bitmap: Bitmap, val coverLuma: Float)

/**
 * Bakes [HeroBlur]s off the main thread, once per picture, through the same tiny decode and CPU
 * blur the lyrics background uses ([ArtworkSpriteBaker.decodeArt], [SpriteBlur]). Coil does the
 * fetch, so artist photos from the network come from its disk cache after the first visit.
 */
internal object HeroBlurBaker {
    private val cache = LruCache<String, HeroBlur>(8)

    fun peek(uri: String?): HeroBlur? =
        if (uri.isNullOrBlank()) null else cache.get(uri)

    suspend fun bake(context: Context, uri: String): HeroBlur? {
        peek(uri)?.let { return it }
        return withContext(Dispatchers.Default) {
            try {
                val art = ArtworkSpriteBaker.decodeArt(context.applicationContext, uri)
                    ?: return@withContext null
                ensureActive()
                val size = ArtworkSpriteBaker.ART_TEXELS
                val pixels = SpriteBlur.bakeSprite(art, size, 0, HeroBlurMath.BLUR_SIGMA_TEXELS, opaque = true)
                HeroBlurMath.saturate(pixels, HeroBlurMath.BLUR_SATURATION)
                val luma = HeroBlurMath.meanLuma(pixels, size, size * 2 / 3, size)
                val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
                HeroBlur(uri, bitmap, luma).also { cache.put(uri, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Hero: failed to bake the blurred picture")
                null
            }
        }
    }
}

/**
 * The blurred copy of [uri], baked in the background. Until a new one is ready the previous one
 * stays (heroes cross-fade between them); a picture that can't be decoded gives null.
 */
@Composable
internal fun rememberHeroBlur(uri: String?): State<HeroBlur?> {
    val context = LocalContext.current.applicationContext
    val state = remember { mutableStateOf(HeroBlurBaker.peek(uri)) }
    LaunchedEffect(uri) {
        if (uri.isNullOrBlank()) {
            state.value = null
            return@LaunchedEffect
        }
        state.value = HeroBlurBaker.peek(uri) ?: HeroBlurBaker.bake(context, uri)
    }
    return state
}

/** The blur on screen, the one leaving, and the fade between them (read while drawing). */
@Stable
internal class HeroBlurCrossfade {
    var shown by mutableStateOf<HeroBlur?>(null)
        internal set
    var leaving by mutableStateOf<HeroBlur?>(null)
        internal set
    val progress = Animatable(1f)
}

/** Cross-fades between the old and the new blurred picture over [durationMillis]. */
@Composable
internal fun rememberHeroBlurCrossfade(blur: State<HeroBlur?>, durationMillis: Int = 320): HeroBlurCrossfade {
    val fade = remember { HeroBlurCrossfade().also { it.shown = blur.value } }
    val current = blur.value
    LaunchedEffect(current) {
        if (current === fade.shown) return@LaunchedEffect
        fade.leaving = fade.shown
        fade.shown = current
        fade.progress.snapTo(0f)
        try {
            fade.progress.animateTo(1f, tween(durationMillis = durationMillis, easing = FastOutSlowInEasing))
        } finally {
            fade.leaving = null
        }
    }
    return fade
}

/**
 * The blurred [bitmap] centre-cropped into the [areaWidth]×[areaHeight] box the sharp picture
 * covers (mirrored below it, which continues the blur), shown only where the vertical mask is
 * opaque: clear above [rampStart], fully blurred from [rampEnd] down.
 */
internal fun heroBlurBrush(
    bitmap: Bitmap,
    areaWidth: Float,
    areaHeight: Float,
    rampStart: Float,
    rampEnd: Float,
): Brush {
    val crop = HeroBlurMath.centerCrop(bitmap.width.toFloat(), bitmap.height.toFloat(), areaWidth, areaHeight)
    val image = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.MIRROR).apply {
        setLocalMatrix(
            Matrix().apply {
                setScale(crop.scale, crop.scale)
                postTranslate(crop.dx, crop.dy)
            }
        )
    }
    val mask = LinearGradient(
        0f, rampStart, 0f, rampEnd.coerceAtLeast(rampStart + 1f),
        0x00000000, 0xFF000000.toInt(),
        Shader.TileMode.CLAMP
    )
    // ComposeShader(dst, src, DST_IN): the picture's colour, the gradient's alpha.
    return ShaderBrush(ComposeShader(image, mask, PorterDuff.Mode.DST_IN))
}

/** One tint filter per background colour: a new one only while a palette fades in. */
internal class ScrimTint {
    private var color: Color = Color.Unspecified
    private var filter: ColorFilter? = null

    fun filterFor(background: Color): ColorFilter {
        val cached = filter
        if (cached != null && background == color) return cached
        return ColorFilter.tint(background, BlendMode.SrcIn).also {
            color = background
            filter = it
        }
    }
}

/** The page heroes' scrim as an alpha mask over [height] px ([HeroBlurMath.PAGE_SCRIM_STOPS]). */
private fun pageScrimMask(height: Float): Brush {
    val stops = HeroBlurMath.PAGE_SCRIM_STOPS
    val alphas = HeroBlurMath.PAGE_SCRIM_ALPHAS
    val pairs = Array(stops.size) { i -> stops[i] to Color.Black.copy(alpha = alphas[i]) }
    return Brush.verticalGradient(*pairs, startY = 0f, endY = height)
}

/**
 * A full-bleed page hero (artist, playlist): the picture centre-cropped to fill this composable,
 * the blurred copy masked in from [HeroBlurMath.PAGE_RAMP_START] to [HeroBlurMath.PAGE_RAMP_END]
 * of its height, and the [backgroundColor] scrim on top (a State read only while drawing). Shaders are built in `drawWithCache`
 * (once per size and blur), the background is read while drawing, so a palette fade or a
 * collapsing header only redraws three rects. [placeholder] shows when there is no picture.
 *
 * Size it once (e.g. the expanded header height) and move it with a layer: resizing it rebuilds
 * the shaders.
 */
@Composable
internal fun ProgressiveBlurPageHero(
    imageUrl: String?,
    contentDescription: String?,
    blur: State<HeroBlur?>,
    backgroundColor: State<Color>,
    imageRequestSize: Size?,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
) {
    val background = backgroundColor
    val tint = remember { ScrimTint() }
    val fade = rememberHeroBlurCrossfade(blur)
    Box(modifier = modifier) {
        if (imageUrl.isNullOrBlank()) {
            placeholder()
        } else {
            SmartImage(
                model = imageUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                targetSize = imageRequestSize,
                allowHardware = true,
                crossfadeDurationMillis = 0,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val rampStart = h * HeroBlurMath.PAGE_RAMP_START
                    val rampEnd = h * HeroBlurMath.PAGE_RAMP_END
                    val shownBrush = fade.shown?.let { heroBlurBrush(it.bitmap, w, h, rampStart, rampEnd) }
                    val leavingBrush = fade.leaving?.let { heroBlurBrush(it.bitmap, w, h, rampStart, rampEnd) }
                    val scrim = pageScrimMask(h)
                    onDrawBehind {
                        if (!imageUrl.isNullOrBlank()) {
                            if (leavingBrush != null) drawRect(leavingBrush)
                            if (shownBrush != null) drawRect(shownBrush, alpha = fade.progress.value)
                        }
                        drawRect(scrim, colorFilter = tint.filterFor(background.value))
                    }
                }
        )
    }
}

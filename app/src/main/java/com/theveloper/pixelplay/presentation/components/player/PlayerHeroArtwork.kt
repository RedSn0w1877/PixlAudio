package com.theveloper.pixelplay.presentation.components.player

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.AlbumArtQuality
import com.theveloper.pixelplay.presentation.components.OptimizedAlbumArt
import com.theveloper.pixelplay.presentation.components.SafeOriginalAlbumArtSize
import com.theveloper.pixelplay.presentation.components.albumArtMemoryCacheKey
import com.theveloper.pixelplay.presentation.components.safeAlbumArtTargetSize
import com.theveloper.pixelplay.presentation.lyrics.background.ArtworkSpriteBaker
import com.theveloper.pixelplay.presentation.lyrics.background.SpriteBlur
import com.theveloper.pixelplay.utils.LocalArtworkUri
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs

/**
 * The blurred copy of one cover: a 96 px, opaque, pre-blurred and slightly saturated bitmap, plus
 * the mean luminance of its bottom third (what the title ends up sitting on).
 */
@Immutable
internal class HeroBlur(val key: String, val bitmap: Bitmap, val coverLuma: Float)

/**
 * Bakes [HeroBlur]s off the main thread, once per cover, through the same tiny decode and CPU blur
 * the lyrics background uses ([ArtworkSpriteBaker.decodeArt], [SpriteBlur]).
 *
 * Why a baked bitmap and not a `RenderEffect` blur on API 31+: HWUI applies a RenderEffect as an
 * image filter every time the node is drawn, and the full player redraws every frame while its
 * progress wave moves, so the blur would be recomputed 60-120 times a second for a picture that
 * never changes. The baked 96 px bitmap is blurred once (a few ms on a background thread), costs
 * one bilinear-filtered textured rect per frame, and looks the same on API 30, where RenderEffect
 * does not exist. The upscale itself is part of the softening.
 */
internal object PlayerHeroBlurBaker {
    private val cache = LruCache<String, HeroBlur>(6)

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
                val pixels = SpriteBlur.bakeSprite(art, size, 0, PlayerHeroMath.BLUR_SIGMA_TEXELS, opaque = true)
                PlayerHeroMath.saturate(pixels, PlayerHeroMath.BLUR_SATURATION)
                val luma = PlayerHeroMath.meanLuma(pixels, size, size * 2 / 3, size)
                val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
                HeroBlur(uri, bitmap, luma).also { cache.put(uri, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Player hero: failed to bake the blurred cover")
                null
            }
        }
    }
}

/**
 * The blurred cover for [uri], baked in the background. Until a new one is ready the previous
 * song's stays (the hero cross-fades between them); a cover that can't be decoded gives null.
 */
@Composable
internal fun rememberPlayerHeroBlur(uri: String?): State<HeroBlur?> {
    val context = LocalContext.current.applicationContext
    val state = remember { mutableStateOf(PlayerHeroBlurBaker.peek(uri)) }
    LaunchedEffect(uri) {
        if (uri.isNullOrBlank()) {
            state.value = null
            return@LaunchedEffect
        }
        state.value = PlayerHeroBlurBaker.peek(uri) ?: PlayerHeroBlurBaker.bake(context, uri)
    }
    return state
}

/** Art size the hero requests, the same as the old carousel's so both share Coil's cache. */
internal fun heroArtTargetSize(quality: AlbumArtQuality): Size =
    if (quality.maxSize == 0) SafeOriginalAlbumArtSize else Size(quality.maxSize, quality.maxSize)

/**
 * The full-bleed cover at the top of the now-playing screen: the sharp cover as a square at the
 * top, the baked blurred copy masked in below it (one shader: the bitmap composed DST_IN with a
 * vertical gradient, so no offscreen layer), and one background-colour gradient that darkens the
 * header and melts the hero into the player. Positions come from [PlayerHeroMath.stops] and the
 * hero's own size; everything is built in `drawWithCache`, so a frame only draws three rects.
 *
 * [dragOffsetPx] (a sideways drag in progress) is read in the layer only.
 */
@Composable
internal fun PlayerHeroArtwork(
    song: Song,
    albumArtQuality: AlbumArtQuality,
    blur: State<HeroBlur?>,
    backgroundColor: Color,
    dragOffsetPx: () -> Float,
    modifier: Modifier = Modifier,
) {
    val targetSize = remember(albumArtQuality) { heroArtTargetSize(albumArtQuality) }
    // The background is read while drawing, through a State: the album colours fade over a few
    // hundred ms after a skip, and that must only redraw the scrim, never rebuild the shaders.
    val backgroundState = androidx.compose.runtime.rememberUpdatedState(backgroundColor)
    val scrimTint = remember { ScrimTint() }

    // Cross-fade between the old and the new blurred cover (the sharp cover fades in Coil).
    val current = blur.value
    var shown by remember { mutableStateOf(current) }
    var leaving by remember { mutableStateOf<HeroBlur?>(null) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(current) {
        if (current === shown) return@LaunchedEffect
        leaving = shown
        shown = current
        swap.snapTo(0f)
        try {
            swap.animateTo(1f, tween(durationMillis = 320, easing = FastOutSlowInEasing))
        } finally {
            leaving = null
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            val w = size.width
            val d = PlayerHeroMath.dragOffset(dragOffsetPx(), w)
            translationX = d
            alpha = if (w > 0f) 1f - (abs(d) / w) * 0.6f else 1f
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            OptimizedAlbumArt(
                uri = song.albumArtUriString,
                title = song.title,
                modifier = Modifier.fillMaxSize(),
                targetSize = targetSize
            )
        }
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val stops = PlayerHeroMath.stops(size.width, size.height)
                    val shownBrush = shown?.let { heroBlurBrush(it.bitmap, stops) }
                    val leavingBrush = leaving?.let { heroBlurBrush(it.bitmap, stops) }
                    val scrimMask = heroScrimMask(stops)
                    onDrawBehind {
                        if (leavingBrush != null) drawRect(leavingBrush)
                        if (shownBrush != null) drawRect(shownBrush, alpha = swap.value)
                        drawRect(scrimMask, colorFilter = scrimTint.filterFor(backgroundState.value))
                    }
                }
        )
    }
}

/** `matchParentSize` is a BoxScope member; this hero's Spacer is always the second Box child. */
private fun Modifier.matchParentSize(): Modifier = this.then(Modifier.fillMaxSize())

/**
 * The blurred cover scaled to the W×W square at the top (mirrored below it, which continues the
 * blur), shown only where the vertical mask is opaque: from [PlayerHeroMath.Stops.rampStart]
 * (clear) to [PlayerHeroMath.Stops.rampEnd] (fully blurred) and below.
 */
private fun heroBlurBrush(bitmap: Bitmap, stops: PlayerHeroMath.Stops): Brush {
    val image = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.MIRROR).apply {
        setLocalMatrix(
            Matrix().apply {
                setScale(stops.width / bitmap.width, stops.width / bitmap.height)
            }
        )
    }
    val mask = LinearGradient(
        0f, stops.rampStart, 0f, stops.rampEnd.coerceAtLeast(stops.rampStart + 1f),
        0x00000000, 0xFF000000.toInt(),
        Shader.TileMode.CLAMP
    )
    // ComposeShader(dst, src, DST_IN): the cover's colour, the gradient's alpha.
    return ShaderBrush(ComposeShader(image, mask, PorterDuff.Mode.DST_IN))
}

/**
 * The player's background laid over the hero, as an alpha mask (tinted with the background colour
 * when drawn): a light scrim at the very top for the header buttons, clear over the cover, then
 * rising with the blur to [PlayerHeroMath.TITLE_SCRIM] at the title and to solid at the hero's
 * end, where the player's own background takes over.
 */
private fun heroScrimMask(stops: PlayerHeroMath.Stops): Brush {
    val p1 = stops.fraction(stops.topScrimEnd)
    val p2 = maxOf(p1, stops.fraction(stops.rampStart))
    val p3 = maxOf(p2, stops.fraction(stops.contentTop))
    return Brush.verticalGradient(
        0f to Color.Black.copy(alpha = PlayerHeroMath.TOP_SCRIM),
        p1 to Color.Transparent,
        p2 to Color.Transparent,
        p3 to Color.Black.copy(alpha = PlayerHeroMath.TITLE_SCRIM),
        1f to Color.Black,
        startY = 0f,
        endY = stops.height
    )
}

/** One tint filter per background colour: a new one only while the album colours fade. */
private class ScrimTint {
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

/**
 * Warms the next and previous songs' covers (Coil, at the hero's size) and their blurred copies,
 * so a skip or a swipe shows the new hero at once instead of a placeholder.
 */
@Composable
internal fun PrefetchHeroNeighbors(
    queue: ImmutableList<Song>,
    currentIndex: Int?,
    albumArtQuality: AlbumArtQuality,
) {
    val context = LocalContext.current.applicationContext
    val requestSize = remember(albumArtQuality) { safeAlbumArtTargetSize(heroArtTargetSize(albumArtQuality)) }
    LaunchedEffect(queue, currentIndex, requestSize) {
        val index = currentIndex ?: return@LaunchedEffect
        val loader = coil.Coil.imageLoader(context)
        for (neighbour in intArrayOf(index + 1, index - 1)) {
            val uri = queue.getOrNull(neighbour)?.albumArtUriString?.takeIf { it.isNotBlank() } ?: continue
            val memoryKey = albumArtMemoryCacheKey(uri, requestSize)
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(uri)
                    .size(requestSize)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .apply { if (memoryKey != null) memoryCacheKey(memoryKey) }
                    .diskCachePolicy(
                        if (LocalArtworkUri.isLocalArtworkUri(uri)) CachePolicy.DISABLED else CachePolicy.ENABLED
                    )
                    .allowHardware(true)
                    .build()
            )
            PlayerHeroBlurBaker.bake(context, uri)
        }
    }
}

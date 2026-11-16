package com.theveloper.pixelplay.presentation.components.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.theveloper.pixelplay.presentation.components.hero.HeroBlur
import com.theveloper.pixelplay.presentation.components.hero.HeroBlurBaker
import com.theveloper.pixelplay.presentation.components.hero.ScrimTint
import com.theveloper.pixelplay.presentation.components.hero.heroBlurBrush
import com.theveloper.pixelplay.presentation.components.hero.rememberHeroBlur
import com.theveloper.pixelplay.presentation.components.hero.rememberHeroBlurCrossfade
import com.theveloper.pixelplay.presentation.components.safeAlbumArtTargetSize
import com.theveloper.pixelplay.utils.LocalArtworkUri
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.abs

/**
 * The blurred cover for [uri], baked in the background. Until a new one is ready the previous
 * song's stays (the hero cross-fades between them); a cover that can't be decoded gives null.
 */
@Composable
internal fun rememberPlayerHeroBlur(uri: String?): State<HeroBlur?> = rememberHeroBlur(uri)

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
    val fade = rememberHeroBlurCrossfade(blur)

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
                    // The sharp cover is the W×W square at the top; the blur lines up with it.
                    val shownBrush = fade.shown?.let {
                        heroBlurBrush(it.bitmap, stops.width, stops.width, stops.rampStart, stops.rampEnd)
                    }
                    val leavingBrush = fade.leaving?.let {
                        heroBlurBrush(it.bitmap, stops.width, stops.width, stops.rampStart, stops.rampEnd)
                    }
                    val scrimMask = heroScrimMask(stops)
                    onDrawBehind {
                        if (leavingBrush != null) drawRect(leavingBrush)
                        if (shownBrush != null) drawRect(shownBrush, alpha = fade.progress.value)
                        drawRect(scrimMask, colorFilter = scrimTint.filterFor(backgroundState.value))
                    }
                }
        )
    }
}

/** `matchParentSize` is a BoxScope member; this hero's Spacer is always the second Box child. */
private fun Modifier.matchParentSize(): Modifier = this.then(Modifier.fillMaxSize())

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
            HeroBlurBaker.bake(context, uri)
        }
    }
}

package com.theveloper.pixelplay.presentation.lyrics.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.theveloper.pixelplay.utils.LocalArtworkUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Everything the lyrics background needs for one artwork, baked once per track.
 *
 * [sprites] are four tiny, pre-blurred copies of the art (spec §2.1): sprite 0 is opaque and
 * tiles MIRROR; sprites 1–3 sit centred on transparent padding (3σ) so they fade out on their own
 * with CLAMP. The on-screen size of the *art* part of sprite k is [ArtworkSpriteBaker.spriteArtSize];
 * the padding scales with it.
 */
class SpriteSet internal constructor(
    /** Art URI this set was baked from. */
    val key: String,
    internal val aspectBucket: Int,
    internal val sprites: Array<Bitmap>,
    /** Mean Rec.709 luma (0..1) of the graded, pre-overlay background. Drives §1.2. */
    val meanLuma: Float,
    /** Random per-track starting angles (rad), one per sprite. */
    internal val initialAngles: FloatArray,
    /** Tier-B `RuntimeShader` (API 33+), or null on older devices / if AGSL failed to compile. */
    internal val shader: Shader?,
) {
    val isBright: Boolean get() = meanLuma > LyricsBackgroundGrade.BRIGHT_ART_LUMA
}

/**
 * Decodes the art small through the app's Coil loader, builds the four padded, pre-blurred
 * sprites (3-pass box blur ≈ Gaussian, premultiplied), measures the graded mean luma and caches
 * the result. All of it runs on [Dispatchers.Default]; nothing here touches the main thread.
 *
 * The only thing that depends on the view is the blur-to-sprite-size ratio, which depends only on
 * the view's aspect ratio (short side / long side) — so rotation reuses the same bake.
 */
object ArtworkSpriteBaker {

    /** Decoded art resolution, in texels. */
    internal const val ART_TEXELS = 96

    /** Screen-space blur σ as a fraction of the view's short side (start value; tune on device). */
    internal const val SCREEN_BLUR_FRACTION = 0.09f

    /** Art size of sprites 1–3 as a fraction of the long side; sprite 0 uses the diagonal. */
    private val SPRITE_FRACTIONS = floatArrayOf(0f, 0.80f, 0.50f, 0.25f)

    private const val TWO_PI = (Math.PI * 2.0).toFloat()

    private val cache = LruCache<String, SpriteSet>(4)

    /**
     * On-screen size (px) of the *art* region of sprite [k] for a [width]×[height] view:
     * the diagonal for sprite 0 (covers the view at any rotation), else a fraction of the long side.
     */
    fun spriteArtSize(k: Int, width: Float, height: Float): Float {
        return if (k == 0) {
            sqrt(width * width + height * height)
        } else {
            SPRITE_FRACTIONS[k] * max(width, height)
        }
    }

    /** Short/long side ratio in percent — the only view metric the bake depends on. */
    fun aspectBucket(width: Int, height: Int): Int {
        val longSide = max(width, height)
        if (longSide <= 0) return 50
        val shortSide = minOf(width, height).coerceAtLeast(1)
        return (shortSide * 100f / longSide).roundToInt().coerceIn(10, 100)
    }

    /** Cached set for [uri] if one was baked for the same aspect bucket. Main-thread safe. */
    fun peek(uri: String?, aspectBucket: Int): SpriteSet? {
        if (uri.isNullOrBlank()) return null
        return cache.get(uri)?.takeIf { it.aspectBucket == aspectBucket }
    }

    /**
     * Returns the sprite set for [uri], baking it if needed. Null when the art can't be decoded.
     * Cancellable: a track change mid-bake just drops the work.
     */
    suspend fun bake(context: Context, uri: String, aspectBucket: Int): SpriteSet? {
        peek(uri, aspectBucket)?.let { return it }
        return withContext(Dispatchers.Default) {
            try {
                val art = decodeArt(context.applicationContext, uri) ?: return@withContext null
                ensureActive()
                val set = buildSpriteSet(uri, aspectBucket, art)
                cache.put(uri, set)
                set
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Lyrics background: failed to bake artwork sprites")
                null
            }
        }
    }

    /** Decodes [uri] as unpremultiplied ARGB, centre-cropped to ART_TEXELS². */
    private suspend fun decodeArt(context: Context, uri: String): IntArray? {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(ART_TEXELS, ART_TEXELS)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .allowHardware(false) // CPU pixel access
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            // Our own LruCache keeps the result; don't fill Coil's memory cache with a 96 px copy.
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(
                if (LocalArtworkUri.isLocalArtworkUri(uri)) CachePolicy.DISABLED else CachePolicy.ENABLED
            )
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        val drawable = result.drawable

        val bitmap = Bitmap.createBitmap(ART_TEXELS, ART_TEXELS, Bitmap.Config.ARGB_8888)
        try {
            val intrinsicW = drawable.intrinsicWidth.takeIf { it > 0 } ?: ART_TEXELS
            val intrinsicH = drawable.intrinsicHeight.takeIf { it > 0 } ?: ART_TEXELS
            val scale = max(ART_TEXELS / intrinsicW.toFloat(), ART_TEXELS / intrinsicH.toFloat())
            val drawW = intrinsicW * scale
            val drawH = intrinsicH * scale
            val left = (ART_TEXELS - drawW) / 2f
            val top = (ART_TEXELS - drawH) / 2f
            drawable.setBounds(
                left.roundToInt(),
                top.roundToInt(),
                (left + drawW).roundToInt(),
                (top + drawH).roundToInt()
            )
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(ART_TEXELS * ART_TEXELS)
            bitmap.getPixels(pixels, 0, ART_TEXELS, 0, 0, ART_TEXELS, ART_TEXELS)
            return pixels
        } finally {
            bitmap.recycle()
        }
    }

    private fun buildSpriteSet(uri: String, aspectBucket: Int, art: IntArray): SpriteSet {
        val ratio = aspectBucket / 100f // short side / long side
        val sprites = arrayOfNulls<Bitmap>(4)
        var meanLuma = 0f
        for (k in 0 until 4) {
            // σ_screen = 0.09·S; σ_tex = σ_screen / S_k × 96, and S / S_k only depends on the ratio.
            val shortOverSprite = if (k == 0) {
                ratio / sqrt(1f + ratio * ratio)
            } else {
                ratio / SPRITE_FRACTIONS[k]
            }
            val sigmaTex = SCREEN_BLUR_FRACTION * shortOverSprite * ART_TEXELS
            val opaque = k == 0
            val pad = if (opaque) 0 else ceil(3f * sigmaTex).toInt()
            val size = ART_TEXELS + 2 * pad
            val pixels = SpriteBlur.bakeSprite(art, ART_TEXELS, pad, sigmaTex, opaque)
            if (k == 0) meanLuma = meanGradedLuma(pixels)
            sprites[k] = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }
        @Suppress("UNCHECKED_CAST")
        val bitmaps = sprites as Array<Bitmap>

        val shader: Shader? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                LyricsBackgroundShader.create(bitmaps)
            } catch (e: Exception) {
                Timber.e(e, "Lyrics background: AGSL shader failed, using the ColorMatrix tier")
                null
            }
        } else {
            null
        }

        return SpriteSet(
            key = uri,
            aspectBucket = aspectBucket,
            sprites = bitmaps,
            meanLuma = meanLuma,
            initialAngles = FloatArray(4) { Random.nextFloat() * TWO_PI },
            shader = shader
        )
    }

    /** Mean luma of the graded (pre-overlay) background, measured on the blurred base sprite. */
    private fun meanGradedLuma(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        val scratch = FloatArray(3)
        var sum = 0.0
        for (c in pixels) {
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            sum += LyricsBackgroundGrade.gradedLuma(r, g, b, scratch)
        }
        return (sum / pixels.size).toFloat()
    }
}

/**
 * CPU blur for the tiny sprites: three box passes per axis approximate a Gaussian of the requested
 * σ (box widths from the classic "boxes for Gauss" fit). Runs in premultiplied alpha so the
 * transparent padding doesn't bleed dark fringes into the art. Pure Kotlin — unit-testable.
 */
internal object SpriteBlur {

    /**
     * Box radii (one per pass) whose combined variance best matches [sigma]².
     * Radius 0 means "skip this pass".
     */
    fun boxRadiiForGaussian(sigma: Float, passes: Int = 3): IntArray {
        if (sigma < 0.5f) return IntArray(passes)
        val s2 = sigma * sigma
        val wIdeal = sqrt(12f * s2 / passes + 1f)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        if (wl < 1) wl = 1
        val wu = wl + 2
        val mIdeal = (12f * s2 - passes * wl * wl - 4f * passes * wl - 3f * passes) / (-4f * wl - 4f)
        val m = mIdeal.roundToInt()
        return IntArray(passes) { i -> ((if (i < m) wl else wu) - 1) / 2 }
    }

    /**
     * Places [art] (unpremultiplied ARGB, [artSize]²) centred on a transparent
     * (artSize + 2·[pad])² canvas, blurs it by [sigma] texels and returns unpremultiplied ARGB.
     * When [opaque], alpha is forced to 1 (art composited over black) and edges mirror, matching
     * the MIRROR tile mode the base sprite is drawn with.
     */
    fun bakeSprite(art: IntArray, artSize: Int, pad: Int, sigma: Float, opaque: Boolean): IntArray {
        val size = artSize + 2 * pad
        val n = size * size
        val a = FloatArray(n)
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (y in 0 until artSize) {
            for (x in 0 until artSize) {
                val c = art[y * artSize + x]
                val srcA = (c ushr 24) / 255f
                val i = (y + pad) * size + (x + pad)
                // Premultiply. Forcing alpha to 1 afterwards makes the opaque sprite "art over black".
                a[i] = if (opaque) 1f else srcA
                r[i] = ((c shr 16) and 0xFF) / 255f * srcA
                g[i] = ((c shr 8) and 0xFF) / 255f * srcA
                b[i] = (c and 0xFF) / 255f * srcA
            }
        }

        val radii = boxRadiiForGaussian(sigma)
        val line = FloatArray(size)
        val planes = if (opaque) arrayOf(r, g, b) else arrayOf(a, r, g, b)
        for (plane in planes) {
            for (radius in radii) {
                if (radius <= 0) continue
                // Rows.
                for (y in 0 until size) boxPass(plane, y * size, 1, size, radius, opaque, line)
                // Columns.
                for (x in 0 until size) boxPass(plane, x, size, size, radius, opaque, line)
            }
        }

        val out = IntArray(n)
        for (i in 0 until n) {
            val alpha = a[i].coerceIn(0f, 1f)
            if (alpha <= 1f / 512f) {
                out[i] = 0
                continue
            }
            val inv = 1f / alpha
            val ai = (alpha * 255f).roundToInt().coerceIn(0, 255)
            val ri = (r[i] * inv * 255f).roundToInt().coerceIn(0, 255)
            val gi = (g[i] * inv * 255f).roundToInt().coerceIn(0, 255)
            val bi = (b[i] * inv * 255f).roundToInt().coerceIn(0, 255)
            out[i] = (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        return out
    }

    /**
     * One running-sum box pass over the [n] samples at data[start + i·stride].
     * Out-of-range samples are 0 (transparent padding) or mirrored when [mirror].
     */
    private fun boxPass(
        data: FloatArray,
        start: Int,
        stride: Int,
        n: Int,
        radius: Int,
        mirror: Boolean,
        line: FloatArray
    ) {
        for (i in 0 until n) line[i] = data[start + i * stride]
        val inv = 1f / (2 * radius + 1)
        var sum = 0f
        for (j in -radius..radius) sum += sample(line, n, j, mirror)
        for (i in 0 until n) {
            data[start + i * stride] = sum * inv
            sum += sample(line, n, i + radius + 1, mirror) - sample(line, n, i - radius, mirror)
        }
    }

    private fun sample(line: FloatArray, n: Int, j: Int, mirror: Boolean): Float {
        if (j in 0 until n) return line[j]
        if (!mirror) return 0f
        val period = 2 * n
        val m = ((j % period) + period) % period
        return line[if (m >= n) period - 1 - m else m]
    }
}

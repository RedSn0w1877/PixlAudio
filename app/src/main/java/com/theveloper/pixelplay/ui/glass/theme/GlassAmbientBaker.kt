package com.theveloper.pixelplay.ui.glass.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.theveloper.pixelplay.presentation.lyrics.background.SpriteBlur
import com.theveloper.pixelplay.utils.LocalArtworkUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bakes the glass ambient layer ([GlassAmbientRecipe]) into a small bitmap, entirely on
 * [Dispatchers.Default]. The artwork is decoded tiny (96 texels, like the lyrics background) through
 * the app's Coil loader and blurred with the lyrics background's own [SpriteBlur] — called, never
 * changed (the lyrics renderer is off-limits). The last few bakes are cached by spec, so going back
 * to a recent track (or rotating back) re-uses the bitmap.
 */
object GlassAmbientBaker {

    private const val ART_TEXELS = 96

    private val cache = LruCache<GlassAmbientSpec, ImageBitmap>(3)

    /** Cached bake for [spec], if any. Main-thread safe. */
    fun peek(spec: GlassAmbientSpec): ImageBitmap? = cache.get(spec)

    /** Returns the bitmap for [spec], baking it if needed. Cancellable; null only on failure. */
    suspend fun bake(context: Context, spec: GlassAmbientSpec): ImageBitmap? {
        peek(spec)?.let { return it }
        val appContext = context.applicationContext
        return withContext(Dispatchers.Default) {
            try {
                val art = spec.artUri?.let { decodeArt(appContext, it) }
                ensureActive()
                val baked = render(spec, art)
                ensureActive()
                cache.put(spec, baked)
                baked
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Glass ambient: bake failed")
                null
            }
        }
    }

    private fun render(spec: GlassAmbientSpec, art: IntArray?): ImageBitmap {
        val w = GlassAmbientRecipe.bakeWidth(spec.width)
        val h = GlassAmbientRecipe.bakeHeight(spec.height)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(
            (if (spec.isDark) GlassAmbientRecipe.DarkBase else GlassAmbientRecipe.LightBase).toArgb()
        )

        if (art != null) {
            val blurred = SpriteBlur.bakeSprite(art, ART_TEXELS, 0, GlassAmbientRecipe.ART_BLUR_TEXELS, opaque = true)
            val artBitmap = Bitmap.createBitmap(blurred, ART_TEXELS, ART_TEXELS, Bitmap.Config.ARGB_8888)
            try {
                // Centre-crop the square art over the whole canvas.
                val side = max(w, h).toFloat()
                val left = (w - side) / 2f
                val top = (h - side) / 2f
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = (GlassAmbientRecipe.ART_ALPHA * 255f).roundToInt()
                }
                canvas.drawBitmap(
                    artBitmap,
                    Rect(0, 0, ART_TEXELS, ART_TEXELS),
                    RectF(left, top, left + side, top + side),
                    paint
                )
            } finally {
                artBitmap.recycle()
            }
        }

        val stops = GlassAmbientRecipe.stops(spec)
        if (stops.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = GlassAmbientRecipe.STOP_RADIUS_OF_HEIGHT * h
            for (stop in stops) {
                val color = stop.color.copy(alpha = stop.color.alpha * stop.alpha)
                paint.shader = RadialGradient(
                    stop.center.x * w,
                    stop.center.y * h,
                    radius,
                    color.toArgb(),
                    Color.Transparent.toArgb(),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
            paint.shader = null
        }

        if (GlassAmbientRecipe.hasScrim(spec)) {
            val alpha = GlassAmbientRecipe.scrimAlpha(spec.isDark, meanLuma(bitmap))
            canvas.drawColor(GlassAmbientRecipe.scrimColor(spec.isDark, alpha).toArgb())
        }

        // Upload once: a hardware copy lives in graphics memory and is never re-uploaded.
        val hardware = runCatching { bitmap.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
        return if (hardware != null) {
            bitmap.recycle()
            hardware.asImageBitmap()
        } else {
            bitmap.prepareToDraw()
            bitmap.asImageBitmap()
        }
    }

    /** Mean Rec. 709 luma of [bitmap], sampled on a 4-px grid. */
    private fun meanLuma(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                sum += GlassAmbientRecipe.luma(
                    ((c shr 16) and 0xFF) / 255f,
                    ((c shr 8) and 0xFF) / 255f,
                    (c and 0xFF) / 255f
                )
                count++
                x += 4
            }
            y += 4
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    /** Decodes [uri] as ARGB, centre-cropped to ART_TEXELS², through the app's Coil loader. */
    private suspend fun decodeArt(context: Context, uri: String): IntArray? {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(ART_TEXELS, ART_TEXELS)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .allowHardware(false) // CPU pixel access
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            // The bake cache keeps the result; don't fill Coil's memory cache with a 96 px copy.
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
}

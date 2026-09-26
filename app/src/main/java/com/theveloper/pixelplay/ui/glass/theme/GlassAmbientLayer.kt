package com.theveloper.pixelplay.ui.glass.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Crossfade between two ambient bakes on a track change (G1: ~450 ms, and only then). */
private const val AMBIENT_CROSSFADE_MS = 450

/**
 * The glass ambient layer's live state: the baked bitmap on screen (and the one it is fading out
 * from), plus where the main window's root sits on screen, so a sheet or dialog in its own window
 * can draw the same bitmap aligned ([com.theveloper.pixelplay.ui.glass.rememberGlassWindowBackdrop]).
 * Nothing in here animates except the crossfade after a track change: idle cost is zero.
 */
@Stable
class GlassAmbientState internal constructor(isDark: Boolean) {

    internal var current: ImageBitmap? by mutableStateOf(null)
        private set
    private var previous: ImageBitmap? by mutableStateOf(null)
    private val fade = Animatable(1f)

    /** Painted until the first bake lands (and under a first bake while it fades in). */
    internal var baseColor: Color by mutableStateOf(if (isDark) GlassAmbientRecipe.DarkBase else GlassAmbientRecipe.LightBase)

    /** The ambient root's top-left on screen (px). */
    var rootOnScreen: Offset by mutableStateOf(Offset.Zero)
        internal set

    /** The ambient root's size (px). */
    var rootSize: IntSize by mutableStateOf(IntSize.Zero)
        internal set

    /** Shows [bitmap], crossfading from whatever is on screen. Runs in the caller's (effect) scope. */
    internal suspend fun show(bitmap: ImageBitmap) {
        if (bitmap === current) return
        previous = current
        current = bitmap
        fade.snapTo(0f)
        try {
            fade.animateTo(1f, tween(AMBIENT_CROSSFADE_MS))
        } finally {
            previous = null
        }
    }

    /**
     * Draws the ambient at [dstSize], top-left at the origin, at [alpha]. Reads snapshot state, so a
     * glass node drawing it is invalidated during a crossfade and never otherwise.
     */
    fun draw(scope: DrawScope, dstSize: Size = scope.size, alpha: Float = 1f) {
        val w = dstSize.width.roundToInt()
        val h = dstSize.height.roundToInt()
        if (w <= 0 || h <= 0) return
        val intSize = IntSize(w, h)
        val cur = current
        val f = fade.value
        if (alpha <= 0f) return
        if (cur == null) {
            scope.drawRect(baseColor, size = dstSize, alpha = alpha)
            return
        }
        val prev = previous
        if (f < 1f) {
            if (prev != null) {
                scope.drawImage(prev, dstOffset = IntOffset.Zero, dstSize = intSize, alpha = alpha, filterQuality = FilterQuality.Low)
            } else {
                scope.drawRect(baseColor, size = dstSize, alpha = alpha)
            }
            scope.drawImage(cur, dstOffset = IntOffset.Zero, dstSize = intSize, alpha = f * alpha, filterQuality = FilterQuality.Low)
        } else {
            scope.drawImage(cur, dstOffset = IntOffset.Zero, dstSize = intSize, alpha = alpha, filterQuality = FilterQuality.Low)
        }
    }
}

/** The ambient state of the glass root, or null outside glass mode. */
val LocalGlassAmbient = staticCompositionLocalOf<GlassAmbientState?> { null }

/** Remembers the ambient state for the glass root. */
@Composable
fun rememberGlassAmbientState(isDark: Boolean): GlassAmbientState {
    val state = remember { GlassAmbientState(isDark) }
    SideEffect { state.baseColor = if (isDark) GlassAmbientRecipe.DarkBase else GlassAmbientRecipe.LightBase }
    return state
}

/**
 * The static backdrop every glass surface samples in glass mode (owner decision G1): the current
 * song's artwork, blurred, with 2–3 radial blobs in its palette and NexHome's scrim, baked off the
 * main thread at a quarter of the window size and drawn scaled. It re-bakes only when the track,
 * palette, light/dark or window size changes, and crossfades ~450 ms then; there is no timer.
 *
 * Put `layerBackdrop(root)` on [modifier] and keep every glass reader a SIBLING of this layer.
 *
 * @param blobs 2–3 palette colours for the blobs, or null for NexHome's NEBULA stops.
 */
@Composable
fun GlassAmbientLayer(
    state: GlassAmbientState,
    artUri: String?,
    blobs: List<Color>?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val spec = remember(artUri, blobs, isDark, size) {
        if (size.width <= 0 || size.height <= 0) {
            null
        } else {
            GlassAmbientSpec(artUri, isDark, blobs, size.width, size.height)
        }
    }
    LaunchedEffect(spec) {
        val target = spec ?: return@LaunchedEffect
        val baked = GlassAmbientBaker.bake(context, target) ?: return@LaunchedEffect
        state.show(baked)
    }
    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                if (coordinates.size != size) size = coordinates.size
                state.rootSize = coordinates.size
                state.rootOnScreen = coordinates.positionOnScreen()
            }
            .drawBehind { state.draw(this) }
    )
}

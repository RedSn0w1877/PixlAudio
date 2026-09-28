package com.theveloper.pixelplay.ui.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Root of the glass system. Resolves everything a glass surface needs exactly once — the
 * capability [GlassTier] (API level + live battery saver), the [GlassPalette], the shared
 * [GlassLight], the page recording and its cross-window snapshot — and provides it through
 * composition locals, so individual components never re-derive any of it.
 *
 * Call once, high in the tree, *outside* the recorded page content. Inside the recorded content,
 * wrap screens in [ProvideInlineGlassSource].
 */
@Composable
fun ProvideGlassEnvironment(
    style: AppUiStyle,
    intensity: Float,
    darkTheme: Boolean,
    accent: Color,
    pageBackdrop: PageBackdrop,
    content: @Composable () -> Unit
) {
    val glassOn = style.isGlass
    val powerSave = rememberPowerSaveMode()
    val tier = remember(powerSave) { GlassTier.resolve(powerSave) }
    val palette = remember(darkTheme, accent) { GlassPalette.create(darkTheme, accent) }
    val light = rememberGlassLight(tier = tier, enabled = glassOn)

    if (glassOn && tier.samplesBackdrop) {
        PageSnapshotLoop(pageBackdrop)
    }

    CompositionLocalProvider(
        LocalAppUiStyle provides style,
        LocalGlassTier provides tier,
        LocalGlassPalette provides palette,
        LocalGlassLight provides light,
        LocalGlassIntensity provides intensity,
        LocalAppBackdrop provides pageBackdrop,
        LocalPageBackdrop provides pageBackdrop
    ) {
        content()
        if (glassOn && tier == GlassTier.Refractive) {
            GlassShaderWarmup()
        }
    }
}

/**
 * Re-scopes [LocalAppBackdrop] for content that is itself being recorded into the page backdrop.
 *
 * Glass drawn inside the recorded subtree must not sample the page (that recursion is a native
 * stack overflow), so it samples an [AmbientBackdrop] instead: the screen's background colour with
 * soft light from the current album art spilling across it. Inline glass then still has colour and
 * light to bend — instead of the flat grey tint it had when this was an empty backdrop.
 *
 * Screens with floating glass over scrolling content should go one step further and record that
 * content locally (see [glassSource]), so their glass refracts what is actually behind it.
 */
@Composable
fun ProvideInlineGlassSource(
    background: Color,
    ambientScheme: ColorScheme?,
    content: @Composable () -> Unit
) {
    val ambient = remember { AmbientBackdrop() }
    ambient.update(
        background = background,
        primary = ambientScheme?.primary ?: background,
        secondary = ambientScheme?.secondary ?: background,
        tertiary = ambientScheme?.tertiary ?: background
    )
    CompositionLocalProvider(LocalAppBackdrop provides ambient, content = content)
}

/**
 * A window-anchored field of light: the page background, with three wide, soft pools of the album
 * palette. Anchored to the window rather than the glass element, so two neighbouring glass buttons
 * show two neighbouring slices of the same light instead of each getting its own copy.
 *
 * Costs one rect and three radial fills per glass draw, with brushes cached per window size and
 * palette — cheaper than any recording, and no recursion risk because it records nothing.
 */
@Stable
class AmbientBackdrop internal constructor() : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    private var background by mutableStateOf(Color.Black)
    private var primary by mutableStateOf(Color.Black)
    private var secondary by mutableStateOf(Color.Black)
    private var tertiary by mutableStateOf(Color.Black)

    private var cacheSize = Size.Zero
    private var cacheColors = arrayOf(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)
    private var brushes: Array<Brush> = emptyArray()
    private val centers = arrayOf(Offset.Zero, Offset.Zero, Offset.Zero)
    private val radii = floatArrayOf(0f, 0f, 0f)

    internal fun update(background: Color, primary: Color, secondary: Color, tertiary: Color) {
        if (this.background != background) this.background = background
        if (this.primary != primary) this.primary = primary
        if (this.secondary != secondary) this.secondary = secondary
        if (this.tertiary != tertiary) this.tertiary = tertiary
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val bg = background
        val p = primary
        val s = secondary
        val t = tertiary
        // Oversized fill: the blur effect samples past the element's own bounds.
        drawRect(bg, topLeft = Offset(-size.width, -size.height), size = size * 3f)
        val coords = coordinates ?: return
        if (!coords.isAttached) return
        val root = coords.findRootCoordinates().size
        val window = Size(root.width.toFloat(), root.height.toFloat())
        if (window.width <= 0f || window.height <= 0f) return
        ensureBrushes(window, bg, p, s, t)
        val origin = coords.positionInWindow()
        translate(-origin.x, -origin.y) {
            for (i in brushes.indices) {
                drawCircle(brush = brushes[i], radius = radii[i], center = centers[i])
            }
        }
    }

    private fun ensureBrushes(window: Size, bg: Color, p: Color, s: Color, t: Color) {
        val c = cacheColors
        if (window == cacheSize && brushes.isNotEmpty() &&
            c[0] == bg && c[1] == p && c[2] == s && c[3] == t
        ) return
        cacheSize = window
        c[0] = bg; c[1] = p; c[2] = s; c[3] = t
        val w = window.width
        val h = window.height
        val r = maxOf(w, h)
        centers[0] = Offset(w * 0.15f, h * 0.12f); radii[0] = r * 0.55f
        centers[1] = Offset(w * 0.95f, h * 0.50f); radii[1] = r * 0.45f
        centers[2] = Offset(w * 0.30f, h * 0.95f); radii[2] = r * 0.50f
        brushes = arrayOf(
            pool(p, 0.34f, centers[0], radii[0]),
            pool(t, 0.26f, centers[1], radii[1]),
            pool(s, 0.28f, centers[2], radii[2])
        )
    }

    private fun pool(color: Color, alpha: Float, center: Offset, radius: Float): Brush =
        Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.55f to color.copy(alpha = alpha * 0.35f),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius
        )
}

/**
 * Keeps [PageBackdrop.snapshotBitmap] fresh — but only while something in another window (a
 * sheet, a dialog) is actually showing glass that needs it.
 *
 * The previous version rasterized the full page three times a second, forever, whenever glass was
 * on: a permanent GPU readback that bought nothing on the 99% of frames with no sheet open.
 */
@Composable
private fun PageSnapshotLoop(pageBackdrop: PageBackdrop) {
    LaunchedEffect(pageBackdrop) {
        snapshotFlow { pageBackdrop.snapshotDemand > 0 }
            .distinctUntilChanged()
            .collectLatest { needed ->
                if (!needed) {
                    pageBackdrop.snapshotBitmap = null
                    return@collectLatest
                }
                while (true) {
                    runCatching {
                        pageBackdrop.snapshotBitmap = pageBackdrop.graphicsLayer.toImageBitmap()
                    }
                    // The page behind an open sheet is almost always static; 2 Hz reads as live.
                    delay(500)
                }
            }
    }
}

/**
 * Draws one throwaway glass element for a few frames right after launch, so the AGSL lens and
 * highlight shaders are compiled before the user's first real interaction with glass instead of
 * during it (that first-use compile was a visible hitch on the first nav-bar drag).
 */
@Composable
private fun GlassShaderWarmup() {
    var done by rememberSaveable { mutableStateOf(false) }
    if (done) return
    var frames by remember { mutableIntStateOf(0) }
    val warmBackdrop = rememberCanvasBackdrop(WarmupFill)
    Box(
        Modifier
            .size(8.dp)
            .graphicsLayer { alpha = 0.01f }
            .drawBackdrop(
                backdrop = warmBackdrop,
                shape = { WarmupShape },
                effects = {
                    vibrancy()
                    blur(2.dp.toPx())
                    lens(2.dp.toPx(), 4.dp.toPx(), depthEffect = true, chromaticAberration = true)
                },
                highlight = { Highlight.Default },
                shadow = null
            )
    )
    LaunchedEffect(Unit) {
        while (frames < 3) {
            withFrameNanos { }
            frames++
        }
        done = true
    }
}

private val WarmupShape = RoundedCornerShape(4.dp)
private val WarmupFill: DrawScope.() -> Unit = { drawRect(Color.Gray) }

/** Live battery-saver state. Glass drops its lens when this is on. */
@Composable
fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    var powerSave by remember(powerManager) { mutableStateOf(powerManager?.isPowerSaveMode == true) }
    DisposableEffect(context, powerManager) {
        if (powerManager == null) return@DisposableEffect onDispose { }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                powerSave = powerManager.isPowerSaveMode
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        powerSave = powerManager.isPowerSaveMode
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return powerSave
}

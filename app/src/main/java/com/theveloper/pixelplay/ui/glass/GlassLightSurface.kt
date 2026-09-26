package com.theveloper.pixelplay.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

/**
 * A static light glass surface as a modifier: the non-interactive `GlassPanel` light recipe
 * (`vibrancy()` + lens 12/24, plain rim, the palette's subtle tint) for an existing layout that
 * can't be restructured into a panel — the queue's now-playing row (NexHome's `GlassSettingRow`
 * look, research-nexhome-design §10.2). One glass node; content keeps its own press handling.
 * Frosted tier (API 31–32): [FrostedBlur] instead of the lens.
 *
 * [shape] must be lens-compatible.
 */
@Composable
fun Modifier.glassLightSurface(
    shape: Shape,
    tint: Color = LocalGlassPalette.current.tintSubtle,
): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val capability = LocalGlassCapability.current
    val surface = remember(backdrop, capability, shape, tint) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                if (capability.hasLens) lens(12.dp.toPx(), 24.dp.toPx()) else blur(FrostedBlur.toPx())
            },
            highlight = { Highlight.Plain },
            shadow = null,
            onDrawSurface = { drawRect(tint) },
        )
    }
    return this.then(surface)
}

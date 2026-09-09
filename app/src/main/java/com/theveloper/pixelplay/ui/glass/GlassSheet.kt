package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight

/**
 * A modifier that turns a sheet's own surface into glass. Pair with
 * `containerColor = Color.Transparent` on the `ModalBottomSheet`, or the opaque container will
 * simply cover the refraction.
 *
 * Applied to the sheet's `modifier` rather than wrapping its content, so the glass covers the
 * full sheet surface including the drag handle area.
 */
@Composable
fun Modifier.glassSheetSurface(
    shape: CornerBasedShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow
): Modifier {
    if (!isGlassEnabled) return this

    // LocalPageBackdrop, not LocalAppBackdrop: a sheet opened from inside a screen would otherwise
    // inherit that screen's deliberately-empty backdrop and so have nothing to refract, which is
    // why sheets only ever looked tinted. See LocalPageBackdrop's doc for why this is safe here
    // (sheets render in their own window, outside the recorded draw pass) but not inline.
    val backdrop = LocalPageBackdrop.current
    // Values taken directly from the reference's own DialogContent.kt, not derived from
    // glassEffects()'s button-scale baseline — a sheet/dialog is its own size class in the
    // reference, with its own hand-picked numbers (heavier blur, no chromaticAberration).
    val isLightTheme = androidx.compose.foundation.isSystemInDarkTheme().not()
    // Transparency dial, not refraction: how much of the tint shows through.
    val tint = containerColor.copy(alpha = lerp(0.35f, 0.75f, glassTransparency()))

    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur((if (isLightTheme) 16.dp else 8.dp).toPx())
            lens(24.dp.toPx(), 48.dp.toPx(), depthEffect = true)
        },
        highlight = { Highlight.Default },
        // ModalBottomSheet already provides the scrim and elevation shadow; a second one here
        // would only darken the sheet's own top edge.
        shadow = null,
        onDrawSurface = { drawRect(tint) }
    )
}

/**
 * The container colour to hand a sheet: transparent under glass (the glass draws the surface),
 * the normal tonal colour otherwise. Lets a call site stay style-agnostic.
 */
@Composable
fun glassSheetContainerColor(
    opaque: Color = MaterialTheme.colorScheme.surfaceContainerLow
): Color = if (isGlassEnabled) Color.Transparent else opaque

/**
 * Container for a modal bottom sheet's contents.
 *
 * Under [AppUiStyle.LiquidGlass] the sheet becomes a refracting panel; under
 * [AppUiStyle.Material3] it stays an opaque tonal surface. Callers pass
 * `containerColor = Color.Transparent` to their `ModalBottomSheet` and wrap the body in this, so
 * the same call site works for both styles.
 *
 * The tint here is heavier than [glassTint]'s default. A sheet covers much more of the screen than
 * a nav bar, sits over arbitrary content, and holds long-form text — at nav-bar transparency the
 * text underneath competes with the text on top and both become hard to read.
 */
@Composable
fun GlassSheetContainer(
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable BoxScope.() -> Unit
) {
    if (!isGlassEnabled) {
        Box(
            modifier = modifier.fillMaxWidth(),
            content = content
        )
        return
    }

    // LocalPageBackdrop, not LocalAppBackdrop: a sheet opened from inside a screen would otherwise
    // inherit that screen's deliberately-empty backdrop and so have nothing to refract, which is
    // why sheets only ever looked tinted. See LocalPageBackdrop's doc for why this is safe here
    // (sheets render in their own window, outside the recorded draw pass) but not inline.
    val backdrop = LocalPageBackdrop.current
    // ModalBottomSheet renders into its own Android Window (its internal Dialog), so it can't
    // draw the live GraphicsLayer the main window records — PageBackdrop falls back to a
    // periodically-rasterized snapshot for exactly this case (see its snapshotBitmap doc and the
    // capture loop in MainActivity). Nothing else needed here: LocalPageBackdrop already resolves
    // to that same PageBackdrop instance, snapshot fallback included.
    //
    // Values below are taken directly from the reference's own DialogContent.kt, not derived from
    // glassEffects()'s button-scale baseline — a sheet/dialog is its own size class in the
    // reference, with its own hand-picked numbers (heavier blur, no chromaticAberration).
    val isLightTheme = androidx.compose.foundation.isSystemInDarkTheme().not()
    // Transparency dial, not refraction: how much of the tint shows through.
    val tint = containerColor.copy(alpha = lerp(0.35f, 0.75f, glassTransparency()))

    Box(
        // fillMaxWidth, not fillMaxSize: a Box that always claims the full available height
        // forces every sheet using this container to expand to the screen's max height, even
        // when its content is short — the content then sits top-aligned with a large dead zone
        // below it, which is what read as the button row being "way too high". Width still needs
        // filling so the backdrop shape spans the sheet's actual (variable) width.
        modifier = modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur((if (isLightTheme) 16.dp else 8.dp).toPx())
                    lens(24.dp.toPx(), 48.dp.toPx(), depthEffect = true)
                },
                highlight = { Highlight.Default },
                // The sheet already casts the scrim/elevation shadow that ModalBottomSheet
                // provides, so a second one here would just darken its own top edge.
                shadow = null,
                onDrawSurface = { drawRect(tint) }
            ),
        content = content
    )
}

package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/**
 * What a window-backed glass surface (a sheet or dialog) refracts: the half-size window snapshot,
 * never the live page layer — that belongs to the main window, and a sheet renders in its own.
 * Also registers the caller for the on-demand snapshot for as long as it is composed.
 */
@Composable
internal fun rememberSheetBackdrop(): Backdrop {
    val page = LocalPageBackdrop.current
    RegisterGlassSnapshot(page)
    return (page as? PageBackdrop)?.snapshotBackdrop ?: page
}

/**
 * A modifier that turns a sheet's own surface into glass. Pair with
 * `containerColor = Color.Transparent` on the `ModalBottomSheet`, or the opaque container will
 * simply cover the refraction.
 *
 * Applied to the sheet's `modifier` rather than wrapping its content, so the glass covers the
 * full sheet surface including the drag handle area. Uses the Sheet recipe: `colorControls`
 * filter, 16dp (light) / 12dp (dark) blur, a depth lens, the plain highlight and no shadow
 * (`ModalBottomSheet` already provides the scrim).
 */
@Composable
fun Modifier.glassSheetSurface(
    shape: CornerBasedShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow
): Modifier {
    if (!isGlassEnabled) return this
    val backdrop = rememberSheetBackdrop()
    val recipe = resolveRecipe(GlassRole.Sheet).withTintColor(containerColor)
    return this.liquidGlass(recipe = recipe, shape = shape, backdrop = backdrop)
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
 * Its content sits *on* glass ([GlassLayer.OnGlass]): glass buttons inside render as fills rather
 * than stacking a second layer of glass. The sheet renders in its own window, so nothing inside
 * is part of any main-window recording.
 *
 * The tint is heavier than a nav bar's. A sheet covers much more of the screen, sits over arbitrary
 * content and holds long-form text — at nav-bar transparency the text underneath competes with the
 * text on top.
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

    val backdrop = rememberSheetBackdrop()
    val recipe = resolveRecipe(GlassRole.Sheet).withTintColor(containerColor)

    Box(
        // fillMaxWidth, not fillMaxSize: a Box that always claims the full available height
        // forces every sheet using this container to expand to the screen's max height, even
        // when its content is short. Width still needs filling so the glass spans the sheet.
        modifier = modifier
            .fillMaxWidth()
            .liquidGlass(recipe = recipe, shape = shape, backdrop = backdrop)
    ) {
        CompositionLocalProvider(
            LocalGlassLayer provides GlassLayer.OnGlass,
            LocalRecordingBackdrops provides emptySet()
        ) {
            content()
        }
    }
}

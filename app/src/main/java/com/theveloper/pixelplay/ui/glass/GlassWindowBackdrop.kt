package com.theveloper.pixelplay.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.theveloper.pixelplay.ui.glass.theme.GlassAmbientState
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassAmbient

/**
 * The glass backdrop for surfaces in ANOTHER window (a `ModalBottomSheet` or `Dialog`, owner
 * decision G2: they keep their window type). A `layerBackdrop` can't cross windows, so this draws the
 * same baked ambient bitmap the main window's glass samples, positioned so it lines up on screen with
 * the main window's ambient layer: the sheet's glass refracts exactly what is behind it.
 *
 * It is a canvas-style backdrop (the library's `rememberCanvasBackdrop` idea) that also knows where
 * each reading node sits on screen, which a plain canvas backdrop can't. Cheap: one scaled bitmap
 * draw per glass node, redrawn only when the node moves or the ambient crossfades.
 *
 * Only the kit's `drawBackdrop` wrapper and `drawPlainBackdrop` should read it; both pass no
 * `layerBlock` to the library (the wrapper applies its own inverse), so none is handled here.
 *
 * Outside glass mode (no ambient state) this is an empty backdrop.
 */
@Composable
fun rememberGlassWindowBackdrop(): Backdrop {
    val state = LocalGlassAmbient.current ?: return emptyBackdrop()
    return remember(state) { GlassWindowBackdrop(state) }
}

/**
 * Provides the window-aligned ambient as the base glass backdrop for [content]. Wrap a sheet's or
 * dialog's content with it in glass mode.
 */
@Composable
fun ProvideGlassWindowBackdrop(content: @Composable () -> Unit) {
    ProvideBaseGlassBackdrop(rememberGlassWindowBackdrop(), content)
}

@Immutable
private class GlassWindowBackdrop(private val state: GlassAmbientState) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val c = coordinates ?: return
        if (!c.isAttached) return
        val rootSize = state.rootSize
        if (rootSize.width <= 0 || rootSize.height <= 0) return
        val offset = c.positionOnScreen() - state.rootOnScreen
        translate(-offset.x, -offset.y) {
            state.draw(this, rootSize.toSize())
        }
    }
}

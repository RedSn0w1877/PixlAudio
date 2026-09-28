package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop

/** True inside [GlassModalBottomSheet]'s body, where the whole sheet is already glass. */
private val LocalInGlassSheet = staticCompositionLocalOf { false }

private val DefaultSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * [ModalBottomSheet] with the same signature, whose *entire* surface is glass — drag handle and
 * bottom inset included.
 *
 * The previous approach made the sheet container transparent and wrapped only the content in
 * glass, so the strip holding the drag handle (and the navigation-bar inset below the content)
 * was a see-through hole over the scrim. Glass can't go on `ModalBottomSheet`'s own `modifier`
 * either: `drawBackdrop` there froze the sheet at its first, undersized measurement. So this
 * takes over the handle and insets and draws them *inside* one glass body.
 *
 * Nested [GlassSheetContainer]s in existing call sites become pass-through, so converting a call
 * site is just the rename.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.modalWindowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    if (!isGlassEnabled) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            sheetMaxWidth = sheetMaxWidth,
            sheetGesturesEnabled = sheetGesturesEnabled,
            shape = shape,
            // Call sites written against the old kit pass glassSheetContainerColor(), which is
            // already opaque here; anything else passes through untouched.
            containerColor = containerColor,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            scrimColor = scrimColor,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            properties = properties,
            content = content
        )
        return
    }

    val glassShape = shape as? CornerBasedShape ?: DefaultSheetShape
    val sheetContentColor = if (containerColor == Color.Transparent) MaterialTheme.colorScheme.onSurface else contentColor
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = glassShape,
        containerColor = Color.Transparent,
        contentColor = sheetContentColor,
        tonalElevation = 0.dp,
        scrimColor = scrimColor,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = properties
    ) {
        val source = rememberSheetBackdrop()
        Column(
            Modifier
                .fillMaxWidth()
                .glassSheetBody(glassShape, source)
                .windowInsetsPadding(contentWindowInsets())
        ) {
            if (dragHandle != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    dragHandle()
                }
            }
            // Glass controls inside the sheet sample the same snapshot instead of a backdrop
            // that lives in the main window.
            CompositionLocalProvider(
                LocalInGlassSheet provides true,
                LocalAppBackdrop provides source
            ) {
                content()
            }
        }
    }
}

/** The glass body of a sheet: heavy [GlassMaterial.Thick] frost over a snapshot of the page. */
@Composable
private fun Modifier.glassSheetBody(shape: CornerBasedShape, source: Backdrop): Modifier {
    return this.glass(
        shape = shape,
        material = GlassMaterial.Thick,
        backdrop = source,
        shadow = false
    )
}

/**
 * The page as seen from another window. Registers demand for the page snapshot for as long as the
 * caller is composed, which is the only time the snapshot loop runs at all.
 */
@Composable
internal fun rememberSheetBackdrop(): Backdrop {
    val page = LocalPageBackdrop.current as? PageBackdrop ?: return emptyBackdrop()
    DisposableEffect(page) {
        page.acquireSnapshot()
        onDispose { page.releaseSnapshot() }
    }
    return page.snapshotView
}

/**
 * A modifier that turns a sheet's own surface into glass. Pair with
 * `containerColor = Color.Transparent`. Prefer [GlassModalBottomSheet], which also covers the
 * drag handle and insets.
 */
@Composable
fun Modifier.glassSheetSurface(
    shape: CornerBasedShape = DefaultSheetShape,
    @Suppress("UNUSED_PARAMETER") containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow
): Modifier {
    if (!isGlassEnabled || LocalInGlassSheet.current) return this
    return this.glassSheetBody(shape, rememberSheetBackdrop())
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
 * Container for a sheet's contents. Pass-through inside [GlassModalBottomSheet] (the sheet is
 * already glass); a glass panel of its own when used inside a plain `ModalBottomSheet`.
 */
@Composable
fun GlassSheetContainer(
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = DefaultSheetShape,
    @Suppress("UNUSED_PARAMETER") containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable BoxScope.() -> Unit
) {
    if (!isGlassEnabled || LocalInGlassSheet.current) {
        Box(modifier = modifier.fillMaxWidth(), content = content)
        return
    }
    // fillMaxWidth, not fillMaxSize: claiming full height forces short sheets to expand to the
    // screen's max height with a dead zone under the content.
    val source = rememberSheetBackdrop()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .glassSheetBody(shape, source)
    ) {
        CompositionLocalProvider(LocalAppBackdrop provides source) {
            content()
        }
    }
}

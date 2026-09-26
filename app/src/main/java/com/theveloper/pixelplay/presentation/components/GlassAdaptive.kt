package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.theveloper.pixelplay.ui.glass.GlassPressIndication
import com.theveloper.pixelplay.ui.glass.glassPressSwell
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import androidx.compose.foundation.indication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import com.theveloper.pixelplay.ui.glass.GlassSheetScrim
import com.theveloper.pixelplay.ui.glass.LocalGlassModeEnabled
import com.theveloper.pixelplay.ui.glass.ProvideGlassWindowBackdrop
import com.theveloper.pixelplay.ui.glass.components.GlassPanel
import com.theveloper.pixelplay.ui.glass.controls.LocalLensBloom
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.GlassPalette
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

// ------------------------------------------------------------------------------------------------
// Liquid Glass mode for Material content (orchestrator decisions G2 / G3).
//
// Every helper here is a pass-through in Material 3 mode: it calls the very Material component it
// replaces with the caller's arguments, so the default mode draws exactly what it drew before.
// ------------------------------------------------------------------------------------------------

/**
 * The app's colour scheme before a glass container re-mapped it, for the few popups (menus) that
 * must stay opaque inside glass content. Null outside glass content.
 */
val LocalBaseColorScheme = staticCompositionLocalOf<ColorScheme?> { null }

/**
 * Re-maps a Material colour scheme for content drawn over glass: the page and surface colours become
 * clear (the ambient layer or the glass panel shows through) and the surface containers become
 * translucent versions of themselves, so tiles read as flat smoky (dark) or frosted (light) glass
 * tints. Text takes the glass palette's emphasis levels. Accents keep the scheme's own colours (an
 * album page keeps its album colours); coloured containers keep their hue at 60 %.
 *
 * Every re-mapped colour is the ORIGINAL colour with a lower alpha, never a new colour, so the
 * common `surface.copy(alpha = x)` / `surfaceVariant.copy(alpha = x)` patterns still give exactly
 * the colour Material 3 mode draws (a masking overlay stays a masking overlay). Applying the remap
 * twice changes nothing. The alphas are PixlAudio's (NexHome has no Material content) and may need
 * tuning on the device.
 */
fun ColorScheme.forGlassContent(palette: GlassPalette): ColorScheme = copy(
    background = background.copy(alpha = 0f),
    onBackground = palette.primary,
    surface = surface.copy(alpha = 0f),
    onSurface = palette.primary,
    surfaceVariant = surfaceVariant.copy(alpha = GlassSurfaceRamp[2]),
    onSurfaceVariant = palette.secondary,
    surfaceTint = surfaceTint.copy(alpha = 0f),
    surfaceBright = surfaceBright.copy(alpha = GlassSurfaceRamp[3]),
    surfaceDim = surfaceDim.copy(alpha = 0f),
    surfaceContainerLowest = surfaceContainerLowest.copy(alpha = GlassSurfaceRamp[0]),
    surfaceContainerLow = surfaceContainerLow.copy(alpha = GlassSurfaceRamp[1]),
    surfaceContainer = surfaceContainer.copy(alpha = GlassSurfaceRamp[2]),
    surfaceContainerHigh = surfaceContainerHigh.copy(alpha = GlassSurfaceRamp[3]),
    surfaceContainerHighest = surfaceContainerHighest.copy(alpha = GlassSurfaceRamp[4]),
    primaryContainer = primaryContainer.copy(alpha = GlassTintedContainerAlpha),
    secondaryContainer = secondaryContainer.copy(alpha = GlassTintedContainerAlpha),
    tertiaryContainer = tertiaryContainer.copy(alpha = GlassTintedContainerAlpha),
)

/** Container alphas over glass: lowest, low, container, high, highest. */
private val GlassSurfaceRamp = floatArrayOf(0.28f, 0.34f, 0.40f, 0.46f, 0.52f)

private const val GlassTintedContainerAlpha = 0.6f

/**
 * The press feedback glass content uses in place of the ripple (see [GlassPressIndication]): NexHome's
 * plain white glow (only accented panels tint theirs). Deliberately not tied to the track's accent:
 * it is the default indication of every clickable on a glass page, so a per-track change would
 * recompose all of them on every skip.
 */
@Composable
fun rememberGlassPressIndication(): GlassPressIndication = remember { GlassPressIndication(Color.White) }

/**
 * Material content over glass: [MaterialTheme] with [forGlassContent] colours, the palette's
 * content colour and the glass press feedback as the default indication. A no-op outside glass
 * mode.
 */
@Composable
fun GlassContentTheme(content: @Composable () -> Unit) {
    if (!LocalGlassModeEnabled.current) {
        content()
        return
    }
    val scheme = MaterialTheme.colorScheme
    GlassContentThemeFor(
        scheme = scheme,
        base = LocalBaseColorScheme.current ?: scheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

@Composable
private fun GlassContentThemeFor(
    scheme: ColorScheme,
    base: ColorScheme,
    typography: Typography,
    shapes: Shapes,
    content: @Composable () -> Unit,
) {
    val palette = LocalGlassPalette.current
    // Remapping an already remapped scheme is harmless; [base] keeps the real colours for menus.
    // Keyed on the light/dark palette only (its text colours), never on the per-track accent: a new
    // scheme instance recomposes every colour reader on the page.
    val glassPalette = if (palette.isDark) GlassPalette.Dark else GlassPalette.Light
    val glassScheme = remember(scheme, glassPalette) { scheme.forGlassContent(glassPalette) }
    val indication = rememberGlassPressIndication()
    MaterialTheme(colorScheme = glassScheme, typography = typography, shapes = shapes) {
        CompositionLocalProvider(
            LocalBaseColorScheme provides base,
            LocalContentColor provides palette.primary,
            LocalIndication provides indication,
            content = content,
        )
    }
}

/**
 * A nested [MaterialTheme] (an album / artist / genre page's own scheme) that stays glass-aware:
 * Material 3 mode is exactly `MaterialTheme(colorScheme, typography, shapes)`; glass mode applies
 * [forGlassContent] to that scheme and keeps the glass press feedback.
 */
@Composable
fun GlassAwareMaterialTheme(
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    typography: Typography = MaterialTheme.typography,
    shapes: Shapes = MaterialTheme.shapes,
    content: @Composable () -> Unit,
) {
    if (LocalGlassModeEnabled.current) {
        GlassContentThemeFor(colorScheme, colorScheme, typography, shapes, content)
    } else {
        MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes, content = content)
    }
}

/**
 * Restores the app's real Material colours inside glass content, for popups that need an opaque
 * container (menus) and for surfaces that must keep their exact Material look (the sync editor's
 * tap-timing dialog, G4). A no-op outside glass content.
 */
@Composable
fun RestoreMaterialColors(content: @Composable () -> Unit) {
    val base = LocalBaseColorScheme.current
    if (base == null) {
        content()
        return
    }
    MaterialTheme(colorScheme = base, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalBaseColorScheme provides null, content = content)
    }
}

// ------------------------------------------------------------------------------------------------
// Sheets and dialogs (G2): they keep their window types; in glass mode their container becomes a
// heavy NexHome GlassPanel sampling the window-aligned baked ambient, blooming its lens in.
// ------------------------------------------------------------------------------------------------

/** NexHome's sheet shape (the sheet header radius, 32). */
private val GlassSheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

/** Dialog panel radius (NexHome `GlassSection`, 28). */
private val GlassDialogShape = RoundedCornerShape(28.dp)

/** Remembers the lens-bloom enter (0 → 1 with NexHome's [LiquidMotion.EnterSpring], overshoot kept). */
@Composable
private fun rememberGlassEnter(): Animatable<Float, *> {
    val enter = remember { Animatable(0f, 0.001f) }
    LaunchedEffect(enter) { enter.animateTo(1f, LiquidMotion.EnterSpring) }
    return enter
}

/**
 * The heavy glass container shared by sheets and dialogs: a background [GlassPanel] (heavy, the
 * palette's strong tint, lens [refraction] / 2×[refraction] with depth, gravity rim) sized to the
 * content, which sits on top of it with [GlassContentTheme]. The content is not a child of the glass
 * node, so its scrolling or animating never re-records the lens. Behaves like `Surface` for layout
 * (min constraints reach the content).
 */
@Composable
private fun GlassContainer(
    shape: Shape,
    refraction: Dp,
    bloom: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val lensShape = remember(shape) { shape.lensCompatible() }
    Box(modifier, propagateMinConstraints = true) {
        GlassPanel(
            modifier = Modifier.matchParentSize(),
            shape = lensShape,
            tint = palette.tintStrong,
            heavy = true,
            refractionHeight = refraction,
            refractionAmount = refraction * 2f,
            enterProgress = bloom,
        )
        Box(Modifier.clip(lensShape), propagateMinConstraints = true) {
            CompositionLocalProvider(LocalLensBloom provides bloom) {
                GlassContentTheme(content)
            }
        }
    }
}

/** The lens accepts Kyant shapes and corner-based shapes only. */
private fun Shape.lensCompatible(): Shape = when (this) {
    is CornerBasedShape, is RoundedRectangle, is Capsule -> this
    RectangleShape -> RoundedCornerShape(0.dp)
    else -> GlassDialogShape
}

/**
 * [ModalBottomSheet] that becomes a glass sheet in Liquid Glass mode. Material 3 mode passes every
 * argument straight through (a null [sheetState] / [contentWindowInsets] means Material's own
 * default).
 *
 * Glass mode keeps the sheet window, its drag, snapping and dismissal; the container is a heavy
 * glass panel with NexHome's sheet radius (32) and header lens (24/48, depth), sampling the baked
 * ambient aligned to the window, blooming in with the enter spring. The caller's container colour,
 * tonal elevation and shape are replaced; its drag handle is drawn inside the glass; the default
 * scrim becomes the palette's sheet scrim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState? = null,
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: (@Composable () -> WindowInsets)? = null,
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = sheetState ?: rememberModalBottomSheetState()
    if (!LocalGlassModeEnabled.current) {
        if (contentWindowInsets != null) {
            ModalBottomSheet(
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                sheetState = state,
                sheetMaxWidth = sheetMaxWidth,
                sheetGesturesEnabled = sheetGesturesEnabled,
                shape = shape,
                containerColor = containerColor,
                contentColor = contentColor,
                tonalElevation = tonalElevation,
                scrimColor = scrimColor,
                dragHandle = dragHandle,
                contentWindowInsets = contentWindowInsets,
                properties = properties,
                content = content,
            )
        } else {
            ModalBottomSheet(
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                sheetState = state,
                sheetMaxWidth = sheetMaxWidth,
                sheetGesturesEnabled = sheetGesturesEnabled,
                shape = shape,
                containerColor = containerColor,
                contentColor = contentColor,
                tonalElevation = tonalElevation,
                scrimColor = scrimColor,
                dragHandle = dragHandle,
                properties = properties,
                content = content,
            )
        }
        return
    }

    val palette = LocalGlassPalette.current
    val defaultScrim = BottomSheetDefaults.ScrimColor
    val insets: @Composable () -> WindowInsets = contentWindowInsets ?: { BottomSheetDefaults.modalWindowInsets }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = state,
        sheetMaxWidth = sheetMaxWidth,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = GlassSheetShape,
        containerColor = Color.Transparent,
        contentColor = palette.primary,
        tonalElevation = 0.dp,
        scrimColor = if (scrimColor == defaultScrim) palette.scrim else scrimColor,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = properties,
    ) {
        ProvideGlassWindowBackdrop {
            val enter = rememberGlassEnter()
            val bloom = remember(enter) { { enter.value } }
            GlassContainer(
                shape = GlassSheetShape,
                refraction = 24.dp,
                bloom = bloom,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(insets())
                ) {
                    if (dragHandle != null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { dragHandle() }
                    }
                    content()
                }
            }
        }
    }
}

/**
 * [AlertDialog] that becomes a glass dialog in Liquid Glass mode. Material 3 mode passes every
 * argument straight through.
 *
 * Glass mode keeps the dialog window and its dismissal. The container is a heavy glass panel with
 * NexHome's section values (radius 28, lens 20/40 with depth, gravity rim) sampling the
 * window-aligned ambient; it rises 96 dp into place with the enter spring while its lens blooms.
 * The layout is Material's (24 dp padding, icon, title, text, end-aligned buttons); the buttons keep
 * the caller's composables and pick up the accent through the glass content colours.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdaptiveAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    if (!LocalGlassModeEnabled.current) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = tonalElevation,
            properties = properties,
        )
        return
    }
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
        GlassDialogPanel(shape = GlassDialogShape) {
            val colors = MaterialTheme.colorScheme
            val typography = MaterialTheme.typography
            Column(Modifier.padding(24.dp)) {
                if (icon != null) {
                    CompositionLocalProvider(LocalContentColor provides colors.secondary) {
                        Box(
                            Modifier
                                .padding(bottom = 16.dp)
                                .align(Alignment.CenterHorizontally)
                        ) { icon() }
                    }
                }
                if (title != null) {
                    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
                        ProvideTextStyle(GlassType.Title) {
                            Box(
                                Modifier
                                    .padding(bottom = 16.dp)
                                    .align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally)
                            ) { title() }
                        }
                    }
                }
                if (text != null) {
                    CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                        ProvideTextStyle(GlassType.Body) {
                            Box(
                                Modifier
                                    .weight(1f, fill = false)
                                    .padding(bottom = 24.dp)
                                    .align(Alignment.Start)
                            ) { text() }
                        }
                    }
                }
                Box(Modifier.align(Alignment.End)) {
                    // NexHome's type and press feel for the caller's buttons: Material buttons set
                    // labelLarge themselves, so the theme's labelLarge becomes GlassType.Label; the
                    // ripple is off and each action swells and glows like a LiquidButton instead.
                    val buttonTypography = remember(typography) { typography.copy(labelLarge = GlassType.Label) }
                    MaterialTheme(colorScheme = colors, typography = buttonTypography, shapes = MaterialTheme.shapes) {
                        CompositionLocalProvider(
                            LocalContentColor provides colors.primary,
                            LocalRippleConfiguration provides null,
                        ) {
                            ProvideTextStyle(GlassType.Label) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (dismissButton != null) GlassDialogAction(content = dismissButton)
                                    GlassDialogAction(content = confirmButton)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One glass dialog action: the caller's button, unchanged, with NexHome's LiquidButton press — it
 * swells to [LiquidMotion.ButtonPressScale] with the press spring and glows dim white, clipped to a
 * capsule. Presses are observed on the initial pass without consuming anything, so the button's own
 * click handling is untouched.
 */
@Composable
private fun GlassDialogAction(content: @Composable () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val glow = remember { GlassPressIndication(Color.White, swellScale = 1f) }
    Box(
        Modifier
            .glassPressSwell(source, LiquidMotion.ButtonPressScale)
            .clip(GlassDialogActionShape)
            .indication(source, glow)
            .pointerInput(source) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val press = androidx.compose.foundation.interaction.PressInteraction.Press(down.position)
                    source.tryEmit(press)
                    var released = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.none { it.pressed }) {
                            released = true
                            break
                        }
                        if (event.changes.any { it.isConsumed && it.pressed && it.positionChanged() }) break
                    }
                    source.tryEmit(
                        if (released) androidx.compose.foundation.interaction.PressInteraction.Release(press)
                        else androidx.compose.foundation.interaction.PressInteraction.Cancel(press)
                    )
                }
            },
        propagateMinConstraints = true,
    ) {
        content()
    }
}

private val GlassDialogActionShape = Capsule()

/**
 * The glass dialog container: window-aligned ambient, the enter (rise 96 dp → 0 and fade, lens
 * bloom) and [GlassContainer] with NexHome's section lens (20/40, depth).
 */
@Composable
private fun GlassDialogPanel(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ProvideGlassWindowBackdrop {
        val enter = rememberGlassEnter()
        val bloom = remember(enter) { { enter.value } }
        GlassContainer(
            shape = shape,
            refraction = 20.dp,
            bloom = bloom,
            modifier = modifier.graphicsLayer {
                val e = enter.value
                translationY = (1f - e) * 96.dp.toPx()
                alpha = e.fastCoerceIn(0f, 1f)
            },
            content = content,
        )
    }
}

/**
 * The container `Surface` of a card-style dialog (`Dialog` / `BasicAlertDialog` content). Material 3
 * mode is exactly `Surface(modifier, shape, color, contentColor, tonalElevation, shadowElevation,
 * border)`; glass mode is the glass dialog panel in the same [shape] (made lens-compatible), with
 * the enter and the glass content colours.
 */
@Composable
fun AdaptiveDialogSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    if (!LocalGlassModeEnabled.current) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            border = border,
            content = content,
        )
        return
    }
    GlassDialogPanel(shape = shape, modifier = modifier, content = content)
}

/**
 * A full-screen [Dialog] (editors, pickers, progress screens). Material 3 mode is exactly
 * `Dialog(onDismissRequest, properties, content)`. Glass mode keeps the window and draws NexHome's
 * sheet scrim — the window-aligned ambient with the palette's colour controls and scrim tint,
 * fading in with the content — under the content, which gets the glass content colours, so its own
 * page and surface colours turn clear and its tiles flat.
 */
@Composable
fun AdaptiveFullScreenDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    if (!LocalGlassModeEnabled.current) {
        Dialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
        return
    }
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        ProvideGlassWindowBackdrop {
            val fade = remember { Animatable(0f) }
            LaunchedEffect(fade) { fade.animateTo(1f, tween(220)) }
            Box(Modifier.fillMaxSize()) {
                GlassSheetScrim(alpha = { fade.value }, modifier = Modifier.fillMaxSize())
                GlassContentTheme(content)
            }
        }
    }
}

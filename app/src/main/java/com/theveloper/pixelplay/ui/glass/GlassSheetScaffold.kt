package com.theveloper.pixelplay.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.vibrancy
import com.theveloper.pixelplay.ui.glass.components.GlassIcon
import com.theveloper.pixelplay.ui.glass.components.LiquidButton
import com.theveloper.pixelplay.ui.glass.controls.LocalLensBloom
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.utils.ProgressConverter
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The full-screen in-window glass sheet (the queue, player-internal sheets), generalised from
 * NexHome's `DeviceSheetScaffold` with its values and mechanics:
 *
 * - Scrim: the ambient layer ([LocalAppBackdrop]) saturated and dimmed with NexHome's colour controls
 *   and scrim tint (palette values in light mode). Touches never leak behind it.
 * - Enter: a "lens bloom" — the content rises 96 dp and fades in with [LiquidMotion.EnterSpring],
 *   and [LocalLensBloom] grows every section's refraction with it.
 * - Body: [content] in a vertically scrolling column (spacing 16, sides 20, status bar + 64 top,
 *   nav bar + 48 bottom). Content must not add its own vertical scroll or `fillMaxSize`.
 * - Swipe down when scrolled to the top: the sheet follows with rubber banding, dismisses past
 *   120 dp or a 1800 px/s fling (spring 1/320), otherwise springs back (0.55/300).
 * - Close: a LiquidButton at status bar + 72 dp, end 20 dp. System back dismisses too.
 *
 * PixlAudio changes (orchestrator decision G2, NexHome defects): the scrim no longer animates a
 * 20 dp blur every frame of enter and drag (it re-rendered a full-screen blur, plus every sheet
 * surface reading the scrim's export, each frame) — the ambient it samples is already pre-blurred,
 * so it keeps constant colour controls and fades with its layer alpha instead; the scrim is not
 * exported, so sheet glass samples the ambient directly and nothing re-renders in a chain during
 * enter; finger drags update a plain float state instead of launching a `snapTo` per scroll event.
 */
@Composable
fun GlassSheetScaffold(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showCloseButton: Boolean = true,
    handleBack: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentOnClose by rememberUpdatedState(onClose)

    val enter = remember { Animatable(0f, 0.001f) }
    // Visual sheet offset (px) = finger part (plain state, no coroutine per event) + settle spring.
    var fingerDrag by remember { mutableFloatStateOf(0f) }
    val settle = remember { Animatable(0f, 0.5f) }
    val rawDrag = remember { floatArrayOf(0f) } // finger distance before rubber banding
    var dismissing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { enter.animateTo(1f, LiquidMotion.EnterSpring) }
    // Remembered: LocalLensBloom is static, so a fresh lambda would recompose the whole sheet.
    val bloom = remember(enter) { { enter.value } }

    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Swallow every touch that lands on the sheet so nothing reaches the screen behind.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val rubberRangePx = heightPx * 0.5f
        val dismissThresholdPx = with(density) { 120.dp.toPx() }

        val sheetOffset: () -> Float = { fingerDrag + settle.value }
        val sheetFade: () -> Float = {
            (1f - sheetOffset() / (heightPx * 0.7f)).fastCoerceIn(0f, 1f) * enter.value.fastCoerceIn(0f, 1f)
        }

        val dismiss: (Float) -> Unit = { velocity ->
            if (!dismissing) {
                dismissing = true
                scope.launch {
                    settle.snapTo(sheetOffset())
                    fingerDrag = 0f
                    settle.animateTo(
                        heightPx,
                        spring(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = 1f),
                        initialVelocity = velocity.coerceAtLeast(0f),
                    )
                    currentOnClose()
                }
            }
        }

        if (handleBack) {
            BackHandler(enabled = !dismissing) { dismiss(0f) }
        }

        val connection = remember(heightPx, dismissThresholdPx) {
            fun rubber(raw: Float): Float = rubberRangePx * ProgressConverter.Default.convert(raw / rubberRangePx)

            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (dismissing) return Offset(0f, available.y)
                    if (source != NestedScrollSource.UserInput || rawDrag[0] <= 0f || available.y >= 0f) {
                        return Offset.Zero
                    }
                    val used = max(available.y, -rawDrag[0])
                    rawDrag[0] += used
                    fingerDrag = rubber(rawDrag[0])
                    return Offset(0f, used)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (dismissing || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                    rawDrag[0] += available.y
                    fingerDrag = rubber(rawDrag[0])
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (rawDrag[0] <= 0f) return Velocity.Zero
                    rawDrag[0] = 0f
                    if (sheetOffset() > dismissThresholdPx || available.y > 1800f) {
                        dismiss(available.y)
                    } else {
                        scope.launch {
                            settle.snapTo(sheetOffset())
                            fingerDrag = 0f
                            settle.animateTo(
                                0f,
                                spring(dampingRatio = 0.55f, stiffness = 300f, visibilityThreshold = 0.5f),
                                initialVelocity = available.y,
                            )
                        }
                    }
                    return available
                }
            }
        }

        // Dimmed, saturated ambient scrim with constant values; only its layer alpha animates.
        GlassSheetScrim(alpha = sheetFade, modifier = Modifier.fillMaxSize())

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val e = enter.value
                    translationY = sheetOffset() + (1f - e) * 96.dp.toPx()
                    alpha = e.fastCoerceIn(0f, 1f)
                },
        ) {
            CompositionLocalProvider(LocalLensBloom provides bloom) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(connection)
                        .verticalScroll(scrollState)
                        .statusBarsPadding()
                        .padding(top = 64.dp)
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )

                if (showCloseButton) {
                    LiquidButton(
                        onClick = { dismiss(0f) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(top = 72.dp, end = 20.dp),
                        surfaceColor = palette.tintSubtle,
                    ) {
                        GlassIcon(Icons.Rounded.Close, size = 22.dp)
                    }
                }
            }
        }
    }
}

/**
 * NexHome's sheet scrim (DeviceSheetScaffold, research-nexhome-design §10.3): the BASE ambient
 * layer ([LocalAppBackdrop]) with `vibrancy()` and the palette's constant colour controls, under the
 * palette's scrim fill. It never animates a blur; only its layer [alpha] (read in the layer) fades.
 * At full alpha it is opaque and hides everything behind it. One plain backdrop node.
 */
@Composable
fun GlassSheetScrim(alpha: () -> Float, modifier: Modifier = Modifier) {
    val appBackdrop = LocalAppBackdrop.current
    val palette = LocalGlassPalette.current
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha().fastCoerceIn(0f, 1f) }
            .drawPlainBackdrop(
                backdrop = appBackdrop,
                shape = { RectangleShape },
                effects = {
                    vibrancy()
                    colorControls(brightness = palette.scrimBrightness, saturation = palette.scrimSaturation)
                },
                onDrawSurface = { drawRect(palette.scrim) },
            )
    )
}

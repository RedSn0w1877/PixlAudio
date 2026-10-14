package com.theveloper.pixelplay.presentation.components

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.currentBackStackEntryAsState
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.navigation.isMainRootRoute
import com.theveloper.pixelplay.ui.theme.QuantizedCornerShapeCache
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.roundToInt


@OptIn(UnstableApi::class)
@Composable
fun ScreenWrapper(
    navController: androidx.navigation.NavController,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    content: @Composable () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Lifecycle State
    val initialCurrentState = lifecycleOwner.lifecycle.currentStateAsState().value
    var isResumed by remember { mutableStateOf(initialCurrentState.isAtLeast(Lifecycle.State.RESUMED)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isResumed = true
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                isResumed = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Collect states to subscribe Compose to their updates. Every event that changes the back
    // stack (navigate / pop commit) emits currentBackStackEntry, so reading these here is what
    // triggers recomposition; the synchronous navController properties below are then re-read
    // with frame-perfect values.
    val syncVisibleEntries by navController.visibleEntries.collectAsState()
    val currentBackStackEntryState by navController.currentBackStackEntryAsState()

    val myEntry = lifecycleOwner as? androidx.navigation.NavBackStackEntry
    val myRoute = myEntry?.destination?.route
    val isMainRootScreen = isMainRootRoute(myRoute)
    val hasVisibleNonMainRootScreen = syncVisibleEntries.any { entry ->
        entry.destination.route?.let { route -> !isMainRootRoute(route) } == true
    }
    val shouldRunDepthEffects = !isMainRootScreen || hasVisibleNonMainRootScreen

    // Dim/Blur Logic: the screen "behind" during any transition — forward push, committed pop,
    // or an in-progress predictive back gesture — is always the entry directly below the top of
    // the back stack, i.e. previousBackStackEntry. The incoming screen of a committed pop is
    // currentBackStackEntry (never previous), so it stays clear, and the exiting screen of a pop
    // is no longer in the back stack at all, so it stays clear too.
    //
    // We deliberately do NOT detect "behind" through visibleEntries: when a predictive back
    // gesture starts, prepareForTransition() raises the previous entry to STARTED without
    // re-emitting the visibleEntries StateFlow (NavHost composes the previous entry directly,
    // bypassing visibleEntries — see the comment inside NavHost's AnimatedContent). On a clean
    // gesture the behind screen therefore never appeared in visibleEntries, but after a
    // CANCELLED gesture the entry lingered in the transition set and the next flow emission
    // included it — which made the blur/dim work only on every other back gesture.
    //
    // previousBackStackEntry is a plain synchronous property; the currentBackStackEntryState
    // reference makes Compose re-read it on every navigate/pop commit (both change together).
    val previousEntryId = navController.previousBackStackEntry?.id.also { _ -> currentBackStackEntryState }
    val shouldDim = myEntry != null && previousEntryId == myEntry.id

    val disableBlurAllOver by playerViewModel.disableBlurAllOver.collectAsState()

    val transition = animatedVisibilityScope?.transition

    // Declarative Animations. Each one is kept as a State and read only inside the
    // graphicsLayer blocks below (draw phase): reading them here, in composition, recomposed
    // this wrapper — for both the entering and the exiting screen — on every frame of every
    // navigation, and rebuilt its modifier chain and blur effect each time.
    val targetRadius = if (shouldRunDepthEffects && !isResumed) 32f else 0f
    val cornerRadiusState: State<Float> = if (transition != null) {
        transition.animateFloat(
            transitionSpec = { tween(durationMillis = 350, easing = FastOutSlowInEasing) },
            label = "cornerRadius"
        ) { state ->
            if (shouldRunDepthEffects && (state == EnterExitState.PostExit || state == EnterExitState.PreEnter)) {
                32f
            } else {
                0f
            }
        }
    } else {
        val fallbackCornerRadius = remember { Animatable(targetRadius) }
        LaunchedEffect(targetRadius) {
            fallbackCornerRadius.animateTo(targetRadius, animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing))
        }
        fallbackCornerRadius.asState()
    }

    // Dim: If strictly behind Top -> 0.4f (or 0.75f if blur is disabled). Else -> 0f.
    val targetDim = if (shouldRunDepthEffects && shouldDim) {
        if (disableBlurAllOver) 0.75f else 0.4f
    } else {
        0f
    }
    val dimAlphaState: State<Float> = if (transition != null) {
        transition.animateFloat(
            transitionSpec = { tween(durationMillis = 350, easing = CubicBezierEasing(0.5f, 0f, 0.8f, 0.2f)) },
            label = "dimAlpha"
        ) { state ->
            if (shouldRunDepthEffects && shouldDim && (state == EnterExitState.PostExit || state == EnterExitState.PreEnter)) {
                if (disableBlurAllOver) 0.75f else 0.4f
            } else {
                0f
            }
        }
    } else {
        val fallbackDimAlpha = remember { Animatable(targetDim) }
        LaunchedEffect(targetDim) {
            fallbackDimAlpha.animateTo(targetDim, animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing))
        }
        fallbackDimAlpha.asState()
    }

    // Blur: If strictly behind Top -> 24dp. Else -> 0dp. Disabled if disableBlurAllOver is true.
    // Animated as a Float in dp (same tween and values as the former animateDp).
    val targetBlur = if (shouldRunDepthEffects && shouldDim && !disableBlurAllOver) 24f else 0f
    val blurRadiusDpState: State<Float> = if (transition != null) {
        transition.animateFloat(
            transitionSpec = { tween(durationMillis = 350, easing = CubicBezierEasing(0.5f, 0f, 0.8f, 0.2f)) },
            label = "blurRadius"
        ) { state ->
            if (shouldRunDepthEffects && shouldDim && !disableBlurAllOver && (state == EnterExitState.PostExit || state == EnterExitState.PreEnter)) {
                24f
            } else {
                0f
            }
        }
    } else {
        val fallbackBlurRadius = remember { Animatable(targetBlur) }
        LaunchedEffect(targetBlur) {
            fallbackBlurRadius.animateTo(targetBlur, animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing))
        }
        fallbackBlurRadius.asState()
    }

    val cornerShapes = remember { QuantizedCornerShapeCache() }
    val blurEffects = remember { DepthBlurEffectCache() }

    Box(
        modifier = modifier
            .fillMaxSize()
            // The compositing strategy stays constant for the screen's whole life, so the
            // RenderNode's rendering mode never flips mid-transition (that flip caused a one-frame
            // flash on the outgoing screen). It is Auto rather than Offscreen: nothing in this
            // layer needs an isolated buffer — the rounded corners are an outline clip, the blur
            // sits on its own layer below and the dim is a child — and an Offscreen layer drew
            // every detail screen (album, artist, playlist, settings…) through a full-screen
            // offscreen buffer on every frame, scrolling included.
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Auto
                val cornerRadius = cornerRadiusState.value
                if (shouldRunDepthEffects && cornerRadius > 0.5f) {
                    this.shape = cornerShapes.get(cornerRadius.dp)
                    this.clip = true
                } else {
                    this.clip = false
                }
            }
            // Same result as Modifier.blur(radius, BlurredEdgeTreatment.Rectangle) — clamp tile
            // mode, rectangle clip — but the effect object is cached per 0.5 px step instead of
            // being rebuilt on every frame.
            .graphicsLayer {
                val blurRadiusPx = if (shouldRunDepthEffects) blurRadiusDpState.value.dp.toPx() else 0f
                renderEffect = blurEffects.get(blurRadiusPx)
                this.shape = RectangleShape
                this.clip = true
            }
            .background(MaterialTheme.colorScheme.background)
    ) {
        content()

        // Dim Layer Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dimAlphaState.value }
                .background(Color.Black)
        )
    }
}

/**
 * The navigation depth blur, reused across frames: the radius is rounded to 0.5 px steps and
 * the RenderEffect is rebuilt only when the step changes.
 */
private class DepthBlurEffectCache {
    private var lastStep = 0
    private var cached: RenderEffect? = null

    fun get(radiusPx: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val step = if (radiusPx.isFinite()) (radiusPx * 2f).roundToInt() else 0
        if (step <= 0) {
            lastStep = 0
            cached = null
            return null
        }
        if (step != lastStep || cached == null) {
            lastStep = step
            val radius = step / 2f
            cached = AndroidRenderEffect
                .createBlurEffect(radius, radius, AndroidShader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
        return cached
    }
}

package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/**
 * A screen laid out in the glass layer model: the body underneath, chrome floating over it and
 * refracting it. A drop-in replacement for Material 3's [Scaffold].
 *
 * - **Material 3 mode:** this *is* `Scaffold(...)` with exactly the arguments given, so a screen
 *   migrated to it renders pixel-for-pixel as before.
 * - **Glass mode:** still the same `Scaffold` layout (insets, snackbar, FAB, padding maths all
 *   unchanged), but the body is recorded into the screen's own backdrop ([RecordedContent]) and
 *   the top and bottom bars become chrome: [LocalGlassLayer] = [GlassLayer.Chrome] and
 *   [LocalAppBackdrop] = that recording. Glass components in the bars refract the body; put the
 *   trailing actions in one [GlassGroup] rather than several separate glass buttons. With
 *   [scrollEdge] the one [ScrollEdgeEffect] per screen is drawn under the top bar, so content
 *   scrolling up beneath it frosts out instead of colliding with the title. Top bars should drop
 *   their opaque container colour in glass mode or they will simply cover the effect.
 *
 * Nothing inside the body may sample the body's own recording — the debug crash guard in
 * [liquidGlass] enforces it. Chrome goes in the chrome slots.
 *
 * @param scrollEdge draw the [ScrollEdgeEffect] under the top bar (glass mode only). Leave it off
 *   for screens whose content never scrolls beneath the bar.
 * @param scrollEdgeHeight the frosted strip's height; defaults to the status bar plus 64dp.
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topChrome: @Composable () -> Unit = {},
    bottomChrome: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    scrollEdge: Boolean = true,
    scrollEdgeHeight: Dp = Dp.Unspecified,
    content: @Composable (PaddingValues) -> Unit
) {
    if (!isGlassEnabled) {
        Scaffold(
            modifier = modifier,
            topBar = topChrome,
            bottomBar = bottomChrome,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            floatingActionButtonPosition = floatingActionButtonPosition,
            containerColor = containerColor,
            contentColor = contentColor,
            contentWindowInsets = contentWindowInsets,
            content = content
        )
        return
    }

    val screenBackdrop = rememberPageBackdrop()
    Scaffold(
        modifier = modifier,
        topBar = {
            GlassChromeScope(screenBackdrop) {
                TopChromeWithScrollEdge(
                    backdrop = screenBackdrop,
                    scrollEdge = scrollEdge,
                    scrollEdgeHeight = scrollEdgeHeight,
                    bar = topChrome
                )
            }
        },
        bottomBar = { GlassChromeScope(screenBackdrop, bottomChrome) },
        snackbarHost = snackbarHost,
        floatingActionButton = { GlassChromeScope(screenBackdrop, floatingActionButton) },
        floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = contentWindowInsets
    ) { padding ->
        // Transparent where the body draws nothing: glass over such an area shows whatever is
        // really beneath it (the scaffold colour, a header band) instead of a flat fill.
        RecordedContent(backdrop = screenBackdrop, modifier = Modifier.fillMaxSize()) {
            content(padding)
        }
    }
}

/**
 * The same layer model for screens that stack their chrome over a list in a plain `Box` instead
 * of a [Scaffold] (the collapsing-header screens). Three pieces, all no-ops in Material 3 mode so
 * the existing `Box { list; topBar }` tree is left exactly as it was (and nothing is allocated):
 *
 * 1. [rememberGlassScreenBackdrop] — the screen's own recording; null in Material 3 mode.
 * 2. [glassScreenContent] on the scrolling content records it into that backdrop.
 * 3. [GlassChrome] around the top bar makes it chrome refracting the recording, and puts the
 *    [ScrollEdgeEffect] beneath it.
 *
 * ```
 * val screenGlass = rememberGlassScreenBackdrop()
 * Box {
 *     LazyColumn(Modifier.fillMaxSize().glassScreenContent(screenGlass)) { … }
 *     GlassChrome(screenGlass) { CollapsibleCommonTopBar(…) }
 * }
 * ```
 *
 * Only put [glassScreenContent] on content that nothing inside samples — the content layer's
 * glass components are tonal (MainActivity gives everything under AppNavigation an empty
 * backdrop), so in practice that is any list or column of screen content.
 */
@Composable
fun rememberGlassScreenBackdrop(): PageBackdrop? =
    if (isGlassEnabled) rememberPageBackdrop() else null

/**
 * Records everything in [content] into [backdrop] for [GlassChrome] — for screens whose header
 * (artwork, title) should be refracted along with the list, so the chrome buttons have to float
 * above both. The layer matches the parent `Box`'s size. With a null [backdrop] (Material 3 mode)
 * [content] runs directly in the caller's `Box`, leaving its tree exactly as it was.
 */
@Composable
fun BoxScope.GlassScreenLayer(
    backdrop: PageBackdrop?,
    content: @Composable BoxScope.() -> Unit
) {
    if (backdrop == null) {
        content()
        return
    }
    val recording = LocalRecordingBackdrops.current
    Box(Modifier.matchParentSize().pageBackdrop(backdrop)) {
        // Arms the crash guard: nothing in here may sample the recording it is part of.
        CompositionLocalProvider(LocalRecordingBackdrops provides recording + backdrop) {
            content()
        }
    }
}

/** Records this content into [backdrop] for [GlassChrome]. No-op when [backdrop] is null. */
fun Modifier.glassScreenContent(backdrop: PageBackdrop?): Modifier =
    if (backdrop != null) this.pageBackdrop(backdrop) else this

/**
 * Makes [content] chrome over the recording made by [glassScreenContent]: glass components in it
 * refract [backdrop]. With [scrollEdge], a [ScrollEdgeEffect] is emitted first, as a sibling
 * beneath the chrome. With a null [backdrop] (Material 3 mode) this just calls [content].
 */
@Composable
fun GlassChrome(
    backdrop: PageBackdrop?,
    scrollEdge: Boolean = true,
    scrollEdgeHeight: Dp = Dp.Unspecified,
    content: @Composable () -> Unit
) {
    if (backdrop == null || !isGlassEnabled) {
        content()
        return
    }
    if (scrollEdge) ScrollEdgeEffect(backdrop = backdrop, height = scrollEdgeHeight.orDefaultEdge())
    GlassChromeScope(backdrop, content)
}

/** Provides the chrome locals for [backdrop] around [content]. Glass mode only. */
@Composable
private fun GlassChromeScope(backdrop: PageBackdrop, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalGlassLayer provides GlassLayer.Chrome,
        LocalAppBackdrop provides backdrop,
        content = content
    )
}

/**
 * The top bar with the scroll edge beneath it. The edge is drawn but does not count towards the
 * slot's size, so the scaffold's content padding stays the bar's own height.
 */
@Composable
private fun TopChromeWithScrollEdge(
    backdrop: PageBackdrop,
    scrollEdge: Boolean,
    scrollEdgeHeight: Dp,
    bar: @Composable () -> Unit
) {
    if (!scrollEdge) {
        bar()
        return
    }
    val edgeHeight = scrollEdgeHeight.orDefaultEdge()
    Layout(
        contents = listOf(
            { ScrollEdgeEffect(backdrop = backdrop, height = edgeHeight) },
            bar
        )
    ) { (edgeMeasurables, barMeasurables), constraints ->
        val bars = barMeasurables.map { it.measure(constraints) }
        val width = bars.maxOfOrNull { it.width } ?: 0
        val height = bars.maxOfOrNull { it.height } ?: 0
        val edgeWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else width
        val edges = edgeMeasurables.map { it.measure(Constraints(maxWidth = edgeWidth)) }
        layout(width, height) {
            edges.forEach { it.place(0, 0) }
            bars.forEach { it.place(0, 0) }
        }
    }
}

@Composable
private fun Dp.orDefaultEdge(): Dp =
    if (isSpecified) this
    else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp

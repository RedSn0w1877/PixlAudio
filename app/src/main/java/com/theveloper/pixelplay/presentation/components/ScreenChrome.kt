package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.ui.glass.GlassCircleAction
import com.theveloper.pixelplay.ui.glass.GlassTopBar
import com.theveloper.pixelplay.ui.glass.GlassTopBarTitle
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

/**
 * Recomposition boundary for a screen's floating chrome (top bar, back button, actions).
 *
 * Deliberately a non-inline composable: state read inside [content] — the per-frame collapse
 * height or fraction of a collapsing header — recomposes only this lambda, not the whole screen.
 * Also the seam for Liquid Glass mode: the shared top bars ([CollapsibleCommonTopBar],
 * [HomeGradientTopBar]) draw [GlassScreenTopBar] in glass mode, so every screen built on them gets
 * NexHome's floating capsule without its own glass code.
 */
@Composable
fun ScreenChrome(content: @Composable () -> Unit) {
    content()
}

/**
 * The same boundary for the scrolling layer of collapsing-header screens (album and artist
 * detail).
 */
@Composable
fun BoxScope.ScreenLayer(content: @Composable BoxScope.() -> Unit) {
    content()
}

/**
 * Glass mode's screen top bar: NexHome's floating capsule ([GlassTopBar], tint Black@0.22 / the
 * light top-bar tint, no glint) with an optional 40 dp back orb, the title (Title) and subtitle
 * (Caption, Secondary), and the screen's own [actions] at the end. [supportingContent], if any,
 * sits under the capsule. The bar floats at a constant size; there is no collapse in glass mode.
 */
@Composable
fun GlassScreenTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBackClick: (() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier) {
        GlassTopBar(
            leading = onBackClick?.let { onBack ->
                {
                    GlassCircleAction(
                        onClick = onBack,
                        contentDescription = stringResource(R.string.common_back),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            trailing = actions,
        ) {
            GlassTopBarTitle(title = title, subtitle = subtitle)
        }
        supportingContent?.invoke()
    }
}

/**
 * Glass mode's replacement for a header's big play / shuffle FAB: a 64 dp NexHome orb (light
 * circular glass, lens 16/32, orb press swell, no glint) lit with the accent — the `MediaOrb`
 * "lit" fill at 0.38 — and emitting it while touched.
 */
@Composable
fun GlassHeaderActionOrb(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalGlassPalette.current
    GlassCircleAction(
        onClick = onClick,
        modifier = modifier,
        contentDescription = contentDescription,
        size = 64.dp,
        tint = palette.accent.copy(alpha = 0.38f),
        content = content,
    )
}

/**
 * Glass mode's bar for a collapsing-header screen: the header keeps its (collapsing) [headerHeight]
 * so the screen's layout and list padding are unchanged, NexHome's floating capsule
 * ([GlassScreenTopBar]) sits at its top at a constant size, and the header area fills with the
 * aligned baked ambient at [maskAlpha] ([glassAmbientFill]) — the same clear-glass page the rest of
 * the screen shows — so a collapsed header still masks the list scrolled under it, as the Material
 * header's solid fill does. A [maskAlpha] of 0 draws no fill.
 */
@Composable
fun GlassCollapsingTopBar(
    title: String,
    headerHeight: Dp,
    maskAlpha: Float,
    onBackClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(headerHeight)
            .zIndex(5f)
            .glassAmbientFill(maskAlpha)
    ) {
        GlassScreenTopBar(
            title = title,
            subtitle = subtitle,
            onBackClick = onBackClick,
            modifier = Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true),
            supportingContent = supportingContent,
            actions = actions
        )
    }
}

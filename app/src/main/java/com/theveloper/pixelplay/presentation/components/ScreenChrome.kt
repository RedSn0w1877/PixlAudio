package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.ui.glass.GlassCircleAction
import com.theveloper.pixelplay.ui.glass.GlassTopBar
import com.theveloper.pixelplay.ui.glass.GlassTopBarTitle

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

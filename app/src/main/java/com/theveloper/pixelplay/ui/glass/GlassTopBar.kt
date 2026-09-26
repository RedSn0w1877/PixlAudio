package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.theveloper.pixelplay.ui.glass.components.GlassPanel
import com.theveloper.pixelplay.ui.glass.components.GlassText
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

/**
 * NexHome's floating capsule top bar (`NexHomeApp.kt` `TopBar`): a light [GlassPanel] capsule tinted
 * with the palette's top-bar tint, with **no highlight glint** (the owner's rule for wide flat
 * surfaces), inset 16 dp at the sides and 8 dp top/bottom under the status bar. Inner padding is
 * NexHome's 22 / 8 / 8 / 8 dp; a [leading] slot (a back orb) tightens the start to 8 dp.
 *
 * It floats at a constant size: glass mode has no collapsing toolbar, content scrolls beneath it
 * and is hidden by it (the bar shows the refracted ambient, never the content).
 *
 * @param applyStatusBarPadding false when the caller already sits below the status bar.
 */
@Composable
fun GlassTopBar(
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    applyStatusBarPadding: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    GlassPanel(
        modifier = modifier
            .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
        shape = Capsule(),
        tint = LocalGlassPalette.current.topBar,
        showHighlight = false,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = if (leading != null) 8.dp else 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke(this)
            content()
            trailing()
        }
    }
}

/**
 * The top bar's title block (NexHome: Title, then a Caption status line in Secondary). Takes the
 * remaining width of the bar.
 */
@Composable
fun RowScope.GlassTopBarTitle(
    title: String,
    subtitle: String? = null,
) {
    val palette = LocalGlassPalette.current
    Column(Modifier.weight(1f)) {
        GlassText(title, style = GlassType.Title, maxLines = 1)
        if (!subtitle.isNullOrBlank()) {
            GlassText(subtitle, style = GlassType.Caption, color = palette.secondary, maxLines = 1)
        }
    }
}

/**
 * NexHome's round top-bar action (the 40 dp orb): a light circular [GlassPanel] with the subtle
 * tint, a shallow lens (16/32 → 8/16 for a light panel), [LiquidMotion.OrbPressScale] swell, no
 * glint, and the accent emitted as light while touched.
 */
@Composable
fun GlassCircleAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 40.dp,
    tint: Color = LocalGlassPalette.current.tintSubtle,
    accent: Color = LocalGlassPalette.current.accent,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassPanel(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        shape = CircleShape,
        tint = tint,
        accent = accent,
        onClick = onClick,
        showHighlight = false,
        refractionHeight = 16.dp,
        refractionAmount = 32.dp,
        pressScale = LiquidMotion.OrbPressScale,
    ) {
        Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center, content = content)
    }
}

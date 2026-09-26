package com.theveloper.pixelplay.ui.glass.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.components.GlassIcon
import com.theveloper.pixelplay.ui.glass.components.GlassPanel
import com.theveloper.pixelplay.ui.glass.components.GlassText
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

/**
 * A titled heavy glass panel (ported from NexHome) — radius 28, heavy lens 20/40 with depth and the
 * gravity rim, padding 16, spacing 14. The lens blooms in with [LocalLensBloom].
 *
 * @param title optional caption, shown UPPERCASE in the secondary colour; [icon] and [trailing] sit
 *   on the same row.
 * @param accent tints the title icon (the section itself is not an emitter).
 *
 * PixlAudio changes: palette colours; NexHome's section export (dead under FlattenNestedGlass) is
 * removed, so kit controls inside refract the base ambient layer directly.
 */
@Composable
fun GlassSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = Color.Unspecified,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    tint: Color = LocalGlassPalette.current.tintStrong,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bloom = LocalLensBloom.current
    val palette = LocalGlassPalette.current
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        backdrop = backdrop,
        shape = RoundedRectangle(28.dp),
        tint = tint,
        heavy = true,
        refractionHeight = 20.dp,
        refractionAmount = 40.dp,
        enterProgress = bloom,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            if (title != null || trailing != null || icon != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (icon != null) {
                        GlassIcon(icon, tint = if (accent.isSpecified) accent else palette.secondary, size = 18.dp)
                    }
                    if (title != null) {
                        GlassText(
                            title.uppercase(),
                            Modifier.weight(1f),
                            style = GlassType.Caption,
                            color = palette.secondary,
                            maxLines = 1,
                        )
                    } else {
                        Row(Modifier.weight(1f)) {}
                    }
                    trailing?.invoke(this)
                }
            }
            content()
        }
    }
}

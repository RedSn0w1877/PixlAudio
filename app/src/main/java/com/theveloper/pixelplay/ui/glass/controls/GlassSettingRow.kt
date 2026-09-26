package com.theveloper.pixelplay.ui.glass.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import com.theveloper.pixelplay.ui.glass.LocalGlassBackdrop
import com.theveloper.pixelplay.ui.glass.components.GlassIcon
import com.theveloper.pixelplay.ui.glass.components.GlassPanel
import com.theveloper.pixelplay.ui.glass.components.GlassText
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

/**
 * A settings row (ported from NexHome): optional icon orb, [title] + [subtitle], and a [trailing]
 * slot (usually a LiquidToggle or a value). Min height 60, padding 14/10, gap 12; title BodyStrong,
 * subtitle Caption in the secondary colour. With [onClick] the row swells and glides its highlight
 * like every kit surface (press scale 1.04 — a swell, never a shrink).
 *
 * @param flat PixlAudio's layer-budget variant (orchestrator decision G3): the same layout, press
 *   swell and dim glow, but no `drawBackdrop` of its own and a plain tinted icon disc instead of a
 *   glass orb. Use it for rows inside a [GlassSection]; a list of glass rows would put two lenses
 *   per row on screen.
 */
@Composable
fun GlassSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    accent: Color = LocalGlassPalette.current.accent,
    onClick: (() -> Unit)? = null,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    flat: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    if (flat) {
        FlatSettingRow(title, modifier, icon, subtitle, accent, onClick, trailing)
        return
    }
    val palette = LocalGlassPalette.current
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        backdrop = backdrop,
        shape = RoundedRectangle(20.dp),
        tint = palette.tintSubtle,
        accent = if (onClick != null) accent else Color.Unspecified,
        onClick = onClick,
        interactive = true,
        refractionHeight = 16.dp,
        refractionAmount = 32.dp,
        pressScale = 1.04f,
    ) {
        SettingRowContent(title, icon, subtitle, accent, orb = true, trailing = trailing)
    }
}

private val FlatRowShape = RoundedCornerShape(20.dp)

@Composable
private fun FlatSettingRow(
    title: String,
    modifier: Modifier,
    icon: ImageVector?,
    subtitle: String?,
    accent: Color,
    onClick: (() -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)?,
) {
    val highlight = rememberKitHighlight(accent)
    val contentColor = LocalGlassPalette.current.primary
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val scale = lerp(1f, 1.04f, highlight.swell)
                scaleX = scale
                scaleY = scale
            }
            .clip(FlatRowShape)
            .then(highlight.modifier)
            .then(highlight.gestureModifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            SettingRowContent(title, icon, subtitle, accent, orb = false, trailing = trailing)
        }
    }
}

@Composable
private fun SettingRowContent(
    title: String,
    icon: ImageVector?,
    subtitle: String?,
    accent: Color,
    orb: Boolean,
    trailing: (@Composable RowScope.() -> Unit)?,
) {
    val palette = LocalGlassPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            if (orb) {
                GlassIconOrb(icon, accent = accent, size = 36.dp, glow = { 0.35f })
            } else {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(palette.tintSubtle, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    GlassIcon(icon, tint = accent, size = 18.dp)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            GlassText(title, style = GlassType.BodyStrong, maxLines = 1)
            if (subtitle != null) {
                GlassText(subtitle, style = GlassType.Caption, color = palette.secondary, maxLines = 2)
            }
        }
        trailing?.invoke(this)
    }
}

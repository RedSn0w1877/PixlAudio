package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.theveloper.pixelplay.ui.glass.components.GlassPanel
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.GlassPalette
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

/** The capsule every glass pill uses (one instance: GlassPanel keys its glass chain on the shape). */
internal val GlassPillShape = Capsule()

/** The accent fill of a lit / prominent glass control (NexHome MediaOrb's "lit" 0.38). */
internal const val GlassLitAlpha = 0.38f

/**
 * A primary button on a floating bar as its own glass pill (Save as playlist, Edit song, the tab
 * order's Done, Quick Fill's Next): a light capsule [GlassPanel] with LiquidButton's values — lens
 * 12 / 24 (24 / 48 halved for a light panel), the button press swell, the jelly and gliding glow —
 * flooded with the accent at 0.38 when [prominent], the subtle tint otherwise ([glassPillTint]).
 *
 * Built on [GlassPanel], NOT [com.theveloper.pixelplay.ui.glass.components.LiquidButton]: the
 * LiquidButton rebuilds its drawBackdrop chain on every recomposition, and these pills sit on
 * screens that recompose on every keystroke or selection. GlassPanel builds it once per real input.
 * Pass a stable [onClick] (a remembered lambda reading `rememberUpdatedState`) so typing never
 * recomposes the pill at all.
 *
 * Kit rules: it reads [LocalGlassBackdrop] (the root ambient, or a sheet's window-aligned one) and
 * must never sit under a `layerBackdrop` it reads. Content gets the palette's primary colour and
 * [GlassType.BodyStrong]; a disabled pill fades its content to 0.38, drops the accent and takes no
 * touches, and still reads as a disabled button to TalkBack.
 *
 * Glass mode only; Material 3 mode keeps its own buttons.
 */
@Composable
fun GlassPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = true,
    tint: Color = Color.Unspecified,
    enterProgress: () -> Float = { 1f },
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalGlassPalette.current
    val resolvedTint = if (tint.isSpecified && enabled) tint else glassPillTint(palette, prominent, enabled)
    GlassPanel(
        // GlassPanel only adds a button role when it has a click, so a disabled pill states its
        // role and state itself.
        modifier = modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            if (!enabled) disabled()
        },
        shape = GlassPillShape,
        tint = resolvedTint,
        accent = if (prominent && enabled) palette.accent else Color.Unspecified,
        onClick = if (enabled) onClick else null,
        refractionHeight = 24.dp,
        refractionAmount = 48.dp,
        pressScale = LiquidMotion.ButtonPressScale,
        enterProgress = enterProgress,
    ) {
        ProvideTextStyle(GlassType.BodyStrong) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 20.dp)
                    .graphicsLayer { alpha = if (enabled) 1f else DisabledContentAlpha },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

private const val DisabledContentAlpha = 0.38f

/**
 * A glass pill's tint: the accent at [GlassLitAlpha] for an enabled prominent (primary) pill, the
 * palette's subtle tint for a secondary or disabled one.
 */
internal fun glassPillTint(palette: GlassPalette, prominent: Boolean, enabled: Boolean): Color =
    if (prominent && enabled) palette.accent.copy(alpha = GlassLitAlpha) else palette.tintSubtle

package com.theveloper.pixelplay.ui.glass.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

/**
 * Kit text (ported from NexHome): [BasicText] with the glass tokens. The colour goes through a
 * remembered [ColorProducer] instead of `style.copy(color = …)` on every recomposition (NexHome
 * defect fixed). Screens may use Material `Text` with `LocalContentColor` from the palette instead.
 */
@Composable
fun GlassText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = GlassType.Body,
    color: Color = LocalGlassPalette.current.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
) {
    val colorProducer = remember(color) { ColorProducer { color } }
    val resolvedStyle = remember(style, textAlign) {
        if (textAlign != null) style.copy(textAlign = textAlign) else style
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = resolvedStyle,
        maxLines = maxLines,
        overflow = overflow,
        color = colorProducer,
    )
}

/** Kit icon: an [ImageVector] painted with a tint, exactly how the library catalog draws icons. */
@Composable
fun GlassIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = LocalGlassPalette.current.primary,
    size: Dp = 24.dp,
) {
    val filter = remember(tint) { ColorFilter.tint(tint) }
    Box(
        modifier
            .size(size)
            .paint(rememberVectorPainter(icon), colorFilter = filter)
    )
}

/** [GlassIcon] for a drawable resource painter (the app's own vector icons). */
@Composable
fun GlassIcon(
    painter: Painter,
    modifier: Modifier = Modifier,
    tint: Color = LocalGlassPalette.current.primary,
    size: Dp = 24.dp,
) {
    val filter = remember(tint) { ColorFilter.tint(tint) }
    Box(
        modifier
            .size(size)
            .paint(painter, colorFilter = filter)
    )
}

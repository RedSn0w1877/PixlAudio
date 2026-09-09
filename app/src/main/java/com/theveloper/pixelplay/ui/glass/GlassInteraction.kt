package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape

/**
 * Click handling for glass components.
 *
 * The ripple is clipped to the component's own [shape] rather than left to spread over the panel
 * bounds: on a transparent surface an unclipped ripple visibly bleeds past the refracted edge,
 * which breaks the illusion that the glass is a solid object.
 */
@Composable
fun Modifier.glassClickable(
    onClick: () -> Unit,
    enabled: Boolean,
    shape: Shape,
    interactionSource: MutableInteractionSource? = null
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return this
        .clip(shape)
        .clickable(
            interactionSource = source,
            indication = ripple(),
            enabled = enabled,
            onClick = onClick
        )
}

package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.presentation.components.LocalMaterialTheme
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.theveloper.pixelplay.ui.theme.QuantizedCornerShapeCache

@Composable
fun ToggleSegmentButton(
    modifier: Modifier,
    active: Boolean,
    enabled: Boolean = true,
    activeColor: Color,
    inactiveColor: Color = Color.Gray,
    activeContentColor: Color = LocalMaterialTheme.current.onPrimary,
    inactiveContentColor: Color = LocalMaterialTheme.current.onSurfaceVariant,
    activeCornerRadius: Dp = 8.dp,
    onClick: () -> Unit,
    iconId: Int,
    contentDesc: String
) {
    ToggleSegmentButtonContainer(
        modifier = modifier,
        active = active,
        enabled = enabled,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        activeCornerRadius = activeCornerRadius,
        onClick = onClick
    ) {
        Icon(
            painter = painterResource(iconId),
            contentDescription = contentDesc,
            tint = if (active) activeContentColor else inactiveContentColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ToggleSegmentButton(
    modifier: Modifier,
    active: Boolean,
    enabled: Boolean = true,
    activeColor: Color,
    inactiveColor: Color = Color.Gray,
    activeContentColor: Color = LocalMaterialTheme.current.onPrimary,
    inactiveContentColor: Color = LocalMaterialTheme.current.onSurfaceVariant,
    activeCornerRadius: Dp = 8.dp,
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDesc: String
) {
    ToggleSegmentButtonContainer(
        modifier = modifier,
        active = active,
        enabled = enabled,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        activeCornerRadius = activeCornerRadius,
        onClick = onClick
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDesc,
            tint = if (active) activeContentColor else inactiveContentColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ToggleSegmentButton(
    modifier: Modifier,
    active: Boolean,
    enabled: Boolean = true,
    activeColor: Color,
    inactiveColor: Color = Color.Gray,
    activeContentColor: Color = LocalMaterialTheme.current.onPrimary,
    inactiveContentColor: Color = LocalMaterialTheme.current.primary,
    activeCornerRadius: Dp = 8.dp,
    onClick: () -> Unit,
    text: String,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Center
) {
    ToggleSegmentButtonContainer(
        modifier = modifier,
        active = active,
        enabled = enabled,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        activeCornerRadius = activeCornerRadius,
        onClick = onClick
    ) {
        Text(
            text = text,
            color = if (active) activeContentColor else inactiveContentColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = maxLines,
            overflow = overflow,
            textAlign = textAlign
        )
    }
}

@Composable
fun ToggleSegmentButton(
    modifier: Modifier,
    active: Boolean,
    enabled: Boolean = true,
    activeColor: Color,
    inactiveColor: Color = Color.Gray,
    activeContentColor: Color = LocalMaterialTheme.current.onPrimary,
    inactiveContentColor: Color = LocalMaterialTheme.current.onSurfaceVariant,
    activeCornerRadius: Dp = 8.dp,
    onClick: () -> Unit,
    text: String,
    imageVector: ImageVector,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Center,
    /** A long press (e.g. the lyrics toolbar's Translate menu); null keeps a plain tap target. */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    /** 0..1 fill behind the label (e.g. "Removing vocals 48 %"), read only while drawing. */
    progress: (() -> Float)? = null,
    /** Spoken state, so the label can stay fixed ("Translate") while the state changes. */
    stateDescription: String? = null,
    /** Looks unavailable but still answers taps (a tap can explain why it is unavailable). */
    dimmed: Boolean = false,
) {
    ToggleSegmentButtonContainer(
        modifier = modifier,
        active = active,
        enabled = enabled,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        activeCornerRadius = activeCornerRadius,
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        progress = progress,
        progressColor = activeColor,
        stateDescription = stateDescription,
        dimmed = dimmed,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = if (active) activeContentColor else inactiveContentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = if (active) activeContentColor else inactiveContentColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = maxLines,
                overflow = overflow,
                textAlign = textAlign
            )
        }
    }
}


@Composable
private fun ToggleSegmentButtonContainer(
    modifier: Modifier,
    active: Boolean,
    enabled: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    activeCornerRadius: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    progress: (() -> Float)? = null,
    progressColor: Color = activeColor,
    stateDescription: String? = null,
    dimmed: Boolean = false,
    content: @Composable () -> Unit
) {
    val looksEnabled = enabled && !dimmed
    val targetBgColor = if (active) activeColor else inactiveColor
    // Both animations are held as State and read only in the layer / draw blocks below, so a
    // tap's colour and corner morph redraws the segment without recomposing it every frame.
    val bgColor = animateColorAsState(
        // Halve the container's own alpha, so a translucent container never gets brighter.
        targetValue = if (looksEnabled) targetBgColor else targetBgColor.copy(alpha = targetBgColor.alpha * 0.5f),
        animationSpec = tween(durationMillis = 250),
        label = ""
    )
    val cornerRadius = animateDpAsState(
        targetValue = if (active) activeCornerRadius else 8.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = ""
    )
    val shapes = remember { QuantizedCornerShapeCache() }
    val currentProgress by rememberUpdatedState(progress)
    val progressFill = progressColor.copy(alpha = progressColor.alpha * 0.35f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                shape = shapes.get(cornerRadius.value)
                clip = true
            }
            .drawBehind {
                drawRect(bgColor.value)
                val fraction = currentProgress?.invoke()?.coerceIn(0f, 1f) ?: 0f
                if (fraction > 0f) drawRect(progressFill, size = Size(size.width * fraction, size.height))
            }
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        role = Role.Button,
                        onLongClickLabel = onLongClickLabel,
                        onLongClick = onLongClick,
                        onClick = onClick
                    )
                } else {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                }
            )
            .then(
                if (stateDescription != null) {
                    val spokenState: String = stateDescription
                    Modifier.semantics { this.stateDescription = spokenState }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer(alpha = if (looksEnabled) 1f else 0.38f)
                .padding(horizontal = 10.dp)
        ) {
            content()
        }
    }
}

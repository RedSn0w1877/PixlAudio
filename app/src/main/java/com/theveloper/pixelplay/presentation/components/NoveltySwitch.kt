@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.brain.Novelty

/**
 * Familiar / Blend / Discover (docs/brain-spec.md, 8.3): how much of what the engine picks is new
 * music. Three stops as a Material 3 Expressive connected group: the chosen stop is a full pill in the
 * primary colour, the others sit beside it with small inner corners. The colours move on the
 * expressive motion scheme's effects spec; the shapes are constants, so nothing is allocated per frame.
 */
@Composable
fun NoveltySwitch(
    level: Int,
    onLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.brain_novelty_cd)
    Row(
        modifier = modifier
            .selectableGroup()
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NoveltyStop(stringResource(R.string.brain_novelty_familiar), level == Novelty.FAMILIAR, STOP_FIRST, Modifier.weight(1f)) {
            onLevelChange(Novelty.FAMILIAR)
        }
        NoveltyStop(stringResource(R.string.brain_novelty_blend), level == Novelty.BLEND, STOP_MIDDLE, Modifier.weight(1f)) {
            onLevelChange(Novelty.BLEND)
        }
        NoveltyStop(stringResource(R.string.brain_novelty_discover), level == Novelty.DISCOVER, STOP_LAST, Modifier.weight(1f)) {
            onLevelChange(Novelty.DISCOVER)
        }
    }
}

private const val STOP_FIRST = 0
private const val STOP_MIDDLE = 1
private const val STOP_LAST = 2

private val StopHeight = 40.dp
private val OuterCorner = 20.dp
private val InnerCorner = 6.dp
private val PillShape = RoundedCornerShape(OuterCorner)
private val FirstShape = RoundedCornerShape(topStart = OuterCorner, bottomStart = OuterCorner, topEnd = InnerCorner, bottomEnd = InnerCorner)
private val MiddleShape = RoundedCornerShape(InnerCorner)
private val LastShape = RoundedCornerShape(topStart = InnerCorner, bottomStart = InnerCorner, topEnd = OuterCorner, bottomEnd = OuterCorner)

@Composable
private fun NoveltyStop(
    label: String,
    selected: Boolean,
    position: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = remember { MotionScheme.expressive() }
    val colorSpec = remember(motion) { motion.fastEffectsSpec<androidx.compose.ui.graphics.Color>() }
    val container = animateColorAsState(
        targetValue = if (selected) colors.primary else colors.surfaceContainerHighest,
        animationSpec = colorSpec,
        label = "noveltyContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        animationSpec = colorSpec,
        label = "noveltyContent",
    )
    // A chosen stop is a full pill; the others keep the group's outer corners and small inner ones.
    // The shapes are constants: only the colours animate, so a change allocates nothing per frame.
    val shape = when {
        selected -> PillShape
        position == STOP_FIRST -> FirstShape
        position == STOP_LAST -> LastShape
        else -> MiddleShape
    }
    Box(
        modifier = modifier
            .height(StopHeight)
            .clip(shape)
            // Read in the draw phase: the colour change repaints the stop without recomposing it.
            .drawBehind { drawRect(container.value) }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

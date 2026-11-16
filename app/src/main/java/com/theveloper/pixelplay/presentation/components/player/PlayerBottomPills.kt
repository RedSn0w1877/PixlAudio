package com.theveloper.pixelplay.presentation.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ripple
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.LocalMaterialTheme
import com.theveloper.pixelplay.presentation.components.ToggleSegmentButton

/** Height of the player's bottom pills (the same 68 dp as the split navigation bar's). */
internal val PlayerPillHeight = 68.dp
private val PlayerPillGap = 10.dp
private val PlayerPillInnerPadding = 8.dp
private val PlayerPillToggleHeight = 52.dp

/**
 * The now-playing screen's bottom bar in the split-pill language of the new navigation bar: a big
 * pill with Shuffle, Repeat and Like (the old toggle row's three [ToggleSegmentButton]s, same
 * state, same commands, same active colours) and a small round pill that opens the lyrics.
 *
 * Only the toggles' own state recomposes them; the press "squish" of the Lyrics pill is read in
 * its layer.
 */
@Composable
internal fun PlayerBottomPills(
    isShuffleEnabled: Boolean,
    isShuffleTransitionInProgress: Boolean,
    repeatMode: Int,
    isFavoriteProvider: () -> Boolean,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onLyricsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMaterialTheme.current
    val isFavorite = isFavoriteProvider()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PlayerPillHeight),
        horizontalArrangement = Arrangement.spacedBy(PlayerPillGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(scheme.surfaceContainerLowest.copy(alpha = 0.7f))
                .padding(horizontal = PlayerPillInnerPadding),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val toggleModifier = Modifier
                .weight(1f)
                .height(PlayerPillToggleHeight)
            val inactiveContent = scheme.onSurface
            ToggleSegmentButton(
                modifier = toggleModifier,
                active = isShuffleEnabled,
                enabled = !isShuffleTransitionInProgress,
                activeColor = scheme.primaryFixed,
                activeCornerRadius = PlayerPillToggleHeight / 2,
                activeContentColor = scheme.onPrimaryFixed,
                inactiveColor = Color.Transparent,
                inactiveContentColor = inactiveContent,
                onClick = onShuffleToggle,
                iconId = R.drawable.rounded_shuffle_24,
                contentDesc = stringResource(R.string.common_shuffle)
            )
            ToggleSegmentButton(
                modifier = toggleModifier,
                active = repeatMode != Player.REPEAT_MODE_OFF,
                activeColor = scheme.secondaryFixed,
                activeCornerRadius = PlayerPillToggleHeight / 2,
                activeContentColor = scheme.onSecondaryFixed,
                inactiveColor = Color.Transparent,
                inactiveContentColor = inactiveContent,
                onClick = onRepeatToggle,
                iconId = if (repeatMode == Player.REPEAT_MODE_ONE) {
                    R.drawable.rounded_repeat_one_24
                } else {
                    R.drawable.rounded_repeat_24
                },
                contentDesc = stringResource(R.string.common_repeat)
            )
            ToggleSegmentButton(
                modifier = toggleModifier,
                active = isFavorite,
                activeColor = scheme.tertiaryFixed,
                activeCornerRadius = PlayerPillToggleHeight / 2,
                activeContentColor = scheme.onTertiaryFixed,
                inactiveColor = Color.Transparent,
                inactiveContentColor = inactiveContent,
                onClick = onFavoriteToggle,
                iconId = if (isFavorite) R.drawable.round_favorite_24 else R.drawable.rounded_favorite_24,
                contentDesc = stringResource(R.string.common_favorite)
            )
        }

        PlayerSmallPill(
            iconRes = R.drawable.rounded_lyrics_24,
            contentDescription = stringResource(R.string.common_lyrics),
            containerColor = scheme.primaryFixed,
            contentColor = scheme.onPrimaryFixedVariant,
            onClick = onLyricsClick
        )
    }
}

/**
 * The small round pill (68 dp). Pressing squeezes it a little with the expressive fast spatial
 * spring, read in the layer only.
 */
@Composable
internal fun PlayerSmallPill(
    iconRes: Int,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressSpec = remember { MotionScheme.expressive().fastSpatialSpec<Float>() }
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = pressSpec,
        label = "playerSmallPillPress"
    )
    Box(
        modifier = modifier
            .size(PlayerPillHeight)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(CircleShape)
            .background(containerColor)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(26.dp)
        )
    }
}

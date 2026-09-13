package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.viewmodel.HomeGreeting
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * The greeting shown at the top of Home. [HomeGreeting.headline] crossfades/slides in on change
 * (local template -> AI-generated line, once a day) instead of snapping, and a slow diagonal
 * color sweep drifts underneath using the current Material scheme's own primary/tertiary roles —
 * same "derive from the theme, don't hardcode a palette" approach as the player's ambient
 * backgrounds ([com.theveloper.pixelplay.presentation.components.player.PlayerAmbientBackground]).
 */
@Composable
fun HomeGreetingCard(
    greeting: HomeGreeting,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 28.dp,
    expandedInsight: String? = null,
    isLoadingInsight: Boolean = false,
    onToggleExpanded: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val shape = AbsoluteSmoothCornerShape(
        cornerRadiusTL = cornerRadius,
        smoothnessAsPercentTL = 60,
        cornerRadiusTR = cornerRadius,
        smoothnessAsPercentTR = 60,
        cornerRadiusBL = cornerRadius,
        smoothnessAsPercentBL = 60,
        cornerRadiusBR = cornerRadius,
        smoothnessAsPercentBR = 60,
    )
    val isExpanded = expandedInsight != null || isLoadingInsight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainerHigh)
    ) {
        GreetingColorSweep(colorScheme = colors, modifier = Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.Top
            ) {
                AnimatedContent(
                    modifier = Modifier.weight(1f),
                    targetState = greeting.headline,
                    transitionSpec = {
                        (fadeIn(tween(450)) + slideInVertically(tween(450)) { full -> full / 3 })
                            .togetherWith(fadeOut(tween(200)) + slideOutVertically(tween(200)) { full -> -full / 3 })
                    },
                    label = "home_greeting_headline"
                ) { headline ->
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                GreetingExpandButton(
                    isExpanded = isExpanded,
                    isLoading = isLoadingInsight,
                    onClick = onToggleExpanded,
                    tint = colors.onSurface
                )
            }

            AnimatedContent(
                targetState = greeting.subtitle,
                transitionSpec = { fadeIn(tween(400)).togetherWith(fadeOut(tween(150))) },
                label = "home_greeting_subtitle"
            ) { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
            ) {
                Box(modifier = Modifier.padding(top = 8.dp)) {
                    if (isLoadingInsight) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.onSurfaceVariant
                        )
                    } else if (expandedInsight != null) {
                        Text(
                            text = expandedInsight,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GreetingExpandButton(
    isExpanded: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    tint: Color
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "greetingExpandPressScale"
    )
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "greetingExpandRotation"
    )

    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(32.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !isLoading,
                onClick = onClick
            ),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.rounded_keyboard_arrow_down_24),
            contentDescription = stringResource(
                if (isExpanded) R.string.home_greeting_collapse_insight else R.string.home_greeting_expand_insight
            ),
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .rotate(rotation)
        )
    }
}

/**
 * A soft diagonal band of color that drifts left-to-right on a loop, underneath the greeting
 * text. Pure [Canvas] draw, no bitmap/blur — cheap enough to run continuously while Home is
 * visible (same reasoning as [com.theveloper.pixelplay.presentation.components.player.PlayerAmbientEffects]'s effects).
 */
@Composable
private fun GreetingColorSweep(colorScheme: ColorScheme, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "GreetingColorSweep")
    val sweep by infiniteTransition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepOffset"
    )
    val bandColors = remember(colorScheme) {
        listOf(
            Color.Transparent,
            colorScheme.primary.copy(alpha = 0.22f),
            colorScheme.tertiary.copy(alpha = 0.20f),
            Color.Transparent
        )
    }

    // drawWithCache, not Canvas: the band's gradient never changes shape, only position, so the
    // Brush (and the Shader behind it) is built once per size/colour change instead of once per
    // frame. Rebuilding it inside the draw lambda meant a fresh List + LinearGradient shader
    // every frame, forever, on the screen the app opens to — pure GC pressure on the UI thread,
    // which is the thread the profile shows as the bottleneck.
    //
    // `sweep` is read only inside onDrawBehind, so it invalidates the draw phase alone; the
    // cache block does not re-run and composition is untouched.
    Spacer(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height
            // Band spans one full viewport width (half either side of its centre), matching the
            // old start/end offsets, so it can be drawn at a fixed origin and simply translated.
            val bandBrush = Brush.linearGradient(
                colors = bandColors,
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
            val bandSize = Size(w, h)
            onDrawBehind {
                translate(left = sweep * w - w * 0.5f) {
                    drawRect(brush = bandBrush, topLeft = Offset.Zero, size = bandSize)
                }
            }
        }
    )
}

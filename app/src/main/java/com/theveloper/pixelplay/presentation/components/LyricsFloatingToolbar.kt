package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.Abc
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.viewmodel.SingUi
import com.theveloper.pixelplay.ui.glass.glassPressSwell
import com.theveloper.pixelplay.ui.glass.motion.LiquidMotion
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import kotlin.math.roundToInt

/** What the toolbar's Translate segment shows; LyricsSheet derives it, the toolbar only draws it. */
@Immutable
data class LyricsTranslateSegment(
    /** The lyrics' translations are showing. */
    val active: Boolean = false,
    /** The lyrics have synced lines to translate. Without them the segment is dimmed; a tap explains. */
    val available: Boolean = true,
    /** An on-device translation (or its model download) is running. */
    val busy: Boolean = false,
    /** The long-press menu offers "Show romanization" (the lyrics have romanizable text). */
    val hasRomanization: Boolean = false,
    val showRomanization: Boolean = true,
)

/**
 * The lyrics screen's toolbar: Back · Translate · Sing · More. (Translate · Sing replaced the
 * Synced · Static switch: synced vs plain is automatic, with "Show as plain text" in More.)
 *
 * - Translate: a tap shows or hides the lyrics' translations, or translates them on this phone
 *   when they have none. A long press opens Translate via AI and Show romanization.
 * - Sing: vocals off / on through the song's studio instrumental, with the render's progress
 *   filling the segment the first time.
 *
 * Material 3 mode keeps the tonal pills. Liquid Glass mode ([glass]) draws the toolbar's content
 * for ONE glass bar the caller provides (LyricsSheet): flat subtle-tint circles and segments on
 * it, the active segment flooded with [glassAccent] as the full player's selected chips are, and
 * NexHome's press swell on every button. No glass node of its own (the bar is the lens).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsFloatingToolbar(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit,
    hasLyrics: Boolean,
    translate: LyricsTranslateSegment,
    onTranslate: () -> Unit,
    onTranslateViaAi: () -> Unit,
    onShowRomanizationChange: (Boolean) -> Unit,
    sing: SingUi,
    onSing: () -> Unit,
    onMoreClick: () -> Unit,
    backgroundColor: Color,
    onBackgroundColor: Color,
    accentColor: Color,
    onAccentColor: Color,
    /** The Material colours of the long-press menu (a popup: it stays opaque in both modes). */
    menuColorScheme: ColorScheme = MaterialTheme.colorScheme,
    // Draw-phase lambda: 0f = fully visible, 1f = dismissed. Read inside graphicsLayer to avoid recomposition per frame.
    backProgressProvider: () -> Float = { 0f },
    glass: Boolean = false,
    glassAccent: Color = Color.Unspecified,
) {
    val haptic = LocalHapticFeedback.current
    var translateMenuOpen by remember { mutableStateOf(false) }
    // Taps answer with a tick; the state changes they cause (a song change dropping translations,
    // Sing resetting) don't.
    val tapTranslate = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onTranslate()
    }
    val tapSing = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onSing()
    }
    val openTranslateMenu = { translateMenuOpen = true }

    val translateLabel = stringResource(if (translate.busy) R.string.lyrics_translating else R.string.lyrics_translate)
    val translateState = stringResource(
        if (translate.active) R.string.lyrics_translate_state_shown else R.string.lyrics_translate_state_hidden
    )
    val singProgress = sing.progress
    val singLabel = when {
        sing.active -> stringResource(R.string.lyrics_sing_vocals_off)
        sing.rendering && singProgress != null ->
            stringResource(R.string.lyrics_sing_removing, (singProgress * 100f).roundToInt())
        sing.rendering -> stringResource(R.string.lyrics_sing_removing_queued)
        else -> stringResource(R.string.lyrics_sing)
    }
    val singState = stringResource(if (sing.active) R.string.lyrics_sing_vocals_off else R.string.lyrics_sing_vocals_on)
    val singIcon = if (sing.active) Icons.Rounded.MicOff else Icons.Rounded.Mic
    val singProgressProvider: (() -> Float)? = if (sing.rendering) ({ singProgress ?: 0f }) else null
    val moreOptionsLabel = stringResource(R.string.lyrics_translate_more_options)

    // With no lyrics (loading, or none found) there is nothing to translate or sing along to, but
    // Back and More stay: the screen must always be easy to leave and to fix.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (glass) Modifier.padding(4.dp) else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LyricsToolbarBackButton(
            onNavigateBack = onNavigateBack,
            backgroundColor = backgroundColor,
            onBackgroundColor = onBackgroundColor,
            backProgressProvider = backProgressProvider,
            glass = glass,
        )

        Spacer(modifier = Modifier.width(if (glass) 6.dp else 8.dp))

        val segmentHeight = if (glass) GlassSegmentHeight else MaterialSegmentHeight
        if (!hasLyrics) {
            Spacer(modifier = Modifier.weight(1f).height(segmentHeight))
        } else Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (glass) 6.dp else 8.dp)
        ) {
            Box(modifier = Modifier.weight(1f).height(segmentHeight)) {
                if (glass) {
                    GlassToolbarSegment(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        text = translateLabel,
                        icon = Icons.Rounded.Translate,
                        active = translate.active,
                        dimmed = !translate.available,
                        accent = glassAccent,
                        onClick = tapTranslate,
                        onLongClick = openTranslateMenu,
                        onLongClickLabel = moreOptionsLabel,
                        stateDescription = translateState,
                    )
                } else {
                    ToggleSegmentButton(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        active = translate.active,
                        activeColor = accentColor,
                        inactiveColor = backgroundColor,
                        activeContentColor = onAccentColor,
                        inactiveContentColor = onBackgroundColor,
                        activeCornerRadius = 50.dp,
                        onClick = tapTranslate,
                        text = translateLabel,
                        imageVector = Icons.Rounded.Translate,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        onLongClick = openTranslateMenu,
                        onLongClickLabel = moreOptionsLabel,
                        stateDescription = translateState,
                        dimmed = !translate.available,
                    )
                }
                MaterialTheme(colorScheme = menuColorScheme) {
                    DropdownMenu(
                        expanded = translateMenuOpen,
                        onDismissRequest = { translateMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lyrics_translate_via_ai)) },
                            leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                            onClick = {
                                translateMenuOpen = false
                                onTranslateViaAi()
                            }
                        )
                        if (translate.hasRomanization) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.lyrics_controls_show_romanization)) },
                                leadingIcon = { Icon(Icons.Rounded.Abc, contentDescription = null) },
                                trailingIcon = if (translate.showRomanization) {
                                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    translateMenuOpen = false
                                    onShowRomanizationChange(!translate.showRomanization)
                                }
                            )
                        }
                    }
                }
            }

            if (glass) {
                GlassToolbarSegment(
                    modifier = Modifier.weight(1f).height(segmentHeight),
                    text = singLabel,
                    icon = singIcon,
                    active = sing.active,
                    enabled = sing.enabled,
                    accent = glassAccent,
                    onClick = tapSing,
                    progress = singProgressProvider,
                    stateDescription = singState,
                )
            } else {
                ToggleSegmentButton(
                    modifier = Modifier.weight(1f).height(segmentHeight),
                    active = sing.active,
                    enabled = sing.enabled,
                    activeColor = accentColor,
                    inactiveColor = backgroundColor,
                    activeContentColor = onAccentColor,
                    inactiveContentColor = onBackgroundColor,
                    activeCornerRadius = 50.dp,
                    onClick = tapSing,
                    text = singLabel,
                    imageVector = singIcon,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    progress = singProgressProvider,
                    stateDescription = singState,
                )
            }
        }

        Spacer(modifier = Modifier.width(if (glass) 6.dp else 8.dp))

        if (glass) {
            GlassToolbarCircle(
                onClick = onMoreClick,
                icon = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.lyrics_options),
            )
        } else {
            IconButton(
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = backgroundColor,
                    contentColor = onBackgroundColor
                ),
                onClick = onMoreClick
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.lyrics_options),
                    tint = onBackgroundColor
                )
            }
        }
    }
}

/**
 * Back, which also follows the predictive-back gesture. Material 3 shrinks on press; Liquid Glass
 * (NexHome's rule: presses swell, never shrink) swells to the orb press scale with the press
 * spring and settles back with the bouncy release spring.
 */
@Composable
private fun LyricsToolbarBackButton(
    onNavigateBack: () -> Unit,
    backgroundColor: Color,
    onBackgroundColor: Color,
    backProgressProvider: () -> Float,
    glass: Boolean,
) {
    val backInteractionSource = remember { MutableInteractionSource() }
    val isBackPressed by backInteractionSource.collectIsPressedAsState()
    val backPressScale = animateFloatAsState(
        targetValue = when {
            !isBackPressed -> 1f
            glass -> LiquidMotion.OrbPressScale
            else -> 0.82f
        },
        animationSpec = when {
            !glass -> BackPressSpring
            isBackPressed -> LiquidMotion.PressSpring
            else -> LiquidMotion.ReleaseSpring
        },
        label = "backPressScale"
    )
    // Press scale combined with the predictive-back gesture's scale, both read in the layer.
    val pressAndGesture = Modifier.graphicsLayer {
        val combined = backPressScale.value * lerp(1f, 0.7f, backProgressProvider())
        scaleX = combined
        scaleY = combined
    }
    val backLabel = stringResource(R.string.common_back)
    if (glass) {
        val palette = LocalGlassPalette.current
        Box(
            modifier = pressAndGesture
                .size(GlassCircleSize)
                .clip(CircleShape)
                .background(palette.tintSubtle)
                .clickable(
                    interactionSource = backInteractionSource,
                    indication = rememberGlassPressIndication(),
                    role = Role.Button,
                    onClick = onNavigateBack
                )
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = palette.primary)
        }
    } else {
        IconButton(
            modifier = pressAndGesture,
            interactionSource = backInteractionSource,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = backgroundColor,
                contentColor = onBackgroundColor
            ),
            onClick = onNavigateBack
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = backLabel,
                tint = onBackgroundColor
            )
        }
    }
}

/** A round button on the glass bar: the palette's subtle tint, NexHome's orb press swell. */
@Composable
private fun GlassToolbarCircle(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
) {
    val palette = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .glassPressSwell(interaction, LiquidMotion.OrbPressScale)
            .size(GlassCircleSize)
            .clip(CircleShape)
            .background(palette.tintSubtle)
            .clickable(
                interactionSource = interaction,
                indication = rememberGlassPressIndication(),
                role = Role.Button,
                onClick = onClick
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = palette.primary)
    }
}

/**
 * A segment on the glass bar: a subtle-tint capsule that, when [active], is flooded with [accent]
 * exactly as the full player's selected LiquidChip is (Hue 0.9 + 0.42, LiquidChip.kt), so it
 * reads as strongly as the player's chips. The flood, the [progress] fill and the press swell are
 * all read in the layer / draw phase. [dimmed] looks unavailable but still answers the tap (and
 * the long press), which explains why.
 */
@Composable
private fun GlassToolbarSegment(
    modifier: Modifier,
    text: String,
    icon: ImageVector,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    dimmed: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    progress: (() -> Float)? = null,
    stateDescription: String? = null,
) {
    val palette = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    val selection = remember { Animatable(if (active) 1f else 0f, 0.001f) }
    LaunchedEffect(active) {
        selection.animateTo(if (active) 1f else 0f, LiquidMotion.ReleaseSpring)
    }
    val fillAccent = if (accent == Color.Unspecified) palette.accent else accent
    val currentAccent by rememberUpdatedState(fillAccent)
    val currentProgress by rememberUpdatedState(progress)
    val rest = palette.tintSubtle
    val indication = rememberGlassPressIndication()
    val looksEnabled = enabled && !dimmed
    Box(
        modifier = modifier
            .glassPressSwell(interaction, LiquidMotion.TilePressScale)
            .graphicsLayer {
                shape = CircleShape
                clip = true
            }
            .drawBehind {
                drawRect(rest)
                val s = selection.value.fastCoerceIn(0f, 1f)
                if (s > 0.001f) {
                    drawRect(currentAccent.copy(alpha = 0.9f * s), blendMode = BlendMode.Hue)
                    drawRect(currentAccent.copy(alpha = 0.42f * s))
                }
                val fraction = currentProgress?.invoke()?.fastCoerceIn(0f, 1f) ?: 0f
                if (fraction > 0f) {
                    drawRect(currentAccent.copy(alpha = 0.35f), size = Size(size.width * fraction, size.height))
                }
            }
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = indication,
                        enabled = enabled,
                        role = Role.Button,
                        onLongClickLabel = onLongClickLabel,
                        onLongClick = onLongClick,
                        onClick = onClick
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = indication,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick
                    )
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
        Row(
            modifier = Modifier
                .graphicsLayer(alpha = if (looksEnabled) 1f else 0.38f)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = palette.primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = if (active) palette.primary else palette.secondary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

private val MaterialSegmentHeight = 50.dp
private val GlassSegmentHeight = 44.dp
private val GlassCircleSize = 44.dp

// Material 3 mode: the back button press spring (unchanged).
private val BackPressSpring = spring<Float>(
    stiffness = Spring.StiffnessMedium,
    dampingRatio = Spring.DampingRatioMediumBouncy
)

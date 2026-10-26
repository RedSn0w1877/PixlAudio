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
import com.theveloper.pixelplay.presentation.viewmodel.SingPhase
import com.theveloper.pixelplay.presentation.viewmodel.SingUi
import kotlin.math.roundToInt

/** What the toolbar's Translate segment shows; LyricsSheet derives it, the toolbar only draws it. */
@Immutable
data class LyricsTranslateSegment(
    /** The lyrics' translations are showing. */
    val active: Boolean = false,
    /** The lyrics have synced lines to translate. Without them the segment is dimmed; a tap explains. */
    val available: Boolean = true,
    /** A translation is running. */
    val busy: Boolean = false,
    /** The long-press menu offers "Show romanization" (the lyrics have romanizable text). */
    val hasRomanization: Boolean = false,
    val showRomanization: Boolean = true,
)

/**
 * The lyrics screen's toolbar: Back · Translate · Sing · More. (Translate · Sing replaced the
 * Synced · Static switch: synced vs plain is automatic.)
 *
 * - Translate: a tap shows or hides the lyrics' translations, or translates them with AI
 *   when they have none. A long press opens Translate via AI and Show romanization.
 * - Sing: vocals off / on through the song's studio instrumental, with the render's progress
 *   filling the segment the first time. When the cloud prepares it the button says "Preparing…"
 *   (or "Waiting for Wi-Fi") while the song plays on with its lyrics, then "Vocals off".
 *
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
        // The cloud is preparing the vocal-free version while the song plays on: its steps are plain words.
        sing.phase == SingPhase.CLOUD_WIFI -> stringResource(R.string.lyrics_sing_waiting_wifi)
        sing.phase == SingPhase.CLOUD -> stringResource(R.string.lyrics_sing_preparing)
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
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LyricsToolbarBackButton(
            onNavigateBack = onNavigateBack,
            backgroundColor = backgroundColor,
            onBackgroundColor = onBackgroundColor,
            backProgressProvider = backProgressProvider,
        )

        Spacer(modifier = Modifier.width(8.dp))

        val segmentHeight = MaterialSegmentHeight
        if (!hasLyrics) {
            Spacer(modifier = Modifier.weight(1f).height(segmentHeight))
        } else Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f).height(segmentHeight)) {
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

        Spacer(modifier = Modifier.width(8.dp))

        run {
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

/** Back, which also follows the predictive-back gesture and shrinks on press. */
@Composable
private fun LyricsToolbarBackButton(
    onNavigateBack: () -> Unit,
    backgroundColor: Color,
    onBackgroundColor: Color,
    backProgressProvider: () -> Float,
) {
    val backInteractionSource = remember { MutableInteractionSource() }
    val isBackPressed by backInteractionSource.collectIsPressedAsState()
    val backPressScale = animateFloatAsState(
        targetValue = if (isBackPressed) 0.82f else 1f,
        animationSpec = BackPressSpring,
        label = "backPressScale"
    )
    // Press scale combined with the predictive-back gesture's scale, both read in the layer.
    val pressAndGesture = Modifier.graphicsLayer {
        val combined = backPressScale.value * lerp(1f, 0.7f, backProgressProvider())
        scaleX = combined
        scaleY = combined
    }
    val backLabel = stringResource(R.string.common_back)
    run {
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

private val MaterialSegmentHeight = 50.dp

// Material 3 mode: the back button press spring (unchanged).
private val BackPressSpring = spring<Float>(
    stiffness = Spring.StiffnessMedium,
    dampingRatio = Spring.DampingRatioMediumBouncy
)

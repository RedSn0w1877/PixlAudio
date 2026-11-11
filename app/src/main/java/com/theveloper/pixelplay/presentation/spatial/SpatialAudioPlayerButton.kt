package com.theveloper.pixelplay.presentation.spatial

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.presentation.viewmodel.SpatialAudioViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The full player's Spatial audio button (cloud-audio-spec §6.5), the third sibling of Lyrics and Ask Taizo in the
 * song row. It collects its own two small slices (the stored profile and what holds it back), so a profile change
 * recomposes this button only, never the player around it. Tapping it opens [SpatialAudioSheet].
 */
@Composable
fun SpatialAudioPlayerButton(
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpatialAudioViewModel = hiltViewModel(),
) {
    val holder = viewModel.spatial
    val ui by remember(holder) {
        combine(holder.storedSettings, holder.audible) { stored, heard ->
            stored?.let { SpatialButtonUi.of(it.profile, heard.blockedBy) }
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = holder.storedSettings.value?.let { SpatialButtonUi.of(it.profile, holder.audible.value.blockedBy) }
    )
    SpatialAudioButtonContent(
        ui = ui,
        containerColor = containerColor,
        contentColor = contentColor,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * The button itself, stateless. [ui] is null until the settings have been read: it then shows Off and does not
 * animate, so a stored profile appears in place instead of growing in from Off.
 *
 * Off: a 48 dp circle in the row's chip colours, the size of Lyrics and Ask Taizo, with "Off" under the icon. On: the
 * colours swap (the selected look of Material's toggles), the corners tighten and the profile's name takes the place
 * of "Off", the button widening for it. Held back ([SpatialButtonUi.heldBack]): the name stays, the emphasis goes.
 * Colours and corners animate in the draw and layer phases only; the width follows the label in the layout phase.
 */
@Composable
internal fun SpatialAudioButtonContent(
    ui: SpatialButtonUi?,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val emphasized = ui != null && ui.isOn && !ui.heldBack
    // Changes animate only once the first real state has been shown: the stored profile appears in place.
    var animate by remember { mutableStateOf(false) }
    LaunchedEffect(ui != null) {
        if (ui != null) animate = true
    }
    val colorSpec: AnimationSpec<Color> = if (animate) tween(durationMillis = ColorMillis) else snap()
    val container = animateColorAsState(
        targetValue = if (emphasized) contentColor else containerColor,
        animationSpec = colorSpec,
        label = "spatialButtonContainer",
    )
    val content = animateColorAsState(
        targetValue = if (emphasized) containerColor.copy(alpha = 1f) else contentColor,
        animationSpec = colorSpec,
        label = "spatialButtonContent",
    )
    val corner = animateDpAsState(
        targetValue = if (emphasized) CornerOn else CornerOff,
        animationSpec = if (animate) {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        } else {
            snap()
        },
        label = "spatialButtonCorner",
    )
    val contentTint = remember(content) { ColorProducer { content.value } }

    // "Off" or the profile's name: shown under the icon, and what TalkBack reads.
    val label = stringResource(ui?.labelRes ?: R.string.spatial_ui_off)
    val spoken = if (ui?.heldBack == true) {
        stringResource(R.string.spatial_ui_cd_button_held, label)
    } else {
        stringResource(R.string.spatial_ui_cd_button, label)
    }

    Box(
        modifier = modifier
            .height(ButtonSize)
            .then(
                if (animate) {
                    Modifier.animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                    )
                } else {
                    Modifier
                }
            )
            .widthIn(min = ButtonSize, max = MaxWidth)
            .graphicsLayer {
                shape = RoundedCornerShape(corner.value)
                clip = true
            }
            .drawBehind { drawRect(container.value) }
            .semantics {
                contentDescription = spoken
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(horizontal = LabelPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.rounded_spatial_audio_24),
                tint = contentTint,
                contentDescription = null,
                modifier = Modifier.size(IconSize),
            )
            BasicText(
                text = label,
                // The button's description already says it.
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentTint,
            )
        }
    }
}

/**
 * Developer mode's file-info label in the full player: what it adds while Spatial audio is audible ("Spatial Studio
 * on phone", "Spatial Studio cloud render"), or null in stereo. Call it only while the label is shown: it collects
 * what is audible for as long as it is composed.
 */
@Composable
fun rememberSpatialFileInfoLabel(viewModel: SpatialAudioViewModel = hiltViewModel()): String? {
    val holder = viewModel.spatial
    val info by remember(holder) {
        holder.audible.map { SpatialAudioUi.fileInfo(it) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = SpatialAudioUi.fileInfo(holder.audible.value))
    val shown = info ?: return null
    return stringResource(shown.res, stringResource(SpatialAudioUi.nameRes(shown.profile)))
}

private val ButtonSize = 48.dp
/** "Concert Hall" fits; a longer translated name ellipsizes instead of squeezing the song title. */
private val MaxWidth = 104.dp
private val IconSize = 20.dp
private val LabelPadding = 6.dp
private val CornerOff = 24.dp
private val CornerOn = 16.dp
private const val ColorMillis = 220

@Preview(showBackground = true)
@Composable
private fun SpatialAudioButtonPreview() {
    MaterialTheme {
        val chip = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
        val chipContent = MaterialTheme.colorScheme.primary
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpatialAudioButtonContent(ui = SpatialButtonUi.OFF, containerColor = chip, contentColor = chipContent, onClick = {})
            SpatialAudioButtonContent(
                ui = SpatialButtonUi(SpatialProfile.STUDIO, heldBack = false),
                containerColor = chip,
                contentColor = chipContent,
                onClick = {},
            )
            SpatialAudioButtonContent(
                ui = SpatialButtonUi(SpatialProfile.HALL, heldBack = false),
                containerColor = chip,
                contentColor = chipContent,
                onClick = {},
            )
            SpatialAudioButtonContent(
                ui = SpatialButtonUi(SpatialProfile.NIGHT, heldBack = true),
                containerColor = chip,
                contentColor = chipContent,
                onClick = {},
            )
        }
    }
}

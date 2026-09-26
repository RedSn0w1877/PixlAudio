package com.theveloper.pixelplay.presentation.lyrics.sync

import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.viewmodel.LyricsSyncEditorStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.SyncNotice
import com.theveloper.pixelplay.presentation.viewmodel.SyncNoticeKind
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.theveloper.pixelplay.ui.glass.LocalGlassModeEnabled
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Colours for the editor. Everything sits on the animated artwork (dark, graded), so text is
 * white; the controls follow the theme mode:
 * - Material 3 Expressive: tonal/primary containers from the album scheme, round shapes.
 * - Liquid Glass: translucent white fills with a thin rim ("fills on glass", no extra backdrop
 *   nodes over an animated background), continuous-corner shapes and a soft press glow.
 */
@Immutable
internal class SyncEditorPalette(
    val glass: Boolean,
    /** Highlight colour that reads on the dark artwork (the word being sung, selections). */
    val accent: Color,
    val onAccent: Color,
    val padContainer: Color,
    val onPad: Color,
    val chipContainer: Color,
    val onChip: Color,
    val rim: Color?,
    val panelContainer: Color,
    val onPanel: Color,
    val panelButton: Color,
    val onPanelButton: Color,
    val prominent: Color,
    val onProminent: Color,
    val capsule: Shape,
    val padShape: Shape,
    val panelShape: Shape,
)

@Composable
internal fun rememberSyncEditorPalette(): SyncEditorPalette {
    val scheme = MaterialTheme.colorScheme
    val glass = LocalGlassModeEnabled.current
    return remember(scheme, glass) { buildPalette(scheme, glass) }
}

// The glass-mode shapes: continuous corners, created once.
private val GlassEditorCapsule: Shape = Capsule()
private val GlassEditorPadShape: Shape = RoundedRectangle(36.dp)
private val GlassEditorPanelShape: Shape = RoundedRectangle(32.dp)

private fun buildPalette(scheme: ColorScheme, glass: Boolean): SyncEditorPalette {
    val accent = if (scheme.primary.luminance() > 0.30f) scheme.primary else scheme.inversePrimary
    val onAccent = if (accent.luminance() > 0.45f) Color(0xFF111114) else Color.White
    if (glass) {
        // Liquid Glass mode: translucent white fills with a thin rim ("fills on glass", no
        // backdrop nodes over the animated artwork), continuous corners — exactly as before.
        return SyncEditorPalette(
            glass = true,
            accent = accent,
            onAccent = onAccent,
            padContainer = Color.White.copy(alpha = 0.16f),
            onPad = Color.White,
            chipContainer = Color.White.copy(alpha = 0.12f),
            onChip = Color.White,
            rim = Color.White.copy(alpha = 0.24f),
            panelContainer = Color.Black.copy(alpha = 0.32f),
            onPanel = Color.White,
            panelButton = Color.White.copy(alpha = 0.12f),
            onPanelButton = Color.White,
            prominent = accent,
            onProminent = onAccent,
            capsule = GlassEditorCapsule,
            padShape = GlassEditorPadShape,
            panelShape = GlassEditorPanelShape,
        )
    }
    return SyncEditorPalette(
        glass = false,
        accent = accent,
        onAccent = onAccent,
        padContainer = scheme.primaryContainer,
        onPad = scheme.onPrimaryContainer,
        chipContainer = scheme.secondaryContainer,
        onChip = scheme.onSecondaryContainer,
        rim = null,
        panelContainer = scheme.surfaceContainer,
        onPanel = scheme.onSurface,
        panelButton = scheme.secondaryContainer,
        onPanelButton = scheme.onSecondaryContainer,
        prominent = scheme.primary,
        onProminent = scheme.onPrimary,
        capsule = CircleShape,
        padShape = RoundedCornerShape(36.dp),
        panelShape = RoundedCornerShape(28.dp),
    )
}

/** Press feedback that matches the theme mode: a soft glass glow or the Material ripple. */
@Composable
internal fun editorIndication(palette: SyncEditorPalette): Indication =
    if (palette.glass) SyncEditorPressGlow else ripple()

/**
 * Glass-mode press feedback for the editor: a white wash added with [BlendMode.Plus] that fades in
 * on press and out on release, drawn under the content so labels stay crisp. The editor's own
 * glow (it keeps its look in both modes), not the kit's swell.
 */
private object SyncEditorPressGlow : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        SyncEditorPressGlowNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()
}

private class SyncEditorPressGlowNode(
    private val interactionSource: InteractionSource
) : Modifier.Node(), DrawModifierNode {

    private val progress = Animatable(0f, 0.001f)
    private val spec = spring(0.5f, 300f, 0.001f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressed = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel ->
                        pressed = (pressed - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                val target = if (pressed > 0) 1f else 0f
                launch { progress.animateTo(target, spec) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = progress.value
        if (p > 0f) {
            drawRect(Color.White.copy(alpha = 0.14f * p), blendMode = BlendMode.Plus)
        }
        drawContent()
    }
}

/**
 * The editor's one button shape: a 56 dp capsule. [prominent] is the primary action;
 * [onPanel] picks the colours for buttons inside the preview panel.
 */
@Composable
internal fun EditorButton(
    onClick: () -> Unit,
    palette: SyncEditorPalette,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    onPanel: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    horizontalPadding: Dp = 20.dp,
    content: @Composable RowScope.(contentColor: Color) -> Unit,
) {
    val container = when {
        prominent -> palette.prominent
        onPanel -> palette.panelButton
        else -> palette.chipContainer
    }
    val contentColor = when {
        prominent -> palette.onProminent
        onPanel -> palette.onPanelButton
        else -> palette.onChip
    }
    val shape = palette.capsule
    Row(
        modifier = modifier
            .heightIn(min = height)
            .clip(shape)
            .background(if (enabled) container else container.copy(alpha = container.alpha * 0.45f))
            .then(if (palette.rim != null && !prominent) Modifier.border(0.75.dp, palette.rim, shape) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = editorIndication(palette),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(if (enabled) contentColor else contentColor.copy(alpha = 0.5f))
    }
}

/**
 * A compact editor button: icon over a short label, so three of them side by side always read
 * in full on a narrow phone and at large font scales.
 */
@Composable
internal fun EditorStackedButton(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    palette: SyncEditorPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    EditorButton(
        onClick = onClick,
        palette = palette,
        modifier = modifier,
        enabled = enabled,
        height = 60.dp,
        horizontalPadding = 6.dp,
    ) { color ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Text(
                text = label,
                color = color,
                fontSize = 13.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun EditorButtonText(text: String, color: Color, bold: Boolean = true) {
    Text(
        text = text,
        color = color,
        fontSize = 16.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** ✕, the song title (14 sp, 70 %) and the speed pill. */
@Composable
internal fun SyncTopBar(
    title: String,
    palette: SyncEditorPalette,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    speed: Float? = null,
    onSpeedChange: (Float) -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(palette.capsule)
                .background(palette.chipContainer)
                .then(if (palette.rim != null) Modifier.border(0.75.dp, palette.rim, palette.capsule) else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = editorIndication(palette),
                    role = Role.Button,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close), tint = palette.onChip)
        }
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )
        if (speed != null) SpeedPill(speed, palette, onSpeedChange)
    }
}

@Composable
internal fun speedLabel(speed: Float): String = when (speed) {
    1f -> stringResource(R.string.lyrics_sync_speed_normal)
    else -> stringResource(R.string.lyrics_sync_speed_x, if (speed == 0.5f) "0.5" else "0.75")
}

@Composable
private fun SpeedPill(speed: Float, palette: SyncEditorPalette, onSpeedChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(palette.capsule)
                .background(if (speed == 1f) palette.chipContainer else palette.accent)
                .then(if (palette.rim != null && speed == 1f) Modifier.border(0.75.dp, palette.rim, palette.capsule) else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = editorIndication(palette),
                    role = Role.DropdownList,
                ) { expanded = true }
                .padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = if (speed == 1f) palette.onChip else palette.onAccent
            Text(speedLabel(speed), color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SPEED_OPTIONS.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = if (value == speed) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        expanded = false
                        onSpeedChange(value)
                    },
                )
            }
        }
    }
}

/** Speed choices: Normal, Slower (0.75×), Slowest (0.5×). */
internal val SPEED_OPTIONS: List<Pair<Float, Int>> = listOf(
    1f to R.string.lyrics_sync_speed_normal,
    0.75f to R.string.lyrics_sync_speed_slower,
    0.5f to R.string.lyrics_sync_speed_slowest,
)

/** Three-way speed picker for the intro screen. */
@Composable
internal fun SpeedSegments(speed: Float, palette: SyncEditorPalette, onSpeedChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SPEED_OPTIONS.forEach { (value, label) ->
            val selected = value == speed
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(palette.capsule)
                    .background(if (selected) palette.accent else palette.chipContainer)
                    .then(if (!selected && palette.rim != null) Modifier.border(0.75.dp, palette.rim, palette.capsule) else Modifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = editorIndication(palette),
                        role = Role.RadioButton,
                    ) { onSpeedChange(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(label),
                    color = if (selected) palette.onAccent else palette.onChip,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Transient message pill ("Removed timing for 14 words · Undo"). Dismisses itself after 4 s. */
@Composable
internal fun SyncNoticePill(
    notice: SyncNotice,
    palette: SyncEditorPalette,
    holder: LyricsSyncEditorStateHolder,
    modifier: Modifier = Modifier,
) {
    val currentHolder by rememberUpdatedState(holder)
    LaunchedEffect(notice.id) {
        delay(NOTICE_MS)
        currentHolder.dismissNotice(notice.id)
    }
    val text = when (notice.kind) {
        SyncNoticeKind.REMOVED_WORDS -> stringResource(R.string.lyrics_sync_removed_words, notice.count)
        SyncNoticeKind.WAIT_TIP -> stringResource(R.string.lyrics_sync_wait_tip)
        SyncNoticeKind.PAST_NEXT_LINE -> stringResource(R.string.lyrics_sync_past_next_line)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xE6202024))
            .padding(start = 18.dp, end = if (notice.canUndo) 6.dp else 18.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (notice.canUndo) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { holder.undoRemoval() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.common_undo),
                    color = palette.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private const val NOTICE_MS = 4_000L

/** True when every character renders in the app's bundled lyrics font (ASCII plus a few marks). */
internal fun isCoveredByAppFont(text: CharSequence): Boolean = text.all { char ->
    char.code in 0x20..0x7E || char == '\n' || char in APP_FONT_EXTRA
}

private const val APP_FONT_EXTRA = "‘’“”–—…  ·≈"

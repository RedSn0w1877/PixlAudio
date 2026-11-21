package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.theveloper.pixelplay.R
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun LyricsSyncControls(
    modifier: Modifier = Modifier,
    offsetMillis: Int,
    onOffsetChange: (Int) -> Unit,
    backgroundColor: Color,
    accentColor: Color,
    onAccentColor: Color,
    onBackgroundColor: Color
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // New UI split-pill language: same 68 dp height and 8 dp inner padding as the
            // toolbar pills, so the buttons are the same 52 dp as its toggles. The strip sits
            // above the play/seek row (50 dp), not directly above the toolbar.
            .height(LyricsPillHeight)
            .background(
                color = backgroundColor,
                shape = CircleShape
            )
            .padding(LyricsPillInnerPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // -0.5s
        SyncButton(
            text = stringResource(R.string.lyrics_offset_minus_half),
            onClick = { onOffsetChange(offsetMillis - 500) },
            weight = 1f,
            containerColor = accentColor,
            contentColor = onAccentColor
        )
        // -0.1s
        SyncButton(
            text = stringResource(R.string.lyrics_offset_minus_point_one),
            onClick = { onOffsetChange(offsetMillis - 100) },
            weight = 1f,
            containerColor = accentColor,
            contentColor = onAccentColor
        )
        // Center Display / Reset
        SyncButton(
            text = if (offsetMillis == 0) {
                stringResource(R.string.lyrics_offset_zero)
            } else {
                stringResource(R.string.lyrics_offset_seconds_fmt, offsetMillis / 1000f)
            },
            onClick = { onOffsetChange(0) },
            weight = 1.3f, // Slightly wider
            // Idle (offset 0): a visible tonal container, not the strip's own colour, so the
            // middle slot reads as a button and not as a gap.
            containerColor = if (offsetMillis != 0) accentColor else onBackgroundColor.copy(alpha = 0.12f),
            contentColor = if (offsetMillis != 0) onAccentColor else onBackgroundColor,
            enabled = offsetMillis != 0
        )
        // +0.1s
        SyncButton(
            text = stringResource(R.string.lyrics_offset_plus_point_one),
            onClick = { onOffsetChange(offsetMillis + 100) },
            weight = 1f,
            containerColor = accentColor,
            contentColor = onAccentColor
        )
        // +0.5s
        SyncButton(
            text = stringResource(R.string.lyrics_offset_plus_half),
            onClick = { onOffsetChange(offsetMillis + 500) },
            weight = 1f,
            containerColor = accentColor,
            contentColor = onAccentColor
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SyncButton(
    text: String,
    onClick: () -> Unit,
    weight: Float,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight(),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor
        ),
        contentPadding = PaddingValues(0.dp) // Tight padding
    ) {
        // Theme label token (scales with the system font size). When it overflows its slot it
        // shrinks a step at a time so five labels fit at large font scales; ellipsis is the
        // last resort. It only re-measures when the text actually overflows.
        val base = MaterialTheme.typography.labelMedium
        var shrink by remember(text) { mutableFloatStateOf(1f) }
        Text(
            text = text,
            style = base.copy(fontSize = base.fontSize * shrink),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (it.didOverflowWidth && shrink > 0.6f) shrink *= 0.9f }
        )
    }
}

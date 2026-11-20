package com.theveloper.pixelplay.presentation.components.taizo

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.ai.claude.taizo.music.MixCardChip
import com.theveloper.pixelplay.presentation.viewmodel.TaizoChatItem

/**
 * The two cards that sit under an Ask Taizo answer (docs/brain-spec.md, 9.3 "What the listener gets"): a mix from a sentence, with Play,
 * Save and the chips that change it; and the one-tap "Less X" that ends an explanation. Material 3 Expressive, existing components only.
 * Nothing here calls a model: a chip is a delta on the stored request and the engine builds the list again on the phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MixCardBubble(
    item: TaizoChatItem.Mix,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onChip: (MixCardChip) -> Unit,
) {
    val card = item.card
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(16.dp)
            .animateContentSize(),
    ) {
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = pluralStringResource(R.plurals.brain_taizo_mix_songs, card.total, card.total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (card.preview.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            card.preview.forEach { track ->
                Text(
                    text = track.title + " · " + track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onPlay, enabled = !item.busy, shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.brain_taizo_mix_play))
            }
            OutlinedButton(onClick = onSave, enabled = !item.busy && !item.saved, shape = RoundedCornerShape(20.dp)) {
                Icon(if (item.saved) Icons.Rounded.Check else Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(if (item.saved) R.string.brain_taizo_mix_saved else R.string.brain_taizo_mix_save))
            }
            if (item.busy) {
                Text(
                    text = stringResource(R.string.brain_taizo_mix_updating),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (card.chips.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                card.chips.forEach { chip ->
                    AssistChip(
                        onClick = { onChip(chip) },
                        enabled = !item.busy,
                        label = { Text(chip.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
        }
        if (card.seedsNotUsed > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.brain_taizo_mix_seed_not_used),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (card.notApplied.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.brain_taizo_mix_not_applied),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.failed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.brain_taizo_mix_failed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** "Less trap": one chip; after the tap the line says what changed and offers Undo. */
@Composable
internal fun LessChipRow(item: TaizoChatItem.Less, onTap: () -> Unit, onUndo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (item.state) {
            TaizoChatItem.LESS_DONE -> {
                Text(
                    text = stringResource(R.string.brain_taizo_less_done, item.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onUndo) { Text(stringResource(R.string.brain_taizo_less_undo)) }
            }
            else -> {
                AssistChip(
                    onClick = onTap,
                    label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
                if (item.state == TaizoChatItem.LESS_FAILED) {
                    Text(
                        text = stringResource(R.string.brain_taizo_less_failed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

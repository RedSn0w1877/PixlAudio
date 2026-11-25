package com.theveloper.pixelplay.presentation.components.taizo

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.presentation.components.redesign.BadgeShape
import com.theveloper.pixelplay.presentation.components.redesign.RedesignType
import com.theveloper.pixelplay.presentation.components.redesign.ShapeBadge
import com.theveloper.pixelplay.presentation.components.redesign.badgeTones
import com.theveloper.pixelplay.presentation.components.redesign.toComposeShape
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
    val scheme = MaterialTheme.colorScheme
    // Redesign 2 (AndroidAskTaizo "Playlist from Taizo"): surfaceContainer card, 28 dp corners,
    // 16 dp padding, 16 dp between blocks. Head: 80 dp tertiaryContainer tile with two expressive
    // shapes, 14 dp, "NEW PLAYLIST" 12/16 sp medium primary (0.5 sp), title 20/26 bold, count 14/20.
    // Preview rows: 40 dp shape art, 12 dp, title 14/20 medium over artist 13/18, 8 dp apart.
    // Actions: a connected pair 48 dp tall, 2 dp apart: Play (weight 1.3, primary, 24/8 corners)
    // and Save (weight 1, secondaryContainer, 8/24). The chips are the outlined 44 dp refine chips.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.surfaceContainer)
            .padding(16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(scheme.tertiaryContainer)
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 30.dp, y = (-26).dp)
                        .size(96.dp)
                        .clip(BadgeShape.Flower.toComposeShape())
                        .background(scheme.tertiary.copy(alpha = 0.55f))
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = 8.dp, y = (-6).dp)
                        .size(44.dp)
                        .clip(BadgeShape.Cookie9.toComposeShape())
                        .background(scheme.onTertiaryContainer)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.brain_taizo_mix_new_label).uppercase(),
                    style = RedesignType.Label.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
                    color = scheme.primary,
                )
                Text(
                    text = card.title,
                    style = RedesignType.SectionTitle.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.brain_taizo_mix_songs, card.total, card.total),
                    style = RedesignType.Label.copy(fontWeight = FontWeight.Normal),
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (card.preview.isNotEmpty()) {
            val tones = badgeTones()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.preview.forEachIndexed { i, track ->
                    val tone = tones[i % tones.size]
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ShapeBadge(
                            icon = rememberVectorPainter(Icons.Rounded.MusicNote),
                            shape = PreviewShapes[i % PreviewShapes.size],
                            containerColor = tone.container,
                            iconTint = tone.content,
                            size = 40.dp,
                            iconSize = 18.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(track.title, style = RedesignType.Label, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = RedesignType.RowSubtitle, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 8.dp, bottomEnd = 8.dp))
                    .background(if (item.busy) scheme.primary.copy(alpha = 0.38f) else scheme.primary)
                    .clickable(enabled = !item.busy, onClick = onPlay),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.brain_taizo_mix_play), style = RedesignType.Label.copy(fontSize = 15.sp), color = scheme.onPrimary)
            }
            val canSave = !item.busy && !item.saved
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 24.dp, bottomEnd = 24.dp))
                    .background(scheme.secondaryContainer)
                    .clickable(enabled = canSave, onClick = onSave),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (item.saved) Icons.Rounded.Check else Icons.Rounded.Add, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(if (item.saved) R.string.brain_taizo_mix_saved else R.string.brain_taizo_mix_save),
                    style = RedesignType.Label.copy(fontSize = 15.sp),
                    color = scheme.onSecondaryContainer,
                )
            }
        }
        if (item.busy) {
            Text(
                text = stringResource(R.string.brain_taizo_mix_updating),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        if (card.chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.chips.forEach { chip ->
                    TaizoOutlinedChip(text = chip.label, onClick = { onChip(chip) }, enabled = !item.busy)
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

private val PreviewShapes = listOf(BadgeShape.Clover4, BadgeShape.SoftBurst, BadgeShape.Cookie9)

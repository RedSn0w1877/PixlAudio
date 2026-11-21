package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.brain.MixKinds
import com.theveloper.pixelplay.presentation.viewmodel.HeroUi
import com.theveloper.pixelplay.presentation.viewmodel.HomeShelfUi
import com.theveloper.pixelplay.presentation.viewmodel.MixCardUi
import com.theveloper.pixelplay.presentation.viewmodel.StationCardUi
import com.theveloper.pixelplay.presentation.viewmodel.TileUi
import kotlinx.collections.immutable.ImmutableList

/*
 * The new Home's pieces (docs/brain-spec.md, 8.1, stage 2). Material 3 Expressive only; every list is keyed; nothing is computed in
 * composition beyond choosing a colour: the plan arrives drawn (titles, art addresses, counts) from `HomePlanStateHolder`.
 */

/** The chips of 8.1: "For you", "Mixes", "Spatial", "New". */
enum class HomeChip { FOR_YOU, MIXES, SPATIAL, NEW }

@Composable
fun HomeDateLine(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 20.dp),
    )
}

@Composable
fun HomeChipsRow(selected: HomeChip, onSelect: (HomeChip) -> Unit, modifier: Modifier = Modifier, chips: List<HomeChip> = HomeChip.entries) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips, key = { it.name }) { chip ->
            FilterChip(
                selected = chip == selected,
                onClick = { onSelect(chip) },
                label = {
                    Text(
                        stringResource(
                            when (chip) {
                                HomeChip.FOR_YOU -> R.string.home_chip_for_you
                                HomeChip.MIXES -> R.string.home_chip_mixes
                                HomeChip.SPATIAL -> R.string.home_chip_spatial
                                HomeChip.NEW -> R.string.home_chip_new
                            }
                        )
                    )
                },
            )
        }
    }
}

/** The hero (8.1 slot 1): one pick over its art. */
@Composable
fun HomeHeroCard(hero: HeroUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scrim = remember { Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))) }
    AdaptiveClickableCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).height(220.dp),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(Modifier.fillMaxSize()) {
            SmartImage(hero.art, null, Modifier.fillMaxSize(), shape = RoundedCornerShape(32.dp))
            Box(Modifier.fillMaxSize().background(scrim))
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    hero.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (hero.subtitle.isNotBlank()) {
                    Text(hero.subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xE6FFFFFF), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "Jump back in" (8.1 slot 3): the last distinct places played from, two columns of up to three. */
@Composable
fun HomeJumpBackIn(tiles: ImmutableList<TileUi>, onTile: (TileUi) -> Unit, modifier: Modifier = Modifier) {
    if (tiles.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_jump_back_in), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        for (rowStart in tiles.indices step 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in rowStart until minOf(rowStart + 2, tiles.size)) {
                    val tile = tiles[i]
                    Card(
                        onClick = { onTile(tile) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            SmartImage(tile.art, null, Modifier.size(56.dp), shape = RoundedCornerShape(16.dp))
                            Text(
                                tile.title, modifier = Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (rowStart + 1 >= tiles.size) Box(Modifier.weight(1f))
            }
        }
    }
}

/** "Made for you": the lists as cards (8.2). */
@Composable
fun HomeMixShelf(
    shelf: HomeShelfUi.Mixes,
    onOpen: (MixCardUi) -> Unit,
    modifier: Modifier = Modifier,
    /** "How it works" of the Endless Listening card's menu; null leaves the menu out. */
    onEndlessHowItWorks: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ShelfHeader(shelf.title, shelf.subtitle)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shelf.cards, key = { it.mixId }, contentType = { "mix_card" }) { card -> MixCard(card, onOpen, onEndlessHowItWorks = onEndlessHowItWorks) }
        }
    }
}

@Composable
fun MixCard(card: MixCardUi, onOpen: (MixCardUi) -> Unit, modifier: Modifier = Modifier, onEndlessHowItWorks: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val endless = card.kind == MixKinds.ENDLESS
    val tone = card.kind % 3
    val container = when (tone) { 0 -> colors.primaryContainer; 1 -> colors.secondaryContainer; else -> colors.tertiaryContainer }
    val content = when (tone) { 0 -> colors.onPrimaryContainer; 1 -> colors.onSecondaryContainer; else -> colors.onTertiaryContainer }
    AdaptiveClickableCard(
        onClick = { onOpen(card) },
        modifier = modifier.width(236.dp),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (art in card.covers) SmartImage(art, null, Modifier.size(60.dp), shape = RoundedCornerShape(18.dp))
            }
            Text(card.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(card.subtitle, style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (endless) {
                // A station has no song count: it says what it does, and its menu replays the introduction.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.endless_card_tag), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (onEndlessHowItWorks != null) EndlessCardMenu(onEndlessHowItWorks)
                }
            } else {
                Text(stringResource(R.string.home_songs_count, card.count), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The small menu of the Endless Listening card: "How it works" replays the introduction. Drawn only for that card. */
@Composable
private fun EndlessCardMenu(onHowItWorks: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.endless_card_menu), modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.endless_how_it_works)) },
                onClick = {
                    open = false
                    onHowItWorks()
                },
            )
        }
    }
}

/** "Stations for you": a genre or a mood of the listener's own library, one tap to a station (8.3). */
@Composable
fun HomeStationShelf(shelf: HomeShelfUi.Stations, onStart: (StationCardUi) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ShelfHeader(shelf.title, shelf.subtitle)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shelf.cards, key = { "${it.kind}:${it.atlasId}" }, contentType = { "station_card" }) { card ->
                AdaptiveClickableCard(
                    onClick = { onStart(card) },
                    modifier = Modifier.width(156.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        SmartImage(card.covers.firstOrNull(), null, Modifier.size(136.dp), shape = RoundedCornerShape(18.dp))
                        Text(card.name, modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.home_songs_count, card.count), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The "Mixes" chip: every made-for-you list as a big card, two to a row. */
@Composable
fun HomeAllMixes(cards: ImmutableList<MixCardUi>, onOpen: (MixCardUi) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (rowStart in cards.indices step 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (i in rowStart until minOf(rowStart + 2, cards.size)) MixCard(cards[i], onOpen, Modifier.weight(1f))
                if (rowStart + 1 >= cards.size) Box(Modifier.weight(1f))
            }
        }
    }
}

/** "Getting to know your music · 3 of 8" (8.10): shown while the first eight radios are fetched. */
@Composable
fun HomeKnowingStrip(done: Int, total: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.home_getting_to_know, done, total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

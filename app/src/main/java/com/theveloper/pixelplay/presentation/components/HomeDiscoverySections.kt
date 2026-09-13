package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.HomeMusicSection
import kotlinx.collections.immutable.ImmutableList

@Composable
fun HomeDiscoveryMixes(
    mixes: ImmutableList<HomeMusicSection>,
    isRefreshing: Boolean,
    preparingSection: String?,
    onRefresh: () -> Unit,
    onPlay: (HomeMusicSection, Song) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Made for your listening", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Mixes that change with your taste", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = onRefresh, enabled = !isRefreshing) {
                if (isRefreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Home recommendations")
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(mixes, key = { it.id }) { mix ->
                val index = mixes.indexOf(mix)
                val colors = MaterialTheme.colorScheme
                val container = when (index % 3) { 0 -> colors.primaryContainer; 1 -> colors.secondaryContainer; else -> colors.tertiaryContainer }
                val content = when (index % 3) { 0 -> colors.onPrimaryContainer; 1 -> colors.onSecondaryContainer; else -> colors.onTertiaryContainer }
                Card(
                    onClick = { mix.songs.firstOrNull()?.let { onPlay(mix, it) } },
                    enabled = preparingSection == null,
                    modifier = Modifier.width(236.dp),
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            mix.songs.take(3).forEach { song ->
                                SmartImage(song.albumArtUriString, null, Modifier.size(60.dp), shape = RoundedCornerShape(18.dp))
                            }
                        }
                        Text(mix.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(mix.subtitle, style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${mix.songs.size} songs", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            if (preparingSection == mix.id) CircularProgressIndicator(Modifier.size(28.dp), color = content, strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${mix.title}", Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeDiscoveryShelf(
    section: HomeMusicSection,
    currentSongId: String?,
    preparingSection: String?,
    onPlay: (HomeMusicSection, Song) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(section.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { section.songs.firstOrNull()?.let { onPlay(section, it) } }, enabled = preparingSection == null) {
                if (preparingSection == section.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Play all")
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(section.songs, key = { it.id }) { song ->
                val isCurrent = song.id == currentSongId
                val cardColor by animateColorAsState(
                    targetValue = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    label = "homeSongCardColor"
                )
                val cardElevation by animateDpAsState(
                    targetValue = if (isCurrent) 8.dp else 1.dp,
                    label = "homeSongCardElevation"
                )
                Card(
                    onClick = { onPlay(section, song) },
                    enabled = preparingSection == null,
                    modifier = Modifier.width(156.dp),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        SmartImage(song.albumArtUriString, null, Modifier.size(136.dp), shape = RoundedCornerShape(18.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(song.title, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        // Por qué esta canción está aquí. Dos líneas fijas: con una sola, motivos
                        // normales como "Discover more from an artist you enjoy" se cortaban a
                        // media palabra en una tarjeta de 156dp (comprobado en pantalla). Se
                        // reservan siempre, tenga motivo o no, para que las tarjetas de una misma
                        // fila no queden con alturas distintas.
                        Text(
                            text = section.reasons[song.id].orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

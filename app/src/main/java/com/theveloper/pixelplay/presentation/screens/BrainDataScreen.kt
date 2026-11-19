package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.viewmodel.BrainDataViewModel

/**
 * Settings > Developer mode > Brain data (docs/brain-spec.md, 3.7, 12): the genre atlas, what is stored about the listener's tracks,
 * each open-data source with its own switch, its requests today and the terms it is used under, and what Spotify sent for artist
 * genres. Read once when the screen opens; plain words, nothing animated, no per-frame work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainDataScreen(
    navController: NavController,
    viewModel: BrainDataViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.brain_data_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "atlas") {
                Section(stringResource(R.string.brain_data_atlas_section)) {
                    if (state.atlasLoaded) {
                        Line(stringResource(R.string.brain_data_atlas_version, state.atlasVersion, state.atlasGenres, state.atlasFamilies))
                        Line(stringResource(R.string.brain_data_atlas_described, state.atlasDescribed), dim = true)
                    } else if (!state.loading) {
                        Line(stringResource(R.string.brain_data_atlas_missing))
                    }
                }
            }
            item(key = "known") {
                Section(stringResource(R.string.brain_data_known_section)) {
                    val c = state.coverage
                    if (c.tracks == 0) {
                        Line(stringResource(R.string.brain_data_known_none))
                    } else {
                        Line(stringResource(R.string.brain_data_known_tracks, c.tracks))
                        Line(stringResource(R.string.brain_data_known_genre, c.withTags, percent(c.withTags, c.tracks)))
                        Line(stringResource(R.string.brain_data_known_year, c.withYear, percent(c.withYear, c.tracks)))
                        Line(stringResource(R.string.brain_data_known_popularity, c.withPopularity, percent(c.withPopularity, c.tracks)))
                        Line(stringResource(R.string.brain_data_known_energy, c.withEnergy, percent(c.withEnergy, c.tracks)))
                        Line(stringResource(R.string.brain_data_known_tempo, c.withTempo, percent(c.withTempo, c.tracks)))
                        Line(stringResource(R.string.brain_data_known_artists, state.artistsKnown, state.artistsWithGenres), dim = true)
                        if (state.activeMisses > 0) Line(stringResource(R.string.brain_data_known_misses, state.activeMisses), dim = true)
                    }
                }
            }
            item(key = "sources_title") {
                Text(
                    stringResource(R.string.brain_data_sources_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            items(state.sources, key = { it.host.key }) { row ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.host.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(checked = row.enabled && row.remoteAllowed, enabled = row.remoteAllowed, onCheckedChange = { viewModel.setSourceEnabled(row.host, it) })
                        }
                        Line(stringResource(R.string.brain_data_source_today, row.requestsToday, row.host.dailyCeiling))
                        Line(stringResource(R.string.brain_data_source_answered, row.tracksAnswered), dim = true)
                        Line(
                            if (row.lastRequestMs > 0) stringResource(R.string.brain_data_source_last, ago(state.nowMs, row.lastRequestMs))
                            else stringResource(R.string.brain_data_source_never),
                            dim = true,
                        )
                        if (!row.remoteAllowed) Line(stringResource(R.string.brain_data_source_remote_off))
                        Text(row.host.terms, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "spotify") {
                Section(stringResource(R.string.brain_data_spotify_section)) {
                    when {
                        !state.spotifyShapes -> Line(stringResource(R.string.brain_data_spotify_off))
                        state.spotifyArtistSeeds == 0 -> Line(stringResource(R.string.brain_data_spotify_empty))
                        state.spotifyArtistSeedsWithGenres == 0 -> Line(stringResource(R.string.brain_data_spotify_genres_none, state.spotifyArtistSeeds))
                        else -> Line(stringResource(R.string.brain_data_spotify_genres, state.spotifyArtistSeedsWithGenres, state.spotifyArtistSeeds))
                    }
                    Line(stringResource(R.string.brain_data_youtube_today, state.youTubeToday, state.youTubeLimit), dim = true)
                }
            }
            item(key = "last") {
                val note = state.lastRun
                Text(
                    text = if (note == null) stringResource(R.string.brain_data_last_run_none)
                    else stringResource(
                        R.string.brain_data_last_run, ago(state.nowMs, note.atMs), note.radios, note.artists, note.releases, note.tracksEnriched,
                        stopWord(note.stopped),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun stopWord(stop: String): String = when (stop) {
    "OFF" -> stringResource(R.string.brain_data_stop_off)
    "PLAYING" -> stringResource(R.string.brain_data_stop_playing)
    "BUDGET" -> stringResource(R.string.brain_data_stop_budget)
    "TIME" -> stringResource(R.string.brain_data_stop_time)
    else -> stringResource(R.string.brain_data_stop_done)
}

private fun percent(part: Int, whole: Int): Int = if (whole <= 0) 0 else (part * 100L / whole).toInt()

@Composable
private fun ago(nowMs: Long, thenMs: Long): String {
    val minutes = ((nowMs - thenMs) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> stringResource(R.string.brain_data_ago_now)
        minutes < 60 -> stringResource(R.string.brain_data_ago_minutes, minutes.toInt())
        minutes < 60 * 48 -> stringResource(R.string.brain_data_ago_hours, (minutes / 60).toInt())
        else -> stringResource(R.string.brain_data_ago_days, (minutes / (60 * 24)).toInt())
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Line(text: String, dim: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

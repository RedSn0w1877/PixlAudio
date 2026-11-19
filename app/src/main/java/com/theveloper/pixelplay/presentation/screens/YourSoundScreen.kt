package com.theveloper.pixelplay.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.brain.Gate
import com.theveloper.pixelplay.brain.HomePlanner
import com.theveloper.pixelplay.brain.ModelVoice
import com.theveloper.pixelplay.data.brain.StationCard
import com.theveloper.pixelplay.data.brain.YourSoundRepository
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.YourSoundViewModel
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Your sound (docs/brain-spec.md, 8.6): one scroll, a section only when there is something true to show in it, and a model line that
 * follows the model's real state ([ModelVoice]). Read when the screen opens; plain static bars, nothing animated, no per-frame work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YourSoundScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: YourSoundViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    val context = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    var openIsland by remember { mutableStateOf<YourSoundRepository.IslandRow?>(null) }
    var openFamilies by remember { mutableStateOf(setOf<Int>()) }
    val resetDone = stringResource(R.string.brain_sound_reset_done)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.brain_sound_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        val data = ui.data
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (data == null || data.isEmpty) {
                item(key = "empty") {
                    if (!ui.loading) Text(stringResource(R.string.brain_sound_empty), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (data != null) {
                if (data.islands.isNotEmpty()) item(key = "islands") {
                    Section(stringResource(R.string.brain_sound_now), stringResource(R.string.brain_sound_now_hint)) {
                        for (row in data.islands.take(6)) {
                            Bar(row.name ?: stringResource(R.string.brain_sound_island_unnamed), row.permille, onClick = { openIsland = row })
                        }
                    }
                }
                if (data.genres.isNotEmpty()) item(key = "genres") {
                    Section(stringResource(R.string.brain_sound_genres)) {
                        if (data.genreTree.isNotEmpty()) {
                            // The atlas tree: a family opens to the genres played inside it; a genre opens its page.
                            for (family in data.genreTree) {
                                val open = family.rootId in openFamilies
                                Bar(
                                    family.name, family.permille,
                                    onClick = { openFamilies = if (open) openFamilies - family.rootId else openFamilies + family.rootId },
                                )
                                if (open) for (child in family.children) {
                                    Row(Modifier.padding(start = 16.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Bar(child.name, child.permille, onClick = { navController.navigate(Screen.GenrePage.createRoute(child.atlasId)) })
                                        }
                                    }
                                }
                            }
                        } else {
                            for (row in data.genres) {
                                Bar(row.name, row.permille, onClick = { navController.navigate(Screen.GenrePage.createRoute(row.atlasId)) })
                            }
                        }
                    }
                }
                if (data.decades.isNotEmpty()) item(key = "eras") {
                    Section(stringResource(R.string.brain_sound_eras)) {
                        for (d in data.decades) Bar(stringResource(R.string.brain_sound_decade, d.decade), d.permille)
                    }
                }
                val dayparts = data.dayparts.filter { it.plays > 0 && it.leadingIsland != null }
                if (dayparts.isNotEmpty()) item(key = "day") {
                    Section(stringResource(R.string.brain_sound_day)) {
                        for (d in dayparts) {
                            Text(
                                stringResource(R.string.brain_sound_daypart_line, daypartName(d.daypart), d.leadingIsland.orEmpty()),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (data.months.size >= 2) item(key = "months") {
                    Section(stringResource(R.string.brain_sound_months)) {
                        for (m in data.months.takeLast(12).asReversed()) {
                            val label = Month.of(m.month % 12 + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + (m.month / 12)
                            Text(
                                stringResource(R.string.brain_sound_month_line, label, m.plays),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val top = m.islands.firstOrNull()
                            if (top != null && top.first != null) {
                                Text(top.first.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (!data.lately.isEmpty) item(key = "lately") {
                    Section(stringResource(R.string.brain_sound_lately)) {
                        if (data.lately.up.isNotEmpty()) Text(stringResource(R.string.brain_sound_lately_up, data.lately.up.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
                        if (data.lately.down.isNotEmpty()) Text(stringResource(R.string.brain_sound_lately_down, data.lately.down.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val novelty = data.novelty
                if (novelty != null) item(key = "newfam") {
                    Section(stringResource(R.string.brain_sound_newfam)) {
                        Text(stringResource(R.string.brain_sound_novelty, novelty.permille / 10), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!data.isEmpty) item(key = "model") {
                    Section(stringResource(R.string.brain_sound_model)) { ModelLines(data) }
                }
            }
            item(key = "controls") {
                Section(stringResource(R.string.brain_sound_controls)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.brain_sound_reset), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.brain_sound_reset_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.brain_sound_reset_confirm)) }
                    }
                    TextButton(onClick = { navController.navigate(Screen.TasteChanges.route) }) {
                        Text(stringResource(R.string.taste_changes_title))
                    }
                }
            }
            item(key = "footer") {
                Text(
                    stringResource(R.string.brain_sound_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }

    val island = openIsland
    if (island != null) {
        val moreDone = stringResource(R.string.brain_genre_more_done, island.genreName.orEmpty())
        val lessDone = stringResource(R.string.brain_genre_less_done, island.genreName.orEmpty())
        val failed = stringResource(R.string.brain_genre_adjust_failed)
        val nothing = stringResource(R.string.brain_genre_nothing)
        AlertDialog(
            onDismissRequest = { openIsland = null },
            title = { Text(island.name ?: stringResource(R.string.brain_sound_island_unnamed)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (island.topArtists.isNotEmpty()) {
                        Text(stringResource(R.string.brain_sound_island_artists), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        for (a in island.topArtists) Text(a, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (island.topSongs.isNotEmpty()) {
                        Text(stringResource(R.string.brain_sound_island_songs), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        for (s in island.topSongs) Text(s, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val genre = island.genreName
                    if (genre != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.adjustGenre(genre, true) { ok -> Toast.makeText(context, if (ok) moreDone else failed, Toast.LENGTH_SHORT).show() } }) {
                                Text(stringResource(R.string.brain_sound_island_more))
                            }
                            TextButton(onClick = { viewModel.adjustGenre(genre, false) { ok -> Toast.makeText(context, if (ok) lessDone else failed, Toast.LENGTH_SHORT).show() } }) {
                                Text(stringResource(R.string.brain_sound_island_less))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val genre = island.genreName
                if (genre != null) TextButton(onClick = {
                    openIsland = null
                    playerViewModel.startGenreStation(StationCard.KIND_GENRE, island.genreId, genre) { Toast.makeText(context, nothing, Toast.LENGTH_SHORT).show() }
                }) { Text(stringResource(R.string.brain_sound_island_play_station)) }
            },
            dismissButton = { TextButton(onClick = { openIsland = null }) { Text(stringResource(R.string.brain_sound_island_close)) } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.brain_sound_reset_title)) },
            text = { Text(stringResource(R.string.brain_sound_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetLearned { Toast.makeText(context, resetDone, Toast.LENGTH_SHORT).show() }
                }) { Text(stringResource(R.string.brain_sound_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** The model's lines: only what is true of its state right now (see [ModelVoice]); a figure only when it was computed from graded plays. */
@Composable
private fun ModelLines(data: YourSoundRepository.State) {
    val m = data.model
    when (m.kind) {
        ModelVoice.GETTING_TO_KNOW -> {
            Text(stringResource(R.string.brain_sound_model_getting), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.brain_sound_model_getting_count, m.gradedPlays, Gate.MIN_PLAYS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ModelVoice.STANDARD_RECIPE -> {
            Text(stringResource(R.string.brain_sound_model_standard), style = MaterialTheme.typography.bodyLarge)
            m.accPriorPercent?.let { Text(stringResource(R.string.brain_sound_model_standard_acc, it), style = MaterialTheme.typography.bodySmall) }
        }
        else -> {
            Text(stringResource(R.string.brain_sound_model_learning, m.learnedFrom.toInt()), style = MaterialTheme.typography.bodyLarge)
            m.accNowPercent?.let { Text(stringResource(R.string.brain_sound_model_acc, it), style = MaterialTheme.typography.bodyMedium) }
            m.accBeforePercent?.let { Text(stringResource(R.string.brain_sound_model_acc_before, it), style = MaterialTheme.typography.bodySmall) }
            m.accPriorPercent?.let { Text(stringResource(R.string.brain_sound_model_acc_prior, it), style = MaterialTheme.typography.bodySmall) }
        }
    }
    val control = data.control
    if (control != null && m.kind != ModelVoice.GETTING_TO_KNOW) {
        val a = control.controlPermille
        val b = control.learnedPermille
        Text(
            if (a != null && b != null) stringResource(R.string.brain_sound_control, a / 10, b / 10)
            else stringResource(R.string.brain_sound_control_waiting, Gate.CONTROL_MIN_PLAYS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun daypartName(daypart: Int): String = stringResource(
    when (daypart) {
        HomePlanner.DAYPART_MORNING -> R.string.brain_sound_daypart_morning
        HomePlanner.DAYPART_AFTERNOON -> R.string.brain_sound_daypart_afternoon
        HomePlanner.DAYPART_EVENING -> R.string.brain_sound_daypart_evening
        else -> R.string.brain_sound_daypart_night
    }
)

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** A name with its share as a static bar. */
@Composable
private fun Bar(label: String, permille: Int, onClick: (() -> Unit)? = null) {
    val mod = if (onClick != null) Modifier.fillMaxWidth().clickableRow(onClick) else Modifier.fillMaxWidth()
    Column(mod, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.brain_sound_share, permille / 10), style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(progress = { (permille / 1000f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

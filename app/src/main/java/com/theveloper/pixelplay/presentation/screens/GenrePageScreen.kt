package com.theveloper.pixelplay.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.brain.StationCard
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.viewmodel.GenrePageViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel

/**
 * A genre page (docs/brain-spec.md, 8.7), built from the atlas alone: its name, where and when it began, the atlas's own paragraph for the
 * genres it describes, and its family as links. Nothing is guessed and nothing is asked of an assistant at run time.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GenrePageScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: GenrePageViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val facts = ui.facts
    val context = LocalContext.current
    val nothing = stringResource(R.string.brain_genre_nothing)
    val extras = ui.extras
    val moreDone = stringResource(R.string.brain_genre_more_done, facts?.name.orEmpty())
    val lessDone = stringResource(R.string.brain_genre_less_done, facts?.name.orEmpty())
    val failed = stringResource(R.string.brain_genre_adjust_failed)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(facts?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        if (facts == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "hero") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(facts.name, style = MaterialTheme.typography.displaySmall)
                    facts.origin?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    facts.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            item(key = "actions") {
                Button(
                    onClick = {
                        playerViewModel.startGenreStation(StationCard.KIND_GENRE, facts.id, facts.name) {
                            Toast.makeText(context, nothing, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.brain_genre_play_station)) }
            }
            item(key = "adjust") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.adjust(true) { ok -> Toast.makeText(context, if (ok) moreDone else failed, Toast.LENGTH_SHORT).show() } },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.brain_genre_more)) }
                    OutlinedButton(
                        onClick = { viewModel.adjust(false) { ok -> Toast.makeText(context, if (ok) lessDone else failed, Toast.LENGTH_SHORT).show() } },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.brain_genre_less)) }
                }
            }
            if (extras != null) {
                extras.you?.let { you ->
                    item(key = "you") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.brain_genre_you), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.brain_genre_you_first, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(you.firstPlayedMs))),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(stringResource(R.string.brain_genre_you_share, (you.permille + 5) / 10, you.plays), style = MaterialTheme.typography.bodyMedium)
                            if (you.topSongs.isNotEmpty()) {
                                Text(stringResource(R.string.brain_genre_you_top), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                for (s in you.topSongs) Text(s, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                namesItem("key_artists", R.string.brain_genre_key_artists, extras.keyArtists)
                namesItem("library_artists", R.string.brain_genre_from_library, extras.libraryArtists)
            }
            familyItem("parents", R.string.brain_genre_comes_from, facts.parents, navController)
            familyItem("children", R.string.brain_genre_branches, facts.children, navController)
            familyItem("related", R.string.brain_genre_close_to, facts.related, navController)
            familyItem("influences", R.string.brain_genre_shaped_by, facts.influences, navController)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.namesItem(key: String, title: Int, names: List<String>) {
    if (names.isEmpty()) return
    item(key = key) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            for (n in names) Text(n, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.familyItem(
    key: String,
    title: Int,
    links: List<GenrePageViewModel.Link>,
    navController: NavController,
) {
    if (links.isEmpty()) return
    item(key = key) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (link in links.take(MAX_LINKS)) {
                    AssistChip(onClick = { navController.navigate(Screen.GenrePage.createRoute(link.id)) }, label = { Text(link.name) })
                }
            }
        }
    }
}

private const val MAX_LINKS = 30

package com.theveloper.pixelplay.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
            familyItem("parents", R.string.brain_genre_comes_from, facts.parents, navController)
            familyItem("children", R.string.brain_genre_branches, facts.children, navController)
            familyItem("related", R.string.brain_genre_close_to, facts.related, navController)
            familyItem("influences", R.string.brain_genre_shaped_by, facts.influences, navController)
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

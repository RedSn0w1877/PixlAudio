package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.viewmodel.ArtistPickerViewModel

/** "Pick three artists you love" (docs/brain-spec.md, 8.10): the way in for a thin or empty library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistPickerScreen(
    navController: NavController,
    viewModel: ArtistPickerViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.done) { if (ui.done) navController.popBackStack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pick_artists_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = viewModel::confirm,
                enabled = ui.selected.isNotEmpty() && !ui.starting,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(
                    if (ui.starting) stringResource(R.string.pick_artists_starting)
                    else stringResource(R.string.pick_artists_start, ui.selected.size)
                )
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.pick_artists_subtitle),
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = ui.query,
                onValueChange = viewModel::onQuery,
                singleLine = true,
                label = { Text(stringResource(R.string.pick_artists_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (ui.failed) {
                Text(stringResource(R.string.pick_artists_failed), modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(ui.results, key = { it.name }, contentType = { "artist" }) { artist ->
                    val isSelected = artist.name in ui.selected
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.toggle(artist.name) }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        SmartImage(artist.art, null, Modifier.size(52.dp), shape = CircleShape)
                        Text(artist.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (isSelected) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

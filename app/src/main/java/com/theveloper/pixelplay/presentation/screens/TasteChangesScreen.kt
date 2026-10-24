package com.theveloper.pixelplay.presentation.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NotInterested
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.recommendation.TasteAdjustment
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentMath
import com.theveloper.pixelplay.data.recommendation.TasteKind
import com.theveloper.pixelplay.data.recommendation.TasteSetBy
import com.theveloper.pixelplay.data.recommendation.TasteTarget
import com.theveloper.pixelplay.presentation.viewmodel.TasteChangesViewModel

/**
 * Settings › Library › Ask Taizo's taste changes: every boost, reduction, mute and block in force with the
 * reason it was made, a Remove button on each and Clear all. Nothing here deletes songs or history; removing a
 * change just takes it back out of the recommendations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasteChangesScreen(
    navController: NavController,
    viewModel: TasteChangesViewModel = hiltViewModel(),
) {
    val changes by viewModel.adjustments.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.taste_changes_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (changes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = stringResource(R.string.taste_changes_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.taste_changes_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item(key = "intro") {
                        Text(
                            text = stringResource(R.string.taste_changes_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(items = changes, key = { it.id }) { change ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth(),
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            headlineContent = { Text(change.label) },
                            supportingContent = { Text(supportingLine(change)) },
                            leadingContent = { Icon(iconFor(change.kind), contentDescription = null) },
                            trailingContent = {
                                IconButton(onClick = { viewModel.remove(change.id) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.taste_changes_remove))
                                }
                            }
                        )
                    }
                }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                ) {
                    Text(stringResource(R.string.taste_changes_clear_all))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.taste_changes_clear_title)) },
            text = { Text(stringResource(R.string.taste_changes_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearAll()
                }) { Text(stringResource(R.string.taste_changes_clear_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

private fun iconFor(kind: TasteKind): ImageVector = when (kind) {
    TasteKind.BOOST -> Icons.Rounded.ArrowUpward
    TasteKind.REDUCE -> Icons.Rounded.ArrowDownward
    TasteKind.MUTE -> Icons.Rounded.NotInterested
    TasteKind.BLOCK -> Icons.Rounded.Block
}

/** "Boosted (x1.6 more) · Artist · Set by Taizo · 3 days ago", then the reason when there is one. */
@Composable
private fun supportingLine(change: TasteAdjustment): String {
    val kind = when (change.kind) {
        TasteKind.BOOST -> stringResource(R.string.taste_kind_boost, TasteAdjustmentMath.describe(change.kind, change.strength))
        TasteKind.REDUCE -> stringResource(R.string.taste_kind_reduce, TasteAdjustmentMath.describe(change.kind, change.strength))
        TasteKind.MUTE -> stringResource(R.string.taste_kind_mute)
        TasteKind.BLOCK -> stringResource(R.string.taste_kind_block)
    }
    val target = when (change.target) {
        TasteTarget.ARTIST -> stringResource(R.string.taste_target_artist)
        TasteTarget.SONG -> stringResource(R.string.taste_target_song)
        TasteTarget.GENRE -> stringResource(R.string.taste_target_genre)
        TasteTarget.ALBUM -> stringResource(R.string.taste_target_album)
    }
    val by = when (change.setBy) {
        TasteSetBy.TAIZO -> stringResource(R.string.taste_set_by_taizo)
        TasteSetBy.USER -> stringResource(R.string.taste_set_by_user)
    }
    val age = if (change.createdAtMs > 0L) {
        DateUtils.getRelativeTimeSpanString(
            change.createdAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    } else {
        null
    }
    val head = listOfNotNull(kind, target, by, age).joinToString(" · ")
    return if (change.reason.isBlank()) head else head + "\n" + change.reason
}

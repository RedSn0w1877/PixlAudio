package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.service.player.AutoplayUiState

/**
 * The Autoplay line of the queue (docs/brain-spec.md, 8.4): everything under it was picked by the
 * engine, and the switch turns the picking off. Shown above the first engine item, or at the end of
 * the queue while there is none yet.
 */
@Composable
fun AutoplayDivider(
    state: AutoplayUiState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.queue_autoplay_title)
    val subtitle = stringResource(
        when {
            !state.enabled -> R.string.queue_autoplay_off
            state.finding -> R.string.queue_autoplay_finding
            state.changedDirection -> R.string.queue_autoplay_changed_direction
            state.fromLibrary -> R.string.queue_autoplay_from_library
            else -> R.string.queue_autoplay_on
        }
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = state.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

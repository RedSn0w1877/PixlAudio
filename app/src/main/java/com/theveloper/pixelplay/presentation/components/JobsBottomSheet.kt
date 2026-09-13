package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.viewmodel.PixelPlayJob

/** List of everything currently rendering/syncing/matching in the background — see [PixelPlayJob]. */
@Composable
fun JobsBottomSheet(jobs: List<PixelPlayJob>) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.jobs_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (jobs.isEmpty()) {
            Text(
                text = stringResource(R.string.jobs_sheet_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.padding(bottom = 12.dp)) {
                items(jobs, key = { it.id }) { job ->
                    JobRow(job)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.JobRow(job: PixelPlayJob) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (job.percent != null) {
            CircularProgressIndicator(
                progress = { job.percent / 100f },
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp
            )
        } else if (job.state == WorkInfo.State.RUNNING) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        } else {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = job.kind.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            val subtitle = job.detail
                ?: if (job.state == WorkInfo.State.ENQUEUED) {
                    stringResource(R.string.jobs_sheet_queued)
                } else null
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (job.percent != null) {
                LinearProgressIndicator(
                    progress = { job.percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }
        }
    }
}

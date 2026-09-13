package com.theveloper.pixelplay.presentation.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.update.AppUpdateState
import com.theveloper.pixelplay.data.update.pendingUpdate
import com.theveloper.pixelplay.presentation.viewmodel.AppUpdateViewModel
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@Composable
fun AppUpdateCard(
    modifier: Modifier = Modifier,
    viewModel: AppUpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val update = state.pendingUpdate

    // Una sola llamada a la API al abrir la pantalla; el límite sin cuenta es de 60 por hora.
    LaunchedEffect(Unit) {
        if (viewModel.state.value is AppUpdateState.Idle) viewModel.checkForUpdates()
    }

    val status = when (val s = state) {
        AppUpdateState.Idle -> stringResource(R.string.app_update_status_current, viewModel.installedVersionName)
        AppUpdateState.Checking -> stringResource(R.string.app_update_status_checking)
        AppUpdateState.UpToDate -> stringResource(R.string.app_update_status_up_to_date, viewModel.installedVersionName)
        is AppUpdateState.Available -> stringResource(
            R.string.app_update_status_available,
            s.update.versionName,
            Formatter.formatShortFileSize(context, s.update.sizeBytes),
        )
        is AppUpdateState.Downloading -> if (s.percent != null) {
            stringResource(R.string.app_update_status_downloading_percent, s.update.versionName, s.percent)
        } else {
            stringResource(R.string.app_update_status_downloading, s.update.versionName)
        }
        is AppUpdateState.ReadyToInstall -> stringResource(R.string.app_update_status_ready, s.update.versionName)
        is AppUpdateState.Installing -> stringResource(R.string.app_update_status_installing)
        is AppUpdateState.Failed -> s.message
    }

    Surface(
        modifier = modifier,
        shape = AbsoluteSmoothCornerShape(22.dp, 60),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (update != null) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(22.dp),
                        tint = if (update != null) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_update_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state is AppUpdateState.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val downloading = state as? AppUpdateState.Downloading
            if (downloading != null) {
                val percent = downloading.percent
                if (percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (update != null && update.releaseNotes.isNotBlank() && state !is AppUpdateState.Installing) {
                Text(
                    text = update.releaseNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (update != null && !viewModel.canSelfUpdate) {
                Text(
                    text = stringResource(R.string.app_update_test_build_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (update != null && update.releaseUrl.isNotBlank()) {
                    TextButton(onClick = { runCatching { uriHandler.openUri(update.releaseUrl) } }) {
                        Text(stringResource(R.string.app_update_action_release_page))
                    }
                }
                when {
                    state is AppUpdateState.Checking -> FilledTonalButton(onClick = {}, enabled = false) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.app_update_action_checking))
                    }
                    update == null -> FilledTonalButton(onClick = viewModel::checkForUpdates) {
                        Text(stringResource(R.string.app_update_action_check))
                    }
                    !viewModel.canSelfUpdate -> Unit
                    state is AppUpdateState.Available -> Button(onClick = viewModel::downloadAndInstall) {
                        Text(
                            stringResource(
                                R.string.app_update_action_download_install,
                                Formatter.formatShortFileSize(context, update.sizeBytes),
                            )
                        )
                    }
                    state is AppUpdateState.ReadyToInstall -> Button(onClick = viewModel::downloadAndInstall) {
                        Text(stringResource(R.string.app_update_action_install))
                    }
                    state is AppUpdateState.Failed -> Button(onClick = viewModel::downloadAndInstall) {
                        Text(stringResource(R.string.app_update_action_retry))
                    }
                    else -> Unit
                }
            }
        }
    }
}

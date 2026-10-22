package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.CloudStudioViewModel

/**
 * Settings › Sing › Cloud: the two doors into cloud processing, the Cloud processing screen (the
 * on/off consent and Wi-Fi switches) and the Cloud queue (what was sent and what came back).
 * They used to hide in Developer › Experimental.
 */
@Composable
fun SettingsSingCloudSection(
    navController: NavController,
    viewModel: CloudStudioViewModel = hiltViewModel(),
) {
    val cloudSettings by viewModel.settings.collectAsStateWithLifecycle()
    val cloudState by viewModel.engineState.collectAsStateWithLifecycle()
    val queueSummary = remember(cloudState.jobs) { viewModel.summaryLine() }
    val tint = MaterialTheme.colorScheme.secondary
    val chevron = @Composable {
        Icon(
            Icons.Rounded.ChevronRight,
            stringResource(R.string.settings_cd_open),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Column {
        SettingsSubsection(title = stringResource(R.string.settings_sing_cloud_section), addBottomSpace = false) {
            SettingsItem(
                title = stringResource(R.string.settings_sing_cloud_processing_title),
                subtitle = stringResource(
                    if (cloudSettings.enabled) R.string.settings_sing_cloud_processing_on
                    else R.string.settings_sing_cloud_processing_off
                ),
                leadingIcon = { Icon(Icons.Rounded.CloudUpload, null, tint = tint) },
                trailingIcon = chevron,
                onClick = { navController.navigateSafely(Screen.CloudProcessing.route) }
            )
            SettingsItem(
                title = stringResource(R.string.settings_sing_cloud_queue_title),
                subtitle = queueSummary ?: stringResource(R.string.settings_sing_cloud_queue_default),
                leadingIcon = { Icon(painterResource(R.drawable.rounded_queue_music_24), null, tint = tint) },
                trailingIcon = chevron,
                onClick = { navController.navigateSafely(Screen.CloudQueue.route) }
            )
        }
    }
}

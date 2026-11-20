package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PhonelinkRing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.model.LocalDeveloperMode
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.SongTapSettingsViewModel

/**
 * Settings > Song Tap: "Allow Song Tap" (off until turned on, here or from the player's menu) and the way
 * into the screen. Kept in its own file so the category screen only gains one item.
 */
@Composable
fun SettingsSongTap(
    navController: NavController,
    viewModel: SongTapSettingsViewModel = hiltViewModel(),
) {
    val allowed by viewModel.allowed.collectAsStateWithLifecycle()
    Column {
        SettingsSubsection(title = stringResource(R.string.songtap_title)) {
            SwitchSettingItem(
                title = stringResource(R.string.songtap_allow_title),
                subtitle = stringResource(R.string.songtap_allow_subtitle),
                checked = allowed,
                onCheckedChange = viewModel::setAllowed,
                leadingIcon = { Icon(Icons.Rounded.PhonelinkRing, null, tint = MaterialTheme.colorScheme.secondary) },
            )
            SettingsItem(
                title = stringResource(R.string.songtap_settings_open),
                subtitle = stringResource(R.string.songtap_settings_open_subtitle),
                leadingIcon = { Icon(Icons.Rounded.PhonelinkRing, null, tint = MaterialTheme.colorScheme.secondary) },
                trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                onClick = { navController.navigateSafely(Screen.SongTap.route) },
            )
            // Calibration numbers for the tap detector: only with Developer mode on.
            if (LocalDeveloperMode.current) {
                SettingsItem(
                    title = stringResource(R.string.songtap_diag_title),
                    subtitle = stringResource(R.string.songtap_diag_settings_subtitle),
                    leadingIcon = { Icon(Icons.Rounded.PhonelinkRing, null, tint = MaterialTheme.colorScheme.secondary) },
                    trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    onClick = { navController.navigateSafely(Screen.SongTapDiagnostics.route) },
                )
            }
        }
    }
}

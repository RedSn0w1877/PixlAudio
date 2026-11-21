package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.PhonelinkRing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.model.LocalDeveloperMode
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.SongTapSettingsViewModel
import com.theveloper.pixelplay.songtap.SongTapNames
import com.theveloper.pixelplay.songtap.SongTapSensitivity
import com.theveloper.pixelplay.songtap.SongTapText

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
    val name by viewModel.name.collectAsStateWithLifecycle()
    val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()
    var editingName by remember { mutableStateOf(false) }
    var editingSensitivity by remember { mutableStateOf(false) }
    if (editingSensitivity) {
        SensitivityDialog(
            current = sensitivity,
            onPick = { viewModel.setSensitivity(it); editingSensitivity = false },
            onDismiss = { editingSensitivity = false },
        )
    }
    if (editingName) {
        NameDialog(
            current = name,
            onSave = { viewModel.setName(it); editingName = false },
            onUseDevice = { viewModel.setName(""); editingName = false },
            onDismiss = { editingName = false },
        )
    }
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
                title = stringResource(R.string.songtap_name_title),
                subtitle = stringResource(R.string.songtap_name_subtitle, name),
                leadingIcon = { Icon(Icons.Rounded.Badge, null, tint = MaterialTheme.colorScheme.secondary) },
                trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                onClick = { editingName = true },
            )
            SettingsItem(
                title = stringResource(R.string.songtap_sensitivity_title),
                subtitle = stringResource(R.string.songtap_sensitivity_subtitle, stringResource(sensitivityLabelRes(sensitivity))),
                leadingIcon = { Icon(Icons.Rounded.Speed, null, tint = MaterialTheme.colorScheme.secondary) },
                trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                onClick = { editingSensitivity = true },
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
                SettingsItem(
                    title = stringResource(R.string.ringdiag_title),
                    subtitle = stringResource(R.string.ringdiag_settings_subtitle),
                    leadingIcon = { Icon(Icons.Rounded.PhonelinkRing, null, tint = MaterialTheme.colorScheme.secondary) },
                    trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    onClick = { navController.navigateSafely(Screen.RingCodeDiagnostics.route) },
                )
            }
        }
    }
}

/** "Your name": what other phones show for this one. 1 to 20 characters; control and direction characters are dropped as it is saved. */
@Composable
private fun NameDialog(current: String, onSave: (String) -> Unit, onUseDevice: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val clean = SongTapNames.clean(text)
    val count = SongTapText.toCodePoints(text.trim()).size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.songtap_name_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.songtap_name_dialog_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(SongTapNames.MAX_CHARS * 2) },
                    singleLine = true,
                    isError = clean.isEmpty() || count > SongTapNames.MAX_CHARS,
                    supportingText = { Text(stringResource(R.string.songtap_name_counter, minOf(count, SongTapNames.MAX_CHARS), SongTapNames.MAX_CHARS)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = clean.isNotEmpty()) { Text(stringResource(R.string.songtap_name_save)) } },
        dismissButton = {
            Column {
                TextButton(onClick = onUseDevice) { Text(stringResource(R.string.songtap_name_use_device)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.songtap_card_not_now)) }
            }
        },
    )
}

private fun sensitivityLabelRes(option: SongTapSensitivity): Int =
    if (option == SongTapSensitivity.HIGH) R.string.songtap_sensitivity_high else R.string.songtap_sensitivity_normal

/** "Sensitivity": Normal (a firmer touch) or High (a smaller, quicker one, the default). Applied at once, also to a phone that is armed. */
@Composable
private fun SensitivityDialog(current: SongTapSensitivity, onPick: (SongTapSensitivity) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.songtap_sensitivity_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.songtap_sensitivity_dialog_body), style = MaterialTheme.typography.bodyMedium)
                for (option in SongTapSensitivity.entries) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = option == current, onClick = { onPick(option) })
                        TextButton(onClick = { onPick(option) }) { Text(stringResource(sensitivityLabelRes(option))) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.songtap_card_not_now)) } },
    )
}

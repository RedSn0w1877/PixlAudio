package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.preferences.MusicHapticsEngineChoice
import com.theveloper.pixelplay.data.preferences.MusicHapticsFollows
import com.theveloper.pixelplay.data.preferences.MusicHapticsSettings
import com.theveloper.pixelplay.data.preferences.PlayerGesture
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.PlayerExtrasSettingsViewModel
import kotlin.math.roundToInt

/**
 * Settings > Playback: Blocked artists, Gestures and Music haptics. Kept in its own composable (with
 * its own small view model) so the 3000-line category screen only gains one item.
 */
@Composable
fun PlaybackExtrasSettings(
    navController: NavController,
    viewModel: PlayerExtrasSettingsViewModel = hiltViewModel(),
) {
    val gestures by viewModel.gestures.collectAsStateWithLifecycle()
    val haptics by viewModel.musicHaptics.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedCount.collectAsStateWithLifecycle()
    val tint = MaterialTheme.colorScheme.secondary

    Column {
        ExtrasSubsection(title = stringResource(R.string.settings_blocked_artists_title)) {
            SettingsItem(
                title = stringResource(R.string.settings_blocked_artists_title),
                subtitle = if (blockedCount == 0) stringResource(R.string.settings_blocked_artists_none)
                else pluralStringResource(R.plurals.settings_blocked_artists_count, blockedCount, blockedCount),
                leadingIcon = { Icon(Icons.Rounded.Block, null, tint = tint) },
                trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                onClick = { navController.navigateSafely(Screen.BlockedArtists.route) }
            )
        }

        ExtrasSubsection(title = stringResource(R.string.settings_gestures_section)) {
            GestureSwitch(R.string.settings_gesture_player_swipe_title, R.string.settings_gesture_player_swipe_subtitle,
                gestures.playerSwipeSkip) { viewModel.setGesture(PlayerGesture.PLAYER_SWIPE_SKIP, it) }
            GestureSwitch(R.string.settings_gesture_player_double_tap_title, R.string.settings_gesture_player_double_tap_subtitle,
                gestures.playerDoubleTapLike) { viewModel.setGesture(PlayerGesture.PLAYER_DOUBLE_TAP_LIKE, it) }
            GestureSwitch(R.string.settings_gesture_player_long_press_title, R.string.settings_gesture_player_long_press_subtitle,
                gestures.playerLongPressActions) { viewModel.setGesture(PlayerGesture.PLAYER_LONG_PRESS, it) }
            GestureSwitch(R.string.settings_gesture_mini_swipe_title, R.string.settings_gesture_mini_swipe_subtitle,
                gestures.miniSwipeSkip) { viewModel.setGesture(PlayerGesture.MINI_SWIPE_SKIP, it) }
            GestureSwitch(R.string.settings_gesture_mini_swipe_up_title, R.string.settings_gesture_mini_swipe_up_subtitle,
                gestures.miniSwipeUpOpen) { viewModel.setGesture(PlayerGesture.MINI_SWIPE_UP, it) }
            GestureSwitch(R.string.settings_gesture_mini_long_press_title, R.string.settings_gesture_mini_long_press_subtitle,
                gestures.miniLongPressActions) { viewModel.setGesture(PlayerGesture.MINI_LONG_PRESS, it) }
            GestureSwitch(R.string.settings_gesture_row_swipe_title, R.string.settings_gesture_row_swipe_subtitle,
                gestures.rowSwipeQueue) { viewModel.setGesture(PlayerGesture.ROW_SWIPE_QUEUE, it) }
        }

        ExtrasSubsection(title = stringResource(R.string.settings_music_haptics_section), addBottomSpace = false) {
            val supported = viewModel.hapticsSupported
            SwitchSettingItem(
                title = stringResource(R.string.settings_music_haptics_title),
                subtitle = if (supported) stringResource(R.string.settings_music_haptics_subtitle)
                else stringResource(R.string.settings_music_haptics_unsupported),
                checked = haptics.enabled && supported,
                onCheckedChange = { viewModel.setHapticsEnabled(it) },
                leadingIcon = { Icon(Icons.Rounded.Vibration, null, tint = tint) },
                enabled = supported
            )
            if (supported && haptics.enabled) {
                // The slider drags a local value and saves once, on release.
                var dragging by remember { mutableStateOf<Float?>(null) }
                SliderSettingsItem(
                    label = stringResource(R.string.settings_music_haptics_strength_title),
                    value = dragging ?: haptics.strengthPercent.toFloat(),
                    valueRange = MusicHapticsSettings.MIN_STRENGTH.toFloat()..MusicHapticsSettings.MAX_STRENGTH.toFloat(),
                    steps = (MusicHapticsSettings.MAX_STRENGTH - MusicHapticsSettings.MIN_STRENGTH) / 10 - 1,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { viewModel.setHapticsStrength((it / 10f).roundToInt() * 10) }
                        dragging = null
                    },
                    valueText = { "${it.roundToInt()}%" }
                )
                ThemeSelectorItem(
                    label = stringResource(R.string.settings_music_haptics_follows_title),
                    description = stringResource(R.string.settings_music_haptics_follows_subtitle),
                    options = linkedMapOf(
                        MusicHapticsFollows.EVERYTHING.key to stringResource(R.string.settings_music_haptics_follows_everything),
                        MusicHapticsFollows.BEAT.key to stringResource(R.string.settings_music_haptics_follows_beat),
                        MusicHapticsFollows.BASS.key to stringResource(R.string.settings_music_haptics_follows_bass),
                    ),
                    selectedKey = haptics.follows.key,
                    onSelectionChanged = { viewModel.setHapticsFollows(MusicHapticsFollows.fromKey(it)) },
                    leadingIcon = { Icon(Icons.Rounded.TouchApp, null, tint = tint) }
                )
                if (viewModel.systemGeneratorAvailable) {
                    ThemeSelectorItem(
                        label = stringResource(R.string.settings_music_haptics_engine_title),
                        description = stringResource(R.string.settings_music_haptics_engine_subtitle),
                        options = linkedMapOf(
                            MusicHapticsEngineChoice.APP.key to stringResource(R.string.settings_music_haptics_engine_app),
                            MusicHapticsEngineChoice.SYSTEM.key to stringResource(R.string.settings_music_haptics_engine_system),
                        ),
                        selectedKey = haptics.engine.key,
                        onSelectionChanged = { viewModel.setHapticsEngine(MusicHapticsEngineChoice.fromKey(it)) },
                        leadingIcon = { Icon(Icons.Rounded.Vibration, null, tint = tint) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GestureSwitch(titleRes: Int, subtitleRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchSettingItem(
        title = stringResource(titleRes),
        subtitle = stringResource(subtitleRes),
        checked = checked,
        onCheckedChange = onChange,
        leadingIcon = { Icon(Icons.Rounded.TouchApp, null, tint = MaterialTheme.colorScheme.secondary) }
    )
}

@Composable
private fun ExtrasSubsection(title: String, addBottomSpace: Boolean = true, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        content()
    }
    if (addBottomSpace) Spacer(modifier = Modifier.height(10.dp))
}

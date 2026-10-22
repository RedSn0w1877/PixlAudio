package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.preferences.MusicHapticsEngineChoice
import com.theveloper.pixelplay.data.preferences.MusicHapticsFollows
import com.theveloper.pixelplay.data.preferences.MusicHapticsSettings
import com.theveloper.pixelplay.data.preferences.PlayerGesture
import com.theveloper.pixelplay.presentation.model.LocalDeveloperMode
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.PlayerExtrasSettingsViewModel
import kotlin.math.roundToInt

/**
 * Settings › Library & storage: the Blocked artists row. Lives next to the gestures section
 * because both read the small [PlayerExtrasSettingsViewModel], so the 3000-line category screen
 * only gains one item each.
 */
@Composable
fun SettingsBlockedArtistsSection(
    navController: NavController,
    viewModel: PlayerExtrasSettingsViewModel = hiltViewModel(),
) {
    val blockedCount by viewModel.blockedCount.collectAsStateWithLifecycle()

    Column {
        SettingsSubsection(title = stringResource(R.string.settings_blocked_artists_title)) {
            SettingsItem(
                title = stringResource(R.string.settings_blocked_artists_title),
                subtitle = if (blockedCount == 0) stringResource(R.string.settings_blocked_artists_none)
                else pluralStringResource(R.plurals.settings_blocked_artists_count, blockedCount, blockedCount),
                leadingIcon = { Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.secondary) },
                trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                onClick = { navController.navigateSafely(Screen.BlockedArtists.route) }
            )
        }
    }
}

/**
 * Settings › Gestures & haptics: full player, mini player and song-list gestures, the haptic
 * switches, and (Developer mode only) the music-haptics engine under Advanced. The two
 * switches that live in [com.theveloper.pixelplay.presentation.viewmodel.SettingsViewModel] come
 * in as parameters; the rest read the small [PlayerExtrasSettingsViewModel].
 */
@Composable
fun SettingsGesturesAndHaptics(
    tapBackgroundClosesPlayer: Boolean,
    onTapBackgroundClosesPlayer: (Boolean) -> Unit,
    hapticFeedbackEnabled: Boolean,
    onHapticFeedbackEnabled: (Boolean) -> Unit,
    viewModel: PlayerExtrasSettingsViewModel = hiltViewModel(),
) {
    val developerMode = LocalDeveloperMode.current
    val gestures by viewModel.gestures.collectAsStateWithLifecycle()
    val haptics by viewModel.musicHaptics.collectAsStateWithLifecycle()
    val supported = viewModel.hapticsSupported
    val tint = MaterialTheme.colorScheme.secondary

    Column {
        SettingsSubsection(title = stringResource(R.string.settings_gestures_full_player_section)) {
            GestureSwitch(R.string.settings_gesture_player_swipe_title, R.string.settings_gesture_player_swipe_subtitle,
                gestures.playerSwipeSkip) { viewModel.setGesture(PlayerGesture.PLAYER_SWIPE_SKIP, it) }
            GestureSwitch(R.string.settings_gesture_player_double_tap_title, R.string.settings_gesture_player_double_tap_subtitle,
                gestures.playerDoubleTapLike) { viewModel.setGesture(PlayerGesture.PLAYER_DOUBLE_TAP_LIKE, it) }
            GestureSwitch(R.string.settings_gesture_player_long_press_title, R.string.settings_gesture_player_long_press_subtitle,
                gestures.playerLongPressActions) { viewModel.setGesture(PlayerGesture.PLAYER_LONG_PRESS, it) }
            SwitchSettingItem(
                title = stringResource(R.string.settings_tap_bg_closes_title),
                subtitle = stringResource(R.string.settings_tap_bg_closes_subtitle),
                checked = tapBackgroundClosesPlayer,
                onCheckedChange = onTapBackgroundClosesPlayer,
                leadingIcon = { Icon(painterResource(R.drawable.rounded_touch_app_24), null, tint = tint) }
            )
        }

        SettingsSubsection(title = stringResource(R.string.settings_gestures_mini_player_section)) {
            GestureSwitch(R.string.settings_gesture_mini_swipe_title, R.string.settings_gesture_mini_swipe_subtitle,
                gestures.miniSwipeSkip) { viewModel.setGesture(PlayerGesture.MINI_SWIPE_SKIP, it) }
            GestureSwitch(R.string.settings_gesture_mini_swipe_up_title, R.string.settings_gesture_mini_swipe_up_subtitle,
                gestures.miniSwipeUpOpen) { viewModel.setGesture(PlayerGesture.MINI_SWIPE_UP, it) }
            GestureSwitch(R.string.settings_gesture_mini_long_press_title, R.string.settings_gesture_mini_long_press_subtitle,
                gestures.miniLongPressActions) { viewModel.setGesture(PlayerGesture.MINI_LONG_PRESS, it) }
        }

        SettingsSubsection(title = stringResource(R.string.settings_gestures_song_lists_section)) {
            GestureSwitch(R.string.settings_gesture_row_swipe_title, R.string.settings_gesture_row_swipe_subtitle,
                gestures.rowSwipeQueue) { viewModel.setGesture(PlayerGesture.ROW_SWIPE_QUEUE, it) }
        }

        SettingsSubsection(title = stringResource(R.string.settings_haptics_section), addBottomSpace = developerMode) {
            SwitchSettingItem(
                title = stringResource(R.string.settings_haptic_feedback_title),
                subtitle = stringResource(R.string.settings_haptic_feedback_subtitle),
                checked = hapticFeedbackEnabled,
                onCheckedChange = onHapticFeedbackEnabled,
                leadingIcon = { Icon(painterResource(R.drawable.rounded_touch_app_24), null, tint = tint) }
            )
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
            }
        }

        if (developerMode && supported && haptics.enabled && viewModel.systemGeneratorAvailable) {
            SettingsSubsection(title = stringResource(R.string.settings_section_advanced), addBottomSpace = false) {
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

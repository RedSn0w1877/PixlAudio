package com.theveloper.pixelplay.presentation.components.subcomps

import com.theveloper.pixelplay.presentation.components.AdaptiveAlertDialog
import com.theveloper.pixelplay.presentation.components.AdaptiveModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.Abc
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.presentation.components.ToggleSegmentButton
import com.theveloper.pixelplay.presentation.components.player.BottomToggleRow
import com.theveloper.pixelplay.presentation.components.player.GlassPlayerToggleRow
import com.theveloper.pixelplay.ui.glass.controls.LiquidSegmented
import com.theveloper.pixelplay.ui.glass.controls.SegmentOption
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsMoreBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    lyrics: Lyrics?,
    song: com.theveloper.pixelplay.data.model.Song?,
    onLyricsReady: (Boolean) -> Unit,
    showSyncedLyrics: Boolean,
    isSyncControlsVisible: Boolean,
    onSaveLyricsAsLrc: () -> Unit,
    onResetImportedLyrics: () -> Unit,
    onTranslateViaAi: () -> Unit,
    onToggleSyncControls: () -> Unit,
    isImmersiveTemporarilyDisabled: Boolean,
    onSetImmersiveTemporarilyDisabled: (Boolean) -> Unit,
    /** The current song shows as plain text even though it has synced lines. */
    showAsPlainText: Boolean,
    /** Null hides the "Show as plain text" switch (lyrics with no synced lines are plain already). */
    onShowAsPlainTextChange: ((Boolean) -> Unit)?,
    lyricsAlignment: String,
    onLyricsAlignmentChange: (String) -> Unit,
    hasTranslatedLyrics: Boolean,
    hasRomanizedLyrics: Boolean,
    showTranslation: Boolean,
    showRomanization: Boolean,
    onShowTranslationChange: (Boolean) -> Unit,
    onShowRomanizationChange: (Boolean) -> Unit,
    immersiveLyricsEnabled: Boolean,
    // BottomToggleRow params
    isShuffleEnabled: Boolean,
    isShuffleTransitionInProgress: Boolean = false,
    repeatMode: Int,
    isFavoriteProvider: () -> Boolean,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: () -> Unit,
    // Colors
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onAccentColor: Color = MaterialTheme.colorScheme.onPrimary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
    onTertiaryColor: Color = MaterialTheme.colorScheme.onTertiary,
    /** "Sync the words yourself"; null hides the item (no song, or casting). */
    onSyncYourself: (() -> Unit)? = null,
    /**
     * Liquid Glass: a floating, half-height glass sheet (owner decision) whose rows stay soft
     * fills in the glass palette's text colour, with the full player's liquid pieces for the
     * alignment picker (LiquidSegmented) and the shuffle / repeat / heart row (LiquidChips).
     * Material 3 mode keeps its look.
     */
    glass: Boolean = false,
) {
    val paragraphBreak = "\n\n"
    val isUserSynced = lyrics?.document?.metadata?.source == com.theveloper.pixelplay.data.lyrics.sync.LyricsTapSync.SOURCE_USER
    val hasWordTiming = lyrics?.synced.orEmpty().any { !it.words.isNullOrEmpty() }
    val navigationBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var showResetDialog by remember { mutableStateOf(false) }
    var showDebugDialog by remember { mutableStateOf(false) }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(top = 0, bottom = 0) },
        floating = glass
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
        // Over glass the rows take the glass palette's text colour: the caller's default is the
        // album scheme's onSurface, which can be dark on a light-palette sheet or vice versa.
        val rowContent = if (glass) LocalGlassPalette.current.primary else contentColor
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // The floating glass sheet already sits above the navigation bar.
                .padding(bottom = if (glass) 16.dp else 24.dp + navigationBarsPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // No Title - "Expressive" relies on visual grouping

            val itemBackgroundColor = rowContent.copy(alpha = 0.08f)

            // Lyrics Actions Group
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    modifier = Modifier
                        .padding(start = 6.dp, bottom = 6.dp),
                    text = stringResource(R.string.lyrics_title),
                    color = accentColor,
                    style = MaterialTheme.typography.bodyLargeEmphasized
                )
                // Sync the words yourself (first item)
                if (onSyncYourself != null) {
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(
                                    if (isUserSynced) R.string.lyrics_sync_fix_title else R.string.lyrics_sync_yourself_title
                                )
                            )
                        },
                        supportingContent = if (!isUserSynced && !hasWordTiming) {
                            { Text(stringResource(R.string.lyrics_sync_yourself_sub)) }
                        } else null,
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Rounded.TouchApp,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 8.dp, bottomEnd = 8.dp))
                            .background(itemBackgroundColor)
                            .clickable {
                                onDismissRequest()
                                onSyncYourself()
                            },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = rowContent,
                            supportingColor = rowContent.copy(alpha = 0.7f),
                            leadingIconColor = accentColor
                        )
                    )
                }

                 // Save lyrics to .lrc
                if (lyrics != null) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.lyrics_save_title)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.outline_save_24),
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(
                                if (onSyncYourself != null) RoundedCornerShape(8.dp)
                                else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
                            )
                            .background(itemBackgroundColor)
                            .clickable {
                                onDismissRequest()
                                onSaveLyricsAsLrc()
                            },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = rowContent,
                            leadingIconColor = rowContent
                        )
                    )
                }

                // Translate via AI
                if (lyrics != null) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.lyrics_translate_via_ai)) },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Rounded.Translate,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(itemBackgroundColor)
                            .clickable {
                                onDismissRequest()
                                onTranslateViaAi()
                            },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = rowContent,
                            leadingIconColor = rowContent
                        )
                    )
                }

                // Reset imported lyrics
                ListItem(
                    headlineContent = { Text(stringResource(R.string.lyrics_reset_imported)) },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.outline_restart_alt_24),
                            contentDescription = null
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(itemBackgroundColor)
                        .clickable {
                            showResetDialog = true
                        },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = rowContent,
                        leadingIconColor = rowContent
                    )
                )

                // Debug: where did this song's lyrics come from, and is any of it word-synced.
                // Always visible (even with lyrics == null) so "nothing loaded" is itself a
                // visible diagnostic rather than a missing menu item.
                ListItem(
                    headlineContent = { Text(stringResource(R.string.lyrics_debug_info)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.BugReport,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                        .background(itemBackgroundColor)
                        .clickable { showDebugDialog = true },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = rowContent,
                        leadingIconColor = rowContent
                    )
                )
            }

            if (song != null) {
                com.theveloper.pixelplay.presentation.components.tais.LyricsSyncJobRow(
                    song = song,
                    onLyricsReady = onLyricsReady
                )
                Text(
                    text = "Find matching pre-synced lyrics first. Audio alignment is the fallback; existing lyrics stay if it fails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = rowContent.copy(alpha = 0.7f)
                )
            }

            if (showResetDialog) {
                AdaptiveAlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = { Text(stringResource(R.string.lyrics_reset_dialog_title)) },
                    text = {
                        Text(
                            if (isUserSynced) {
                                stringResource(R.string.lyrics_reset_dialog_message) + paragraphBreak +
                                    stringResource(R.string.lyrics_reset_also_user)
                            } else {
                                stringResource(R.string.lyrics_reset_dialog_message)
                            }
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                showResetDialog = false
                                onDismissRequest()
                                onResetImportedLyrics()
                            }
                        ) {
                            Text(stringResource(R.string.common_reset), color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { showResetDialog = false }
                        ) {
                            Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            }

            if (showDebugDialog) {
                // Inspect the actual loaded timing data, including newer catalog formats.
                val synced = lyrics?.synced
                val wordSyncedCount = synced?.count { !it.words.isNullOrEmpty() } ?: 0
                val debugMessage = when {
                    lyrics == null -> "No lyrics loaded for this song."
                    synced.isNullOrEmpty() -> {
                        val sourceLine = lyrics.document?.metadata?.source ?: if (lyrics.areFromRemote) "Remote catalog" else "Local file / embedded / cache"
                        if (lyrics.plain.isNullOrEmpty()) {
                            "Source: $sourceLine\nNo lyrics content at all."
                        } else {
                            "Source: $sourceLine\nPlain text only — no timestamps, so no highlighting is possible."
                        }
                    }
                    else -> {
                        val sourceLine = lyrics.document?.metadata?.source ?: if (lyrics.areFromRemote) "Remote catalog" else "Local file / embedded / cache"
                        buildString {
                            append("Source: ").append(sourceLine).append('\n')
                            append("Synced lines: ").append(synced.size).append('\n')
                            append("Word-synced lines: ").append(wordSyncedCount).append(" of ").append(synced.size)
                            if (wordSyncedCount == 0) {
                                append("\n\nNo word-level timing in this file.")
                                if (lyrics.areFromRemote) {
                                    append(" This catalog record has line timing only. Other recordings may include real word timing; resync checks available catalogs again.")
                                } else {
                                    append(" This file doesn't include per-word tags.")
                                }
                            }
                        }
                    }
                }
                AdaptiveAlertDialog(
                    onDismissRequest = { showDebugDialog = false },
                    title = { Text(stringResource(R.string.lyrics_debug_dialog_title)) },
                    text = { Text(debugMessage) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { showDebugDialog = false }) {
                            Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            }

            // Appearance Group
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    modifier = Modifier
                        .padding(start = 6.dp, bottom = 6.dp),
                    text = stringResource(R.string.lyrics_appearance_section),
                    color = accentColor,
                    style = MaterialTheme.typography.bodyLargeEmphasized
                 )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(itemBackgroundColor)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.lyrics_appearance_alignment),
                        color = rowContent,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )

                    if (glass) {
                        // The liquid lens picker, on the sheet's own window backdrop.
                        val alignments = remember { listOf("left", "center", "right") }
                        LiquidSegmented(
                            options = listOf(
                                SegmentOption(
                                    stringResource(R.string.lyrics_appearance_align_left),
                                    Icons.AutoMirrored.Rounded.FormatAlignLeft
                                ),
                                SegmentOption(
                                    stringResource(R.string.lyrics_appearance_align_center),
                                    Icons.Rounded.FormatAlignCenter
                                ),
                                SegmentOption(
                                    stringResource(R.string.lyrics_appearance_align_right),
                                    Icons.AutoMirrored.Rounded.FormatAlignRight
                                ),
                            ),
                            selectedIndex = alignments.indexOf(lyricsAlignment).coerceAtLeast(0),
                            onSelect = { index -> onLyricsAlignmentChange(alignments[index]) }
                        )
                    } else Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ToggleSegmentButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            active = lyricsAlignment == "left",
                            activeColor = accentColor,
                            inactiveColor = containerColor,
                            activeContentColor = onAccentColor,
                            inactiveContentColor = rowContent.copy(alpha = 0.78f),
                            activeCornerRadius = 50.dp,
                            onClick = { onLyricsAlignmentChange("left") },
                            imageVector = Icons.AutoMirrored.Rounded.FormatAlignLeft,
                            contentDesc = stringResource(R.string.lyrics_appearance_align_left)
                        )

                        ToggleSegmentButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            active = lyricsAlignment == "center",
                            activeColor = accentColor,
                            inactiveColor = containerColor,
                            activeContentColor = onAccentColor,
                            inactiveContentColor = rowContent.copy(alpha = 0.78f),
                            activeCornerRadius = 50.dp,
                            onClick = { onLyricsAlignmentChange("center") },
                            imageVector = Icons.Rounded.FormatAlignCenter,
                            contentDesc = stringResource(R.string.lyrics_appearance_align_center)
                        )

                        ToggleSegmentButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            active = lyricsAlignment == "right",
                            activeColor = accentColor,
                            inactiveColor = containerColor,
                            activeContentColor = onAccentColor,
                            inactiveContentColor = rowContent.copy(alpha = 0.78f),
                            activeCornerRadius = 50.dp,
                            onClick = { onLyricsAlignmentChange("right") },
                            imageVector = Icons.AutoMirrored.Rounded.FormatAlignRight,
                            contentDesc = stringResource(R.string.lyrics_appearance_align_right)
                        )
                    }
                }
            }

            // Control Settings Group
            // (isSyncVisible / isImmersiveVisible key on showSyncedLyrics, which is false while
            // "Show as plain text" is on: plain text has no sync to adjust or immersion.)
            val isPlainTextVisible = onShowAsPlainTextChange != null
            val isSyncVisible = showSyncedLyrics
            val isRomanizationVisible = hasRomanizedLyrics
            val isTranslationVisible = hasTranslatedLyrics
            val isImmersiveVisible = showSyncedLyrics && immersiveLyricsEnabled
            val controlRows = listOf(
                isPlainTextVisible, isSyncVisible, isRomanizationVisible, isTranslationVisible, isImmersiveVisible
            )
            val controlCount = controlRows.count { it }
            // Each visible row's shape from its position in the group (first / middle / last).
            fun controlShape(row: Int): Shape =
                groupRowShape(controlRows.take(row).count { it }, controlCount)

            if (controlCount > 0) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        modifier = Modifier
                            .padding(start = 6.dp, bottom = 6.dp),
                        text = stringResource(R.string.lyrics_controls_section),
                        color = accentColor,
                        style = MaterialTheme.typography.bodyLargeEmphasized
                    )

                    if (isPlainTextVisible) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.lyrics_show_plain_text)) },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Notes,
                                    contentDescription = null
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = showAsPlainText,
                                    onCheckedChange = { onShowAsPlainTextChange?.invoke(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = onAccentColor,
                                        checkedTrackColor = accentColor,
                                        uncheckedThumbColor = rowContent,
                                        uncheckedTrackColor = rowContent.copy(alpha = 0.3f)
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(controlShape(0))
                                .background(itemBackgroundColor)
                                .clickable { onShowAsPlainTextChange?.invoke(!showAsPlainText) },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = rowContent,
                                leadingIconColor = rowContent
                            )
                        )
                    }

                    if (isSyncVisible) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    if (isSyncControlsVisible) {
                                        stringResource(R.string.lyrics_controls_hide_sync)
                                    } else {
                                        stringResource(R.string.lyrics_controls_adjust_sync)
                                    }
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(controlShape(1))
                                .background(itemBackgroundColor)
                                .clickable {
                                    onDismissRequest()
                                    onToggleSyncControls()
                                },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = rowContent,
                                leadingIconColor = rowContent
                            )
                        )
                    }

                    if (isRomanizationVisible) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.lyrics_controls_show_romanization)) },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.Abc,
                                    contentDescription = null
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = showRomanization,
                                    onCheckedChange = onShowRomanizationChange,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = onAccentColor,
                                        checkedTrackColor = accentColor,
                                        uncheckedThumbColor = rowContent,
                                        uncheckedTrackColor = rowContent.copy(alpha = 0.3f)
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(controlShape(2))
                                .background(itemBackgroundColor)
                                .clickable { onShowRomanizationChange(!showRomanization) },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = rowContent,
                                leadingIconColor = rowContent
                            )
                        )
                    }

                    if (isTranslationVisible) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.lyrics_controls_show_translations)) },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.Translate,
                                    contentDescription = null
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = showTranslation,
                                    onCheckedChange = onShowTranslationChange,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = onAccentColor,
                                        checkedTrackColor = accentColor,
                                        uncheckedThumbColor = rowContent,
                                        uncheckedTrackColor = rowContent.copy(alpha = 0.3f)
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(controlShape(3))
                                .background(itemBackgroundColor)
                                .clickable { onShowTranslationChange(!showTranslation) },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = rowContent,
                                leadingIconColor = rowContent
                            )
                        )
                    }

                    // Immersive Mode Toggle
                    if (isImmersiveVisible) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.lyrics_controls_disable_immersive_once)) },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.VisibilityOff,
                                    contentDescription = null
                                )
                            },
                            trailingContent = {
                                Switch(
                                    modifier = Modifier,
                                    checked = isImmersiveTemporarilyDisabled,
                                    onCheckedChange = {
                                        onSetImmersiveTemporarilyDisabled(it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = onAccentColor,
                                        checkedTrackColor = accentColor,
                                        uncheckedThumbColor = rowContent,
                                        uncheckedTrackColor = rowContent.copy(alpha = 0.3f)
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(controlShape(4))
                                .background(itemBackgroundColor)
                                // The whole row is the target, as for the other switch rows.
                                .clickable { onSetImmersiveTemporarilyDisabled(!isImmersiveTemporarilyDisabled) },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = rowContent,
                                leadingIconColor = rowContent
                            )
                        )
                    }

                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // Playback Options
            if (glass) {
                // The full player's liquid chips (not clipped: their press swell needs the room).
                GlassPlayerToggleRow(
                    isShuffleEnabled = isShuffleEnabled,
                    isShuffleTransitionInProgress = isShuffleTransitionInProgress,
                    repeatMode = repeatMode,
                    isFavoriteProvider = isFavoriteProvider,
                    onShuffleToggle = onShuffleToggle,
                    onRepeatToggle = onRepeatToggle,
                    onFavoriteToggle = onFavoriteToggle,
                    modifier = Modifier.fillMaxWidth()
                )
            } else Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    //.background(rowContent.copy(alpha = 0.08f))
                    .padding(vertical = 0.dp, horizontal = 0.dp)
            ) {
                 BottomToggleRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(74.dp)
                        .padding(horizontal = 20.dp),
                    isShuffleEnabled = isShuffleEnabled,
                    repeatMode = repeatMode,
                    isFavoriteProvider = isFavoriteProvider,
                    onShuffleToggle = onShuffleToggle,
                    onRepeatToggle = onRepeatToggle,
                    onFavoriteToggle = onFavoriteToggle
                )
            }
        }
        }
    }
}

/**
 * The shape of row [index] of [count] in a grouped list (the More sheet's Controls group): the
 * first row has [top] corners on top, the last [bottom] corners at the bottom, everything else
 * [inner]. A single row gets both outer corners.
 */
internal fun groupRowCorners(
    index: Int,
    count: Int,
    top: Dp = 18.dp,
    bottom: Dp = 24.dp,
    inner: Dp = 8.dp,
): Pair<Dp, Dp> = Pair(
    if (index <= 0) top else inner,
    if (index >= count - 1) bottom else inner,
)

internal fun groupRowShape(index: Int, count: Int): Shape {
    val (top, bottom) = groupRowCorners(index, count)
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

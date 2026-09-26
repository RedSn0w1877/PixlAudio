package com.theveloper.pixelplay.presentation.lyrics.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.lyrics.sync.SyncDraftOrigin
import com.theveloper.pixelplay.presentation.viewmodel.LyricsSyncEditorStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.SyncPhase
import com.theveloper.pixelplay.presentation.viewmodel.SyncUiState
import com.theveloper.pixelplay.ui.glass.GlassAlertDialog
import kotlinx.coroutines.delay

/** Shared frame for the short screens: top bar, content centred, actions at the bottom. */
@Composable
private fun SyncCardScreen(
    ui: SyncUiState,
    holder: LyricsSyncEditorStateHolder,
    palette: SyncEditorPalette,
    modifier: Modifier = Modifier,
    speed: Float? = null,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        SyncTopBar(
            title = ui.title,
            palette = palette,
            onClose = holder::requestClose,
            speed = speed,
            onSpeedChange = holder::setSpeed,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            content = content,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = actions,
        )
    }
}

@Composable
private fun Title(text: String) {
    Text(text = text, color = Color.White, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text = text, color = Color.White.copy(alpha = 0.72f), fontSize = 16.sp, lineHeight = 22.sp, modifier = modifier)
}

/** "Sync the words yourself": three short steps and the speed choice (spec §2.4). */
@Composable
internal fun SyncIntroScreen(
    ui: SyncUiState,
    holder: LyricsSyncEditorStateHolder,
    palette: SyncEditorPalette,
    modifier: Modifier = Modifier,
) {
    val alreadyTimed = ui.origin == SyncDraftOrigin.WORD_SYNCED || ui.origin == SyncDraftOrigin.USER_SYNCED
    SyncCardScreen(
        ui = ui,
        holder = holder,
        palette = palette,
        modifier = modifier,
        actions = {
            EditorButton(
                onClick = { holder.startFromIntro(dontShowAgain = false) },
                palette = palette,
                prominent = true,
                modifier = Modifier.fillMaxWidth(),
            ) { color -> EditorButtonText(stringResource(R.string.lyrics_sync_start), color) }
            if (alreadyTimed) {
                EditorButton(
                    onClick = { holder.goToPreview() },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                ) { color -> EditorButtonText(stringResource(R.string.lyrics_sync_see_result), color, bold = false) }
            } else {
                TextButton(onClick = { holder.startFromIntro(dontShowAgain = true) }) {
                    Text(
                        text = stringResource(R.string.lyrics_sync_got_it),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                    )
                }
            }
        },
    ) {
        Title(stringResource(R.string.lyrics_sync_yourself_title))
        Spacer(Modifier.height(24.dp))
        IntroStep(Icons.Rounded.PlayArrow, stringResource(R.string.lyrics_sync_intro_1), palette)
        IntroStep(Icons.Rounded.TouchApp, stringResource(R.string.lyrics_sync_intro_2), palette)
        IntroStep(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.lyrics_sync_intro_3), palette)
        if (alreadyTimed) {
            Body(stringResource(R.string.lyrics_sync_already_timed), Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.lyrics_sync_speed_label),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        SpeedSegments(speed = ui.speed, palette = palette, onSpeedChange = holder::setSpeed)
        Body(stringResource(R.string.lyrics_sync_speed_help), Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun IntroStep(icon: ImageVector, text: String, palette: SyncEditorPalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(palette.capsule)
                .background(palette.accent),
        ) {
            Icon(icon, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(text = text, color = Color.White, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
    }
}

/** "Pick up where you left off?" (spec §2.2). */
@Composable
internal fun SyncResumeScreen(
    phase: SyncPhase.ResumePrompt,
    ui: SyncUiState,
    holder: LyricsSyncEditorStateHolder,
    palette: SyncEditorPalette,
    modifier: Modifier = Modifier,
) {
    SyncCardScreen(
        ui = ui,
        holder = holder,
        palette = palette,
        modifier = modifier,
        actions = {
            EditorButton(
                onClick = holder::resumeKeepGoing,
                palette = palette,
                prominent = true,
                modifier = Modifier.fillMaxWidth(),
            ) { color -> EditorButtonText(stringResource(R.string.lyrics_sync_keep_going), color) }
            EditorButton(
                onClick = holder::resumeStartOver,
                palette = palette,
                modifier = Modifier.fillMaxWidth(),
            ) { color -> EditorButtonText(stringResource(R.string.lyrics_sync_start_over), color, bold = false) }
        },
    ) {
        Title(stringResource(R.string.lyrics_sync_resume_title))
        Spacer(Modifier.height(12.dp))
        Body(stringResource(R.string.lyrics_sync_resume_body, phase.tapped, phase.total))
    }
}

/** The song is already user-synced: Fix timing · Start over · Remove my timing (spec §2.10). */
@Composable
internal fun SyncManageScreen(
    ui: SyncUiState,
    holder: LyricsSyncEditorStateHolder,
    palette: SyncEditorPalette,
    modifier: Modifier = Modifier,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    SyncCardScreen(
        ui = ui,
        holder = holder,
        palette = palette,
        modifier = modifier,
        actions = {
            EditorButton(
                onClick = holder::manageFixTiming,
                palette = palette,
                prominent = true,
                modifier = Modifier.fillMaxWidth(),
            ) { color -> EditorButtonText(stringResource(R.string.lyrics_sync_fix_timing), color) }
            EditorButton(
                onClick = holder::manageStartOver,
                palette = palette,
                modifier = Modifier.fillMaxWidth(),
            ) { color -> EditorButtonText(stringResource(R.string.lyrics_sync_start_over), color, bold = false) }
            TextButton(onClick = { confirmRemove = true }) {
                Text(
                    text = stringResource(R.string.lyrics_sync_remove),
                    color = Color(0xFFFFB4AB),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
    ) {
        Title(stringResource(R.string.lyrics_sync_fix_title))
        Spacer(Modifier.height(12.dp))
        Body(ui.title)
    }
    if (confirmRemove) {
        GlassAlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.lyrics_sync_remove)) },
            text = { Text(stringResource(R.string.lyrics_sync_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    holder.removeMyTiming()
                }) { Text(stringResource(R.string.common_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** Spinner; the text appears only if getting ready takes more than 600 ms. */
@Composable
internal fun SyncLoadingScreen(modifier: Modifier = Modifier) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SLOW_LOADING_MS)
        slow = true
    }
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color.White)
        AnimatedVisibility(visible = slow, enter = fadeIn()) {
            Text(
                text = stringResource(R.string.lyrics_sync_loading),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
internal fun SyncErrorScreen(
    message: String,
    holder: LyricsSyncEditorStateHolder,
    palette: SyncEditorPalette,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        EditorButton(onClick = holder::close, palette = palette, prominent = true) { color ->
            EditorButtonText(stringResource(R.string.common_close), color)
        }
    }
}

private const val SLOW_LOADING_MS = 600L

package com.theveloper.pixelplay.presentation.lyrics.sync

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.lyrics.background.LyricsArtworkBackground
import com.theveloper.pixelplay.presentation.lyrics.background.rememberLyricsBackgroundState
import com.theveloper.pixelplay.presentation.viewmodel.LyricsSyncEditorStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.SyncDialog
import com.theveloper.pixelplay.presentation.viewmodel.SyncPhase
import com.theveloper.pixelplay.presentation.viewmodel.SyncUiState
import com.theveloper.pixelplay.ui.glass.GlassAlertDialog

/**
 * Host of the "sync it yourself" editor: a full-screen layer over the lyrics sheet inside the
 * player (spec §2), with the same animated artwork behind it. It keeps the screen on, pauses on
 * `ON_STOP`, owns Back, blocks touches to what's underneath and switches between the screens.
 */
@Composable
fun LyricsSyncEditorOverlay(
    holder: LyricsSyncEditorStateHolder,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
) {
    val phase by holder.phase.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = phase != SyncPhase.Closed,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(tween(200)) + slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it / 10 },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it / 12 },
    ) {
        MaterialTheme(colorScheme = colorScheme) {
            EditorHost(holder = holder, phase = phase)
        }
    }
}

@Composable
private fun EditorHost(holder: LyricsSyncEditorStateHolder, phase: SyncPhase) {
    // The whole state is read only inside the screen switcher below, so a tap (a new draft)
    // recomposes the current screen, not the host, the background and the dialogs.
    val uiState = holder.uiState.collectAsStateWithLifecycle()
    val artUri by remember { derivedStateOf { uiState.value.artUri } }
    val notice by remember { derivedStateOf { uiState.value.notice } }
    val dialog by remember { derivedStateOf { uiState.value.dialog } }
    val endedEarlyWords by remember { derivedStateOf { uiState.value.endedEarlyWords } }
    // While the exit animation runs the phase is already Closed and the state has been reset:
    // keep drawing the last screen, with its last state and artwork, so it doesn't blank out
    // as it fades.
    val closing = phase == SyncPhase.Closed
    val lastPhase = remember { Latch<SyncPhase?>(null) }
    val lastUi = remember { Latch<SyncUiState?>(null) }
    val lastArtUri = remember { Latch<String?>(null) }
    if (!closing) {
        lastPhase.value = phase
        lastArtUri.value = artUri
    }
    val shownPhase = lastPhase.value ?: return
    val shownArtUri = if (closing) lastArtUri.value else artUri
    val palette = rememberSyncEditorPalette()
    val backgroundState = rememberLyricsBackgroundState()

    KeepScreenOn()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, holder) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) holder.onHostStopped()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hostView = LocalView.current
    DisposableEffect(hostView, holder) {
        onDispose {
            // The activity is finishing (not a configuration change): the session must not
            // outlive it with the player slowed down and crossfades suspended.
            if (hostView.context.findActivity()?.isFinishing == true) holder.close()
        }
    }
    BackHandler(enabled = phase != SyncPhase.Closed) { holder.onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Nothing below (the lyrics sheet, the player) may see touches while editing: being
            // hit here is enough for lower siblings, since pointer input never falls through.
            // Ancestors are another matter — the player sheet's vertical drag sits above us — so
            // any drag nobody in the editor claimed is consumed here once it passes touch slop.
            // Taps are never consumed, so the editor's own buttons keep working; its own scrolls
            // consume first (children see the Main pass before us) and are left alone.
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> change.consume() }
            },
    ) {
        LyricsArtworkBackground(
            artUri = shownArtUri,
            modifier = Modifier.fillMaxSize(),
            state = backgroundState,
            colorScheme = MaterialTheme.colorScheme,
        )
        // A light scrim keeps white controls legible over any artwork.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (shownPhase == SyncPhase.Preview) 0.08f else 0.24f))
        )

        AnimatedContent(
            targetState = shownPhase,
            contentKey = { screenKey(it) },
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
            label = "syncEditorScreen",
            modifier = Modifier.fillMaxSize(),
        ) { target ->
            val live = uiState.value
            val ui = if (closing) lastUi.value ?: live else live.also { lastUi.value = it }
            when (target) {
                SyncPhase.Closed, SyncPhase.Loading -> SyncLoadingScreen()
                is SyncPhase.ResumePrompt -> SyncResumeScreen(target, ui, holder, palette)
                SyncPhase.NeedWords -> SyncWordsEntryScreen(ui, holder, palette)
                SyncPhase.Intro -> SyncIntroScreen(ui, holder, palette)
                SyncPhase.Tapping, is SyncPhase.FixLine -> SyncTapScreen(ui, holder, palette)
                SyncPhase.Preview -> SyncPreviewScreen(ui, holder, palette, brightArt = backgroundState.isBrightArt)
                SyncPhase.Manage -> SyncManageScreen(ui, holder, palette)
                is SyncPhase.Error -> SyncErrorScreen(target.message, holder, palette)
            }
        }

        notice?.let { shown ->
            SyncNoticePill(
                notice = shown,
                palette = palette,
                holder = holder,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = NOTICE_BOTTOM),
            )
        }
    }

    SyncDialogs(dialog, endedEarlyWords, holder)
}

/** A plain holder written during composition; reads never trigger recomposition. */
private class Latch<T>(var value: T)

private fun screenKey(phase: SyncPhase): String = when (phase) {
    SyncPhase.Closed, SyncPhase.Loading -> "loading"
    is SyncPhase.ResumePrompt -> "resume"
    SyncPhase.NeedWords -> "words"
    SyncPhase.Intro -> "intro"
    SyncPhase.Tapping, is SyncPhase.FixLine -> "tap"
    SyncPhase.Preview -> "preview"
    SyncPhase.Manage -> "manage"
    is SyncPhase.Error -> "error"
}

/**
 * Keeps the screen on for the whole session. It uses the window flag rather than
 * `View.keepScreenOn`, because the lyrics sheet underneath clears the view's flag when it leaves
 * composition (which it does while the editor is open).
 */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun SyncDialogs(dialog: SyncDialog, endedEarlyWords: Int, holder: LyricsSyncEditorStateHolder) {
    when (dialog) {
        SyncDialog.NONE -> Unit
        SyncDialog.LEAVE -> GlassAlertDialog(
            onDismissRequest = holder::dismissDialog,
            title = { Text(stringResource(R.string.lyrics_sync_leave_title)) },
            text = { Text(stringResource(R.string.lyrics_sync_leave_body)) },
            confirmButton = {
                TextButton(onClick = holder::confirmLeave) { Text(stringResource(R.string.lyrics_sync_leave)) }
            },
            dismissButton = {
                TextButton(onClick = holder::dismissDialog) { Text(stringResource(R.string.lyrics_sync_stay)) }
            },
        )
        SyncDialog.SONG_CHANGED -> GlassAlertDialog(
            onDismissRequest = holder::dismissDialog,
            title = { Text(stringResource(R.string.lyrics_sync_song_changed_title)) },
            text = { Text(stringResource(R.string.lyrics_sync_song_changed_body)) },
            confirmButton = {
                TextButton(onClick = holder::dismissDialog) { Text(stringResource(R.string.common_ok)) }
            },
        )
        SyncDialog.ENDED_EARLY -> GlassAlertDialog(
            onDismissRequest = holder::dismissDialog,
            text = { Text(stringResource(R.string.lyrics_sync_ended_early, endedEarlyWords)) },
            confirmButton = {
                TextButton(onClick = holder::endedKeepGoing) { Text(stringResource(R.string.lyrics_sync_keep_going)) }
            },
            dismissButton = {
                TextButton(onClick = holder::endedTimeRest) { Text(stringResource(R.string.lyrics_sync_time_rest)) }
            },
        )
        SyncDialog.SAVE_FAILED -> GlassAlertDialog(
            onDismissRequest = holder::dismissDialog,
            text = { Text(stringResource(R.string.lyrics_sync_save_failed)) },
            confirmButton = {
                TextButton(onClick = {
                    holder.dismissDialog()
                    holder.save()
                }) { Text(stringResource(R.string.lyrics_sync_try_again)) }
            },
            dismissButton = {
                TextButton(onClick = holder::dismissDialog) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private val NOTICE_BOTTOM = 92.dp

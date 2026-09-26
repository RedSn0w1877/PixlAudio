package com.theveloper.pixelplay.presentation.lyrics.sync

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.lyrics.KaraokeLyricsAppearance
import com.theveloper.pixelplay.presentation.lyrics.KaraokeLyricsView
import com.theveloper.pixelplay.presentation.lyrics.LongSource
import com.theveloper.pixelplay.presentation.lyrics.rememberLyricsAppearancePrefs
import com.theveloper.pixelplay.presentation.lyrics.rememberLyricsClock
import com.theveloper.pixelplay.presentation.lyrics.rememberLyricsEngine
import com.theveloper.pixelplay.presentation.viewmodel.LyricsSyncEditorStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.SyncUiState
import com.theveloper.pixelplay.presentation.components.AdaptiveAlertDialog
import com.theveloper.pixelplay.ui.theme.LyricsDisplayFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Preview (spec §2.7): the real result in the same karaoke renderer as the lyrics sheet, over
 * the same artwork, with the earlier/later nudge, Fix a line, Save, Share and Keep tapping.
 */
@Composable
internal fun SyncPreviewScreen(
    ui: SyncUiState,
    holder: LyricsSyncEditorStateHolder,
    palette: SyncEditorPalette,
    brightArt: Boolean,
    modifier: Modifier = Modifier,
) {
    val preview = ui.preview
    // A lambda, not `holder::positionMs`: the reference boxes every frame's position.
    val positionSource = remember(holder) { LongSource { holder.positionMs() } }
    val clock = rememberLyricsClock(positionProvider = positionSource)
    val engine = rememberLyricsEngine(clock)
    val covered = remember(ui.draft?.songId, ui.draft?.lines?.size) {
        ui.draft?.lines?.all { isCoveredByAppFont(it.text) } ?: true
    }
    // The same preferences the lyrics sheet reads (alignment, blur, contrast, size), so the
    // preview the user approves before saving is exactly what the lyrics screen will show.
    val prefs by rememberLyricsAppearancePrefs()
    val textSize = MaterialTheme.typography.titleLarge.fontSize
    val appearance = remember(covered, brightArt, prefs, textSize) {
        prefs.toAppearance(if (covered) LyricsDisplayFamily else null, textSize, brightArt)
    }
    var showShare by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SyncTopBar(
            title = ui.title,
            palette = palette,
            onClose = holder::requestClose,
            speed = ui.speed,
            onSpeedChange = holder::setSpeed,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (preview == null) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else {
                KaraokeLyricsView(
                    prepared = preview.prepared,
                    clock = clock,
                    engine = engine,
                    isPlaying = ui.isPlaying,
                    songKey = ui.songId,
                    appearance = appearance,
                    onSeekLine = { line -> holder.onPreviewLineTap(line.index, line.startMs) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = ui.lineSelectMode,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(palette.capsule)
                        .background(palette.accent)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.lyrics_sync_fix_line_hint),
                        color = palette.onAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        PreviewPanel(
            ui = ui,
            holder = holder,
            palette = palette,
            onShare = { showShare = true },
        )
    }

    // Launchers live here, not in the dialog: the dialog closes before the result comes back.
    val exporter = rememberLyricsFileExporter(holder)
    if (showShare) {
        ShareDialog(
            onDismiss = { showShare = false },
            onPick = { ttml ->
                showShare = false
                exporter(ttml)
            },
        )
    }
}

@Composable
private fun PreviewPanel(
    ui: SyncUiState,
    holder: LyricsSyncEditorStateHolder,
    palette: SyncEditorPalette,
    onShare: () -> Unit,
) {
    val shape = palette.panelShape
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .navigationBarsPadding()
            .clip(shape)
            .background(palette.panelContainer)
            .then(if (palette.rim != null) Modifier.border(0.75.dp, palette.rim, shape) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedContent(
            targetState = ui.lineSelectMode,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "previewPanel",
        ) { selecting ->
            if (selecting) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.lyrics_sync_fix_line_hint),
                        color = palette.onPanel,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { holder.setLineSelectMode(false) }) {
                        Text(stringResource(R.string.common_cancel), color = palette.onPanel)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.lyrics_sync_preview_timing_q),
                        color = palette.onPanel.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    NudgeControl(nudgeMs = ui.draft?.nudgeMs ?: 0, palette = palette, onNudge = holder::nudge)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        EditorButton(
                            onClick = { holder.setLineSelectMode(true) },
                            palette = palette,
                            onPanel = true,
                            modifier = Modifier.weight(1f),
                        ) { color ->
                            Icon(Icons.Rounded.Edit, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            EditorButtonText(stringResource(R.string.lyrics_sync_fix_line), color, bold = false)
                        }
                        EditorButton(
                            onClick = holder::keepTapping,
                            palette = palette,
                            onPanel = true,
                            modifier = Modifier.weight(1f),
                        ) { color ->
                            Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            EditorButtonText(stringResource(R.string.lyrics_sync_keep_tapping), color, bold = false)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        EditorButton(
                            onClick = onShare,
                            palette = palette,
                            onPanel = true,
                            modifier = Modifier.size(56.dp),
                        ) { color ->
                            Icon(
                                Icons.Rounded.IosShare,
                                contentDescription = stringResource(R.string.lyrics_sync_share),
                                tint = color,
                            )
                        }
                        EditorButton(
                            onClick = holder::save,
                            palette = palette,
                            prominent = true,
                            enabled = !ui.isSaving,
                            modifier = Modifier.weight(1f),
                        ) { color ->
                            if (ui.isSaving) {
                                CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                EditorButtonText(stringResource(R.string.lyrics_sync_saving), color)
                            } else {
                                EditorButtonText(stringResource(R.string.lyrics_sync_save), color)
                            }
                        }
                    }
                    if (ui.preview?.hasRoughLines == true) {
                        Text(
                            text = "≈  " + stringResource(R.string.lyrics_sync_rough),
                            color = palette.onPanel.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

/** "Earlier ‹ | +40 ms | › Later": each press moves every word by 20 ms. */
@Composable
private fun NudgeControl(nudgeMs: Int, palette: SyncEditorPalette, onNudge: (Int) -> Unit) {
    val shape = palette.capsule
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(palette.panelButton),
    ) {
        NudgeHalf(
            label = stringResource(R.string.lyrics_sync_earlier),
            leading = true,
            color = palette.onPanelButton,
            palette = palette,
            onClick = { onNudge(-1) },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.lyrics_sync_nudge_value, nudgeMs),
            color = palette.onPanelButton,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        NudgeHalf(
            label = stringResource(R.string.lyrics_sync_later),
            leading = false,
            color = palette.onPanelButton,
            palette = palette,
            onClick = { onNudge(1) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NudgeHalf(
    label: String,
    leading: Boolean,
    color: Color,
    palette: SyncEditorPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = editorIndication(palette),
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        if (leading) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = null, tint = color)
        Text(text = label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (!leading) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = color)
    }
}

/** Returns a launcher: pick a place for the .lrc (false) or .ttml (true) file, then write it. */
@Composable
private fun rememberLyricsFileExporter(holder: LyricsSyncEditorStateHolder): (Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.lyrics_sync_file_saved)
    val failedMessage = stringResource(R.string.lyrics_sync_file_failed)

    fun write(uri: Uri?, ttml: Boolean) {
        if (uri == null) return
        scope.launch {
            val text = holder.exportText(ttml)
            val ok = text != null && withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) savedMessage else failedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    val lrcLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        write(uri, ttml = false)
    }
    val ttmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/ttml+xml")) { uri ->
        write(uri, ttml = true)
    }
    return remember(lrcLauncher, ttmlLauncher, holder) {
        { ttml: Boolean ->
            if (ttml) ttmlLauncher.launch(holder.exportFileName(ttml = true))
            else lrcLauncher.launch(holder.exportFileName(ttml = false))
        }
    }
}

/** "Share lyrics file": .lrc for most apps, .ttml for the most detail. */
@Composable
private fun ShareDialog(onDismiss: () -> Unit, onPick: (ttml: Boolean) -> Unit) {
    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lyrics_sync_share)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShareOption(stringResource(R.string.lyrics_sync_share_lrc)) { onPick(false) }
                ShareOption(stringResource(R.string.lyrics_sync_share_ttml)) { onPick(true) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun ShareOption(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
    )
}

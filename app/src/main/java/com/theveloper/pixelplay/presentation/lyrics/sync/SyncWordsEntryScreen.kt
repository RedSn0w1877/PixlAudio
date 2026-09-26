package com.theveloper.pixelplay.presentation.lyrics.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.viewmodel.LyricsSyncEditorStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.SyncUiState
import com.theveloper.pixelplay.presentation.components.AdaptiveAlertDialog

/** "Paste the lyrics" (spec §2.3). */
@Composable
internal fun SyncWordsEntryScreen(
    ui: SyncUiState,
    holder: LyricsSyncEditorStateHolder,
    palette: SyncEditorPalette,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(ui.songId, ui.wordsSeed) { mutableStateOf(ui.wordsSeed) }
    var confirmTooLong by remember { mutableStateOf(false) }
    val hasWords = text.lineSequence().any { it.isNotBlank() }

    fun next() {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val words = lines.sumOf { line -> line.trim().split(WHITESPACE).count { it.isNotEmpty() } }
        if (lines.size > MAX_LINES || words > MAX_WORDS) confirmTooLong = true else holder.submitWords(text)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        SyncTopBar(title = ui.title, palette = palette, onClose = holder::requestClose)
        Text(
            text = stringResource(R.string.lyrics_sync_paste_title),
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.lyrics_sync_paste_body),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        val fieldShape = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(fieldShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(0.75.dp, Color.White.copy(alpha = 0.16f), fieldShape)
                .padding(18.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp, lineHeight = 26.sp),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxSize(),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.lyrics_sync_paste_body),
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 18.sp,
                        )
                    }
                    inner()
                },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            EditorButton(
                onClick = holder::findLyricsOnline,
                palette = palette,
                enabled = !ui.searching,
                modifier = Modifier.weight(1f),
            ) { color ->
                if (ui.searching) {
                    CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                EditorButtonText(stringResource(R.string.lyrics_sync_find_online), color, bold = false)
            }
            EditorButton(
                onClick = ::next,
                palette = palette,
                prominent = true,
                enabled = hasWords,
                modifier = Modifier.weight(1f),
            ) { color ->
                EditorButtonText(stringResource(R.string.lyrics_sync_next), color)
            }
        }
    }

    ui.searchHits?.let { hits ->
        AdaptiveAlertDialog(
            onDismissRequest = holder::clearSearchHits,
            title = { Text(stringResource(R.string.lyrics_sync_pick_result)) },
            text = {
                if (hits.isEmpty()) {
                    Text(stringResource(R.string.lyrics_sync_no_results))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(hits, key = { index, _ -> index }, contentType = { _, _ -> "hit" }) { _, hit ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button) {
                                        text = hit.text
                                        holder.clearSearchHits()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                            ) {
                                Text(hit.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = hit.text.lineSequence().take(2).joinToString(" / "),
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = holder::clearSearchHits) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (confirmTooLong) {
        AdaptiveAlertDialog(
            onDismissRequest = { confirmTooLong = false },
            text = { Text(stringResource(R.string.lyrics_sync_too_long)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTooLong = false
                    holder.submitWords(text)
                }) { Text(stringResource(R.string.lyrics_sync_use_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTooLong = false }) { Text(stringResource(R.string.lyrics_sync_edit)) }
            },
        )
    }
}

private val WHITESPACE = Regex("\\s+")
private const val MAX_LINES = 400
private const val MAX_WORDS = 5_000

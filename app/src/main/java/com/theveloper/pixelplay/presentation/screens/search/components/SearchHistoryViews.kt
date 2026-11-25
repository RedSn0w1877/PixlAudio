package com.theveloper.pixelplay.presentation.screens.search.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import com.theveloper.pixelplay.presentation.components.redesign.toComposeShape
import com.theveloper.pixelplay.presentation.components.redesign.RedesignType
import com.theveloper.pixelplay.presentation.components.redesign.BadgeShape
import androidx.compose.runtime.key
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryKind
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.SmartImageListTargetSize

/*
 * The search history on the Search screen (one behaviour on both apps):
 *   - SearchRecentStrip: a compact "Recent" row above the genre grid, up to 10 tiles.
 *   - SearchHistoryPanel: the vertical "Recent searches" list that replaces the grid while the empty field has focus.
 *   - SearchHistorySuggestions: matching history rows above the live results while typing.
 * All three are dumb: the screen decides which shows, from the query, the field's focus and the private session.
 */


@Composable
private fun kindLabel(kind: SearchHistoryKind?): String? = when (kind) {
    SearchHistoryKind.SONG -> stringResource(R.string.search_history_kind_song)
    SearchHistoryKind.ALBUM -> stringResource(R.string.search_history_kind_album)
    SearchHistoryKind.ARTIST -> stringResource(R.string.search_history_kind_artist)
    SearchHistoryKind.PLAYLIST -> stringResource(R.string.search_history_kind_playlist)
    else -> null
}

/** The one-line note that shows instead of the recents while a private session is on. */
@Composable
fun SearchHistoryPrivateHint(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.search_history_private_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

// --- The "Recent" strip -------------------------------------------------------------------------------------------

/**
 * "Recent" above Browse (redesign 2, AndroidSearch mockup): a 14/20 sp medium label in
 * onSurfaceVariant, then up to [RecentRowsShown] rows of 56 dp: 48 dp art (a song is a cookie, an
 * artist a circle, an album or playlist a 16 dp square, a plain query a 16 dp tile with the
 * history icon), 14 dp gap, title 16/22, kind 13/18, and a 44 dp X. A tap opens the item; the X,
 * a long press or the accessibility action removes it (the screen offers Undo).
 */
@Composable
fun SearchRecentStrip(
    items: List<SearchHistoryItem>,
    onOpen: (SearchHistoryItem) -> Unit,
    onRemove: (SearchHistoryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.search_recent_strip_title),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .semantics { heading() }
        )
        items.take(RecentRowsShown).forEach { item ->
            key(item.key) {
                RecentRow(item = item, onOpen = onOpen, onRemove = onRemove, onFill = null)
            }
        }
    }
}

/** Rows the Browse page shows under "Recent"; the full list is the focused field's panel. */
const val RecentRowsShown = 3

private val RecentArtSquare = RoundedCornerShape(16.dp)
private val ContainerShape = RoundedCornerShape(28.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(
    item: SearchHistoryItem,
    onOpen: (SearchHistoryItem) -> Unit,
    onRemove: (SearchHistoryItem) -> Unit,
    onFill: ((SearchHistoryItem) -> Unit)?,
) {
    val haptic = LocalHapticFeedback.current
    val removeLabel = stringResource(R.string.search_history_remove_action)
    val removeCd = stringResource(R.string.search_history_remove_item_cd, item.title)
    val kind = item.openKind
    val kindText = kindLabel(kind) ?: stringResource(R.string.search_history_kind_query)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = { onOpen(item) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRemove(item)
                },
            )
            .semantics {
                customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(item); true })
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (kind) {
            null -> Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RecentArtSquare)
                    .background(scheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            else -> {
                val shape: Shape = when (kind) {
                    SearchHistoryKind.ARTIST -> CircleShape
                    SearchHistoryKind.SONG -> BadgeShape.Cookie9.toComposeShape()
                    else -> RecentArtSquare
                }
                SmartImage(
                    model = item.imageUrl,
                    contentDescription = null,
                    targetSize = SmartImageListTargetSize,
                    shape = shape,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (kind == null) "\u201C${item.title}\u201D" else item.title,
                style = RedesignType.RowTitle.copy(fontWeight = FontWeight.Normal),
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = kindText,
                style = RedesignType.RowSubtitle,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onFill != null && kind == null) {
            FillArrowButton(item = item, onFill = onFill)
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = { onRemove(item) }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = removeCd, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

// --- The focused, empty field: "Recent searches" ---------------------------------------------------------------

/**
 * The list that takes the genre grid's place while the empty field has focus. Query rows have the history icon, an
 * up-left arrow that puts the text in the field without searching, and an X; rows that opened something show its artwork
 * and kind. "Clear all" sits in the header and asks nothing: the screen offers Undo.
 */
@Composable
fun SearchHistoryPanel(
    items: List<SearchHistoryItem>,
    onOpen: (SearchHistoryItem) -> Unit,
    onFill: (SearchHistoryItem) -> Unit,
    onRemove: (SearchHistoryItem) -> Unit,
    onClearAll: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.search_recent_searches),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            TextButton(onClick = onClearAll) {
                Text(stringResource(R.string.search_action_clear_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items, key = { "history_${it.key}" }, contentType = { if (it.openKind != null) "target" else "query" }) { item ->
                RecentRow(item = item, onOpen = onOpen, onRemove = onRemove, onFill = onFill)
            }
        }
    }
}

@Composable
private fun FillArrowButton(item: SearchHistoryItem, onFill: (SearchHistoryItem) -> Unit) {
    FilledTonalIconButton(
        onClick = { onFill(item) },
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = Icons.Rounded.NorthWest,
            contentDescription = stringResource(R.string.search_history_fill_cd, item.query),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RemoveButton(label: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(imageVector = Icons.Rounded.Close, contentDescription = label, modifier = Modifier.size(18.dp))
    }
}

// --- Typing: matching history rows above the live results ---------------------------------------------------------

/** Up to 5 history rows whose words start with what is typed; a tap fills the field and runs the search. */
@Composable
fun SearchHistorySuggestions(
    items: List<SearchHistoryItem>,
    onPick: (SearchHistoryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Surface(
        shape = ContainerShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            items.forEach { item ->
                ListItem(
                    headlineContent = { Text(text = item.query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(item) },
                )
            }
        }
    }
}

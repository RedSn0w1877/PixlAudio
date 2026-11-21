package com.theveloper.pixelplay.presentation.screens.search.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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

private val ContainerShape = RoundedCornerShape(28.dp)
private val ThumbShape = RoundedCornerShape(14.dp)

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
 * One horizontal row of up to 10 recent items above the genre grid: an artist is a circle, an album or playlist a
 * square, a song or a plain query a chip. A tap opens it; a long press (or the "Remove from recent" accessibility
 * action) removes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchRecentStrip(
    items: List<SearchHistoryItem>,
    onOpen: (SearchHistoryItem) -> Unit,
    onRemove: (SearchHistoryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.search_recent_strip_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
                .semantics { heading() }
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            items(items, key = { "recent_${it.key}" }, contentType = { it.openKind ?: SearchHistoryKind.QUERY }) { item ->
                RecentTile(item = item, onOpen = onOpen, onRemove = onRemove)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentTile(
    item: SearchHistoryItem,
    onOpen: (SearchHistoryItem) -> Unit,
    onRemove: (SearchHistoryItem) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val removeLabel = stringResource(R.string.search_history_remove_action)
    val kind = item.openKind
    // Says what the tile is ("Adele, Artist"), so an artist and an album with one name do not sound alike.
    val spokenKind = kindLabel(kind)
    val spoken = if (spokenKind != null) "${item.title}, $spokenKind" else item.title
    val tileModifier = Modifier
        .combinedClickable(
            onClick = { onOpen(item) },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRemove(item)
            },
        )
        .semantics(mergeDescendants = true) {
            contentDescription = spoken
            customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(item); true })
        }

    when (kind) {
        SearchHistoryKind.ARTIST, SearchHistoryKind.ALBUM, SearchHistoryKind.PLAYLIST -> {
            val shape: Shape = if (kind == SearchHistoryKind.ARTIST) CircleShape else ThumbShape
            Column(
                modifier = Modifier
                    .width(84.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .then(tileModifier)
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SmartImage(
                    model = item.imageUrl,
                    contentDescription = null,
                    targetSize = SmartImageListTargetSize,
                    shape = shape,
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }

        else -> {
            // A song or a query: a chip, centred against the image tiles' picture.
            Box(modifier = Modifier.heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .clip(CircleShape)
                        .then(tileModifier),
                ) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .padding(start = 14.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (kind == SearchHistoryKind.SONG) Icons.Rounded.MusicNote else Icons.Rounded.History,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    }
                }
            }
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
                .padding(start = 24.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.search_recent_searches),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
            TextButton(onClick = onClearAll) {
                Text(stringResource(R.string.search_action_clear_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Surface(
            shape = ContainerShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomPadding)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(items, key = { "history_${it.key}" }, contentType = { if (it.openKind != null) "target" else "query" }) { item ->
                    HistoryRow(item = item, onOpen = onOpen, onFill = onFill, onRemove = onRemove)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: SearchHistoryItem,
    onOpen: (SearchHistoryItem) -> Unit,
    onFill: (SearchHistoryItem) -> Unit,
    onRemove: (SearchHistoryItem) -> Unit,
) {
    val kind = item.openKind
    val removeLabel = stringResource(R.string.search_history_remove_item_cd, item.title)
    ListItem(
        headlineContent = {
            Text(text = item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = kindLabel(kind)?.let { label ->
            { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            if (kind == null) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                SmartImage(
                    model = item.imageUrl,
                    contentDescription = null,
                    targetSize = SmartImageListTargetSize,
                    shape = if (kind == SearchHistoryKind.ARTIST) CircleShape else ThumbShape,
                    modifier = Modifier.size(48.dp),
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (kind == null) {
                    FillArrowButton(item = item, onFill = onFill)
                }
                RemoveButton(label = removeLabel, onClick = { onRemove(item) })
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onOpen(item) },
    )
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

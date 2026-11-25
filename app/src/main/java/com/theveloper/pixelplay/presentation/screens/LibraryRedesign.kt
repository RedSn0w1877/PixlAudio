package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.redesign.GroupPosition
import com.theveloper.pixelplay.presentation.components.redesign.Redesign
import com.theveloper.pixelplay.presentation.components.redesign.RedesignFilterChip
import com.theveloper.pixelplay.presentation.components.redesign.RedesignGroupButton
import com.theveloper.pixelplay.presentation.components.redesign.RedesignHeaderButton
import com.theveloper.pixelplay.presentation.components.redesign.RedesignScreenTitle
import com.theveloper.pixelplay.presentation.components.redesign.RedesignType

/*
 * Library, redesign batch 2 (mockup project/AndroidLibrary.dc.html):
 *
 *   header  48 dp row, 16 dp gutter: "Library" 36/44 sp bold, then 48 dp / 16 dp-corner buttons
 *           (8 dp apart): the tab's main action in primaryContainer (New playlist "+", or Shuffle),
 *           Import (playlists only) and Settings in surfaceContainerHigh.
 *   16 dp
 *   chips   36 dp filter chips, 10 dp corners, 8 dp apart, one per library tab in the user's
 *           order, then an outlined Edit chip (reorder tabs). Selected = secondaryContainer + check.
 *   16 dp
 *   tools   40 dp: sort as a text button on the left ("Date added", 20 dp sort icon, 6 dp gap,
 *           14/20 sp medium); on the right a connected group (2 dp gaps, 44 x 40, 20/6 corners):
 *           Locate / Instrumental filter where the tab has them, then List | Grid on Albums.
 *   grid    2 columns, 12 dp across, 16 dp down; square art with 28 dp corners, 8 dp, title
 *           16/22 medium, subtitle 13/18 onSurfaceVariant.
 */

/** Header row of the Library: title and the square action buttons. */
@Composable
internal fun LibraryRedesignHeader(
    mainActionIcon: androidx.compose.ui.graphics.painter.Painter,
    mainActionLabel: String,
    onMainAction: () -> Unit,
    showImport: Boolean,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    leadingExtra: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Redesign.HeaderButtonSize)
            .padding(horizontal = Redesign.Gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RedesignScreenTitle(
            text = stringResource(R.string.library_screen_title),
            modifier = Modifier.weight(1f),
        )
        leadingExtra()
        if (showImport) {
            RedesignHeaderButton(
                icon = painterResource(R.drawable.rounded_upload_file_24),
                contentDescription = stringResource(R.string.library_cd_import_m3u_playlist),
                onClick = onImport,
                containerColor = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurface,
            )
        }
        RedesignHeaderButton(
            icon = mainActionIcon,
            contentDescription = mainActionLabel,
            onClick = onMainAction,
        )
        RedesignHeaderButton(
            icon = painterResource(R.drawable.rounded_settings_24),
            contentDescription = stringResource(R.string.library_cd_open_settings),
            onClick = onSettings,
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        )
    }
}

/** One chip per library tab (user order), plus the Edit chip that opens the reorder sheet. */
@Composable
internal fun LibraryTabChips(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in titles.indices) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val shown = visible.any { it.index == selectedIndex && it.offset >= 0 &&
                it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
            if (!shown) listState.animateScrollToItem(selectedIndex)
        }
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Redesign.Gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(titles, key = { index, title -> "$index:$title" }) { index, title ->
            RedesignFilterChip(
                label = title,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                modifier = Modifier.semantics { selected = index == selectedIndex },
            )
        }
        item(key = "edit") {
            val cd = stringResource(R.string.library_cd_reorder_tabs)
            RedesignFilterChip(
                label = "",
                selected = false,
                onClick = onEdit,
                leadingIcon = Icons.Rounded.Edit,
                contentDescription = cd,
            )
        }
    }
}

/**
 * The row under the chips: sort (or the folder breadcrumbs) on the left, a connected group of the
 * tab's toggles on the right. Every callback is the one the old LibraryActionRow had.
 */
@Composable
internal fun LibraryToolsRow(
    sortLabel: String?,
    onSortClick: () -> Unit,
    showLocate: Boolean,
    onLocate: () -> Unit,
    showInstrumentalFilter: Boolean,
    instrumentalOnly: Boolean,
    onInstrumentalFilter: () -> Unit,
    /** null when the tab has no list/grid choice. */
    isListView: Boolean?,
    onListView: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    leftContent: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (leftContent != null) {
                leftContent()
            } else if (sortLabel != null) {
                val sortCd = stringResource(R.string.library_cd_sort_options)
                Row(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(role = Role.Button, onClick = onSortClick)
                        .semantics { contentDescription = "$sortCd, $sortLabel" }
                        .padding(start = 8.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                    Text(
                        text = sortLabel,
                        style = RedesignType.Label,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                }
            }
        }
        // The connected group: [Locate] [Instrumental] [List | Grid].
        val buttons = buildList {
            if (showLocate) add(0)
            if (showInstrumentalFilter) add(1)
            if (isListView != null) { add(2); add(3) }
        }
        if (buttons.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Redesign.GroupGap)) {
                buttons.forEachIndexed { i, kind ->
                    val position = when {
                        buttons.size == 1 -> GroupPosition.Single
                        i == 0 -> GroupPosition.First
                        i == buttons.lastIndex -> GroupPosition.Last
                        else -> GroupPosition.Middle
                    }
                    val selected = when (kind) {
                        1 -> instrumentalOnly
                        2 -> isListView == true
                        3 -> isListView == false
                        else -> false
                    }
                    RedesignGroupButton(
                        position = position,
                        selected = selected,
                        onClick = {
                            when (kind) {
                                0 -> onLocate()
                                1 -> onInstrumentalFilter()
                                2 -> onListView(true)
                                3 -> onListView(false)
                            }
                        },
                        modifier = Modifier.width(44.dp),
                        contentPadding = PaddingValues(0.dp),
                        role = if (kind >= 2) Role.RadioButton else Role.Button,
                    ) { tint ->
                        when (kind) {
                            0 -> Icon(Icons.Rounded.MyLocation, stringResource(R.string.library_cd_locate_current_song), tint = tint, modifier = Modifier.size(20.dp))
                            1 -> Icon(
                                painterResource(R.drawable.rounded_music_off_24),
                                stringResource(
                                    if (instrumentalOnly) R.string.library_instrumentalized_filter_on
                                    else R.string.library_instrumentalized_filter_off
                                ),
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                            2 -> Icon(Icons.AutoMirrored.Rounded.ViewList, stringResource(R.string.library_view_mode_list), tint = tint, modifier = Modifier.size(20.dp))
                            else -> Icon(Icons.Rounded.GridView, stringResource(R.string.library_view_mode_grid), tint = tint, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

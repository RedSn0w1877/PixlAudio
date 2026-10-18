package com.theveloper.pixelplay.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.diagnostics.DiagnosticsLog
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.text.SongSearchIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/**
 * State of the "find in this page" search on a list of songs (playlist, album, liked songs).
 * The search text lives here; [results] is computed off the main thread and is null while the
 * search is not filtering.
 */
@Stable
class FindInListState internal constructor() {
    var visible by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    internal var results by mutableStateOf<List<Song>?>(null)

    /** True while a non-blank query is filtering the page. */
    val isFiltering: Boolean get() = visible && query.isNotBlank()

    fun show() {
        if (!visible) {
            visible = true
            DiagnosticsLog.event("FindInList", "opened")
        }
    }

    fun hide() {
        if (visible) DiagnosticsLog.event("FindInList", "closed")
        visible = false
        query = ""
        results = null
    }

    /** The list to show: the filtered songs while searching, otherwise [all]. */
    fun displayed(all: List<Song>): List<Song> = if (isFiltering) results ?: all else all
}

/**
 * Builds the search index once per list (on a background thread) and re-filters 80 ms after the
 * last keystroke, also off the main thread.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Composable
fun rememberFindInList(songs: List<Song>): FindInListState {
    val state = remember { FindInListState() }
    val index by produceState<SongSearchIndex?>(initialValue = null, songs) {
        value = withContext(Dispatchers.Default) { SongSearchIndex(songs) }
    }
    LaunchedEffect(state, index) {
        val current = index ?: return@LaunchedEffect
        snapshotFlow { state.query }
            .distinctUntilChanged()
            .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
            .mapLatest { q ->
                if (q.isBlank()) null
                else withContext(Dispatchers.Default) { current.filter(q) }
            }
            .collect { state.results = it }
    }
    return state
}

private const val SEARCH_DEBOUNCE_MS = 80L

/**
 * Pulling the list down while it is already at the top reveals the search bar; scrolling the list
 * up again while the bar is empty tucks it away. Never consumes scroll, so it cannot fight the list.
 */
@Composable
fun rememberFindPullConnection(state: FindInListState, listState: LazyListState): NestedScrollConnection {
    val thresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    return remember(state, listState, thresholdPx) {
        object : NestedScrollConnection {
            private var pulled = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f) {
                    pulled = 0f
                    if (state.visible && state.query.isEmpty()) state.hide()
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f && !listState.canScrollBackward) {
                    pulled += available.y
                    if (pulled > thresholdPx && !state.visible) {
                        pulled = 0f
                        state.show()
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }
}

/** The Material 3 search field with Cancel. Animates in and out; takes focus when revealed. */
@Composable
fun FindInListBar(state: FindInListState, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val fieldLabel = stringResource(R.string.find_in_list_label)

    BackHandler(enabled = state.visible) {
        keyboard?.hide()
        state.hide()
    }

    AnimatedVisibility(
        visible = state.visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = fieldLabel },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text(fieldLabel) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { state.query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_clear))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                })
            )
            TextButton(onClick = {
                keyboard?.hide()
                state.hide()
            }) { Text(stringResource(R.string.common_cancel)) }
        }
    }

    LaunchedEffect(state.visible) {
        if (state.visible) {
            delay(80)
            runCatching { focusRequester.requestFocus() }
        }
    }
}

/** Shown in place of the list when the query matches nothing. */
@Composable
fun FindInListEmpty(query: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.find_in_list_no_matches, query.trim()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

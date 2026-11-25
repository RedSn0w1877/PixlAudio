package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.screens.search.components.DefaultSearchCategories
import com.theveloper.pixelplay.presentation.screens.search.components.SearchHistoryPanel
import com.theveloper.pixelplay.presentation.screens.search.components.SearchHistoryPrivateHint
import com.theveloper.pixelplay.presentation.screens.search.components.SearchHistorySuggestions
import com.theveloper.pixelplay.presentation.screens.search.components.SearchRecentStrip
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryKind
import com.theveloper.pixelplay.data.searchhistory.SearchHistoryRules
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.LocalFocusManager
import com.theveloper.pixelplay.presentation.components.AdaptiveClickableSurface
import com.theveloper.pixelplay.presentation.components.AdaptiveClickableCard

import com.theveloper.pixelplay.presentation.components.rememberAppListState
import com.theveloper.pixelplay.presentation.components.PlaybackRowState
import androidx.compose.runtime.State
import com.theveloper.pixelplay.presentation.components.rememberIsSongPlaying
import com.theveloper.pixelplay.presentation.components.rememberIsCurrentSong
import com.theveloper.pixelplay.presentation.components.rememberPlaybackRowState
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.navigation.navigateSafelyReplacing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImagePainter

import com.theveloper.pixelplay.presentation.components.MultiSelectionBottomSheet
import com.theveloper.pixelplay.presentation.components.AlbumMultiSelectionOptionSheet
import com.theveloper.pixelplay.presentation.components.PlaylistMultiSelectionBottomSheet
import com.theveloper.pixelplay.presentation.components.GenreMultiSelectionOptionSheet
import com.theveloper.pixelplay.presentation.components.subcomps.SelectionActionRow
import com.theveloper.pixelplay.presentation.components.subcomps.SelectionCountPill
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.toShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Genre
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.presentation.components.redesign.GroupPosition
import com.theveloper.pixelplay.presentation.components.redesign.RedesignGroupButton
import com.theveloper.pixelplay.presentation.components.redesign.RedesignType
import com.theveloper.pixelplay.presentation.components.redesign.RedesignHeaderButton
import com.theveloper.pixelplay.presentation.components.redesign.RedesignScreenTitle
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.SmartImageListTargetSize
import com.theveloper.pixelplay.presentation.components.SongInfoBottomSheet
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import android.util.Log
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.theveloper.pixelplay.presentation.components.NavPillHeight
import com.theveloper.pixelplay.ui.theme.FlexPresets
import com.theveloper.pixelplay.ui.theme.flexStyle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusModifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.PlaylistCover
import com.theveloper.pixelplay.presentation.components.resolveMainScreenBottomGradientHeight
import com.theveloper.pixelplay.presentation.components.resolveNavBarOccupiedHeight
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.screens.search.components.GenreCategoriesGrid
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.utils.formatSongCount
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import timber.log.Timber
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import androidx.compose.ui.res.stringResource

private const val MAX_ALBUM_MULTI_SELECTION = 6

/** What the area under the title shows: the genre grid, the recent searches (the empty field has focus), or results. */
private enum class SearchPane { Browse, History, Results }

private data class SearchUiSlice(
    val selectedSearchFilter: SearchFilterType = SearchFilterType.ALL,
    val searchResults: ImmutableList<SearchResultItem> = persistentListOf()
)

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    paddingValues: PaddingValues,
    playerViewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    navController: NavHostController,
    onSearchBarActiveChange: (Boolean) -> Unit = {}
) {
    // The query is typed in the nav bar's search pill (SplitPillNavigationBar), which writes the
    // view model's searchQuery; this screen only reads it. One source of truth for both.
    val searchQuery = playerViewModel.searchQuery
    // The result list only needs the query when a row is tapped, so it gets a stable reader instead
    // of the String: typing no longer changes anything the list (and its rows) are passed.
    val searchQueryProvider: () -> String = remember(playerViewModel) { { playerViewModel.searchQuery } }
    // A result was tapped: remember the typed query together with what it opened (nothing is written in a private session).
    val onSearchResultSelected: (SearchHistoryKind?, String?, String?, String?) -> Unit = remember(playerViewModel) {
        { kind, targetId, targetTitle, imageUrl ->
            val query = playerViewModel.searchQuery
            if (query.isNotBlank()) {
                playerViewModel.onSearchResultOpened(query, kind, targetId, targetTitle, imageUrl)
            }
        }
    }
    val statusBarTopInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    val bottomGradientHeight = resolveMainScreenBottomGradientHeight(navBarCompactMode)
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Multi-selection state for songs
    val multiSelectionState = playerViewModel.multiSelectionStateHolder
    val selectedSongs by multiSelectionState.selectedSongs.collectAsStateWithLifecycle()
    val isSongSelectionMode by multiSelectionState.isSelectionMode.collectAsStateWithLifecycle()
    val selectedSongIds by multiSelectionState.selectedSongIds.collectAsStateWithLifecycle()
    var showMultiSelectionSheet by remember { mutableStateOf(false) }

    // Multi-selection state for albums
    var selectedAlbums by remember { mutableStateOf<List<Album>>(emptyList()) }
    val selectedAlbumIds = remember(selectedAlbums) { selectedAlbums.map { it.id }.toSet() }
    val isAlbumSelectionMode = selectedAlbums.isNotEmpty()
    var showAlbumMultiSelectionSheet by remember { mutableStateOf(false) }

    // Multi-selection state for playlists
    val playlistSelectionState = playerViewModel.playlistSelectionStateHolder
    val selectedPlaylists by playlistSelectionState.selectedPlaylists.collectAsStateWithLifecycle()
    val isPlaylistSelectionMode by playlistSelectionState.isSelectionMode.collectAsStateWithLifecycle()
    val selectedPlaylistIds by playlistSelectionState.selectedPlaylistIds.collectAsStateWithLifecycle()
    var showPlaylistMultiSelectionSheet by remember { mutableStateOf(false) }

    // Multi-selection state for genres
    var selectedGenres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    val selectedGenreIds = remember(selectedGenres) { selectedGenres.map { it.id }.toSet() }
    val isGenreSelectionMode = selectedGenres.isNotEmpty()
    var showGenreMultiSelectionSheet by remember { mutableStateOf(false) }

    // Playlist bottom sheet songs helper state
    var playlistSheetSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    // Any selection mode check
    val anySelectionMode = isSongSelectionMode || isPlaylistSelectionMode || isAlbumSelectionMode || isGenreSelectionMode

    // BackHandler to clear selections
    BackHandler(enabled = anySelectionMode) {
        multiSelectionState.clearSelection()
        playlistSelectionState.clearSelection()
        selectedAlbums = emptyList()
        selectedGenres = emptyList()
    }

    // Long press and toggle callbacks for songs
    val onSongLongPress: (Song) -> Unit = remember(multiSelectionState, haptic) {
        { song -> 
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            multiSelectionState.toggleSelection(song) 
        }
    }
    val searchUiState by remember(playerViewModel) {
        playerViewModel.playerUiState
            .map { uiState ->
                SearchUiSlice(
                    selectedSearchFilter = uiState.selectedSearchFilter,
                    searchResults = uiState.searchResults
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = SearchUiSlice())
    val currentFilter = searchUiState.selectedSearchFilter
    val genres by playerViewModel.genres.collectAsStateWithLifecycle()
    // The Browse tiles the atlas knows (8.10): a genre it lacks has no tile. Null (every tile, as before) with the legacy engine or no atlas.
    val brainTiles by androidx.compose.runtime.produceState<Set<String>?>(null, playerViewModel) {
        value = playerViewModel.knownGenreTiles(DefaultSearchCategories.map { it.name })
    }
    val tileCategories = remember(brainTiles) {
        brainTiles?.let { known -> DefaultSearchCategories.filter { it.name in known } } ?: DefaultSearchCategories
    }
    val playbackRowState = rememberPlaybackRowState(playerViewModel.stablePlayerState)
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onSearchBarActiveChange(false)
    }
    // Double-tapping Search now focuses the field in the nav pill itself (SplitPillNavigationBar).

    // Search debouncing is centralized in SearchStateHolder.
    // A query that was only copied in from a Recent row's arrow waits for the user (typing or the search key).
    val queryFilledOnly = playerViewModel.searchQueryFilledOnly
    LaunchedEffect(searchQuery, currentFilter, queryFilledOnly) {
        if (!queryFilledOnly) playerViewModel.performSearch(searchQuery)
    }
    val libraryResults = searchUiState.searchResults
    val catalogResults by playerViewModel.catalogSearchResults.collectAsStateWithLifecycle()
    val isCatalogSearching by playerViewModel.isCatalogSearching.collectAsStateWithLifecycle()
    val youtubeMusicResults by playerViewModel.youtubeMusicSearchResults.collectAsStateWithLifecycle()
    val isYoutubeMusicSearching by playerViewModel.isYoutubeMusicSearching.collectAsStateWithLifecycle()

    // Los del catálogo y los de YouTube Music van al final, detrás de todo lo que el usuario
    // ya tiene. Se concatenan aquí y no en el state holder para que la biblioteca pueda
    // pintarse en cuanto Room responde, sin esperar a ninguna petición de red.
    val searchResults = remember(libraryResults, catalogResults, youtubeMusicResults) {
        if (catalogResults.isEmpty() && youtubeMusicResults.isEmpty()) {
            libraryResults
        } else {
            (libraryResults + catalogResults + youtubeMusicResults).toImmutableList()
        }
    }

    val handleSongMoreOptionsClick: (Song) -> Unit = { song ->
        playerViewModel.selectSongForInfo(song)
        showSongInfoBottomSheet = true
    }

    // --- Search history (one behaviour on both apps) ---------------------------------------------------------------
    // Loaded once when the screen appears and again after every write, never while typing; filtering is in memory.
    LaunchedEffect(playerViewModel) { playerViewModel.loadSearchHistory() }
    val history by playerViewModel.searchHistory.collectAsStateWithLifecycle()
    val privateOn by playerViewModel.brainSession.privateSessionActive.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val historySnackbar = remember { SnackbarHostState() }
    val removedMessage = stringResource(R.string.search_history_removed)
    val clearedMessage = stringResource(R.string.search_history_cleared)
    val undoLabel = stringResource(R.string.common_undo)
    val motionScheme = remember { MotionScheme.expressive() }

    // Which pane shows. Read through a derived state, so only a flip recomposes the swap, not each keystroke.
    val pane by remember(playerViewModel) {
        derivedStateOf {
            when {
                playerViewModel.searchQuery.isNotBlank() && !playerViewModel.searchQueryFilledOnly -> SearchPane.Results
                playerViewModel.searchFieldFocused && !privateOn && history.isNotEmpty() -> SearchPane.History
                else -> SearchPane.Browse
            }
        }
    }
    // Up to 5 remembered searches that start with what is typed, above the live results.
    val historySuggestions by remember(playerViewModel) {
        derivedStateOf {
            if (privateOn) emptyList() else SearchHistoryRules.suggest(history, playerViewModel.searchQuery)
        }
    }
    // One row per thing opened: the same artist reached from two typed queries is one tile, not two.
    val shownHistory = remember(history) { SearchHistoryRules.collapseByTarget(history) { it.targetKey } }
    val recentStripItems = remember(shownHistory) { shownHistory.take(SearchHistoryRules.RECENT_STRIP_LIMIT) }
    // The genre grid's scroll survives the swap to the Recent list and back.
    val genreGridState = rememberLazyGridState()

    val runHistoryQuery: (String) -> Unit = { query ->
        playerViewModel.updateSearchQuery(query)
        playerViewModel.onSearchQuerySubmitted(query)
        keyboardController?.hide()
    }
    val removeHistoryItem: (com.theveloper.pixelplay.data.model.SearchHistoryItem) -> Unit = { item ->
        playerViewModel.deleteSearchHistoryItem(item.key)
        scope.launch {
            historySnackbar.currentSnackbarData?.dismiss()
            val result = historySnackbar.showSnackbar(
                message = removedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) playerViewModel.undoSearchHistoryRemoval()
        }
    }
    val clearHistory: () -> Unit = {
        playerViewModel.clearSearchHistory()
        scope.launch {
            historySnackbar.currentSnackbarData?.dismiss()
            val result = historySnackbar.showSnackbar(
                message = clearedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) playerViewModel.undoSearchHistoryRemoval()
        }
    }
    val openHistoryItem: (com.theveloper.pixelplay.data.model.SearchHistoryItem) -> Unit = { item ->
        val kind = item.openKind
        val targetId = item.targetId
        if (kind == null || targetId == null) {
            runHistoryQuery(item.query)
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
            // Using it again moves it back to the top of the history.
            playerViewModel.onSearchResultOpened(item.query, kind, targetId, item.targetTitle, item.imageUrl)
            when (kind) {
                SearchHistoryKind.ALBUM -> targetId.toLongOrNull()?.let {
                    navController.navigateSafelyReplacing(
                        route = Screen.AlbumDetail.createRoute(it),
                        patternToPop = Screen.AlbumDetail.route
                    )
                } ?: runHistoryQuery(item.query)
                SearchHistoryKind.ARTIST -> targetId.toLongOrNull()?.let {
                    navController.navigateSafelyReplacing(
                        route = Screen.ArtistDetail.createRoute(it),
                        patternToPop = Screen.ArtistDetail.route
                    )
                } ?: runHistoryQuery(item.query)
                SearchHistoryKind.PLAYLIST -> navController.navigateSafely(Screen.PlaylistDetail.createRoute(targetId))
                SearchHistoryKind.SONG -> scope.launch {
                    val song = playerViewModel.getSongs(listOf(targetId)).firstOrNull()
                    if (song != null) {
                        playerViewModel.showAndPlaySong(song, listOf(song), "Search: ${item.query}")
                    } else {
                        runHistoryQuery(item.query)
                    }
                }
                SearchHistoryKind.QUERY -> runHistoryQuery(item.query)
            }
        }
    }
    val imeTargetBottom = with(LocalDensity.current) { WindowInsets.imeAnimationTarget.getBottom(this).toDp() }
    // Back (or a swipe) that hides the keyboard leaves the field focused; let go of the focus so the Recent list gives
    // the genre grid back. Only a keyboard that was shown and then hidden counts, so a hardware keyboard never triggers it.
    var imeWasShown by remember { mutableStateOf(false) }
    val imeUp = imeTargetBottom > 8.dp
    LaunchedEffect(imeUp) {
        if (imeUp) {
            imeWasShown = true
        } else if (imeWasShown) {
            imeWasShown = false
            if (playerViewModel.searchFieldFocused) focusManager.clearFocus()
        }
    }
    val hasMiniPlayer by remember(playerViewModel) {
        playerViewModel.stablePlayerState.map { it.currentSong != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val searchbarCornerRadius = 28.dp

    val dm = LocalPixelPlayDarkTheme.current

    val gradientColorsDark = listOf(
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        Color.Transparent
    ).toImmutableList()

    val gradientColorsLight = listOf(
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
        Color.Transparent
    ).toImmutableList()

    val gradientColors = if (dm) gradientColorsDark else gradientColorsLight

    val gradientBrush = remember(gradientColors) {
        Brush.verticalGradient(colors = gradientColors)
    }
    val colorScheme = MaterialTheme.colorScheme
    val bottomGradientBrush = remember(colorScheme.surfaceContainerLowest) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.2f to Color.Transparent,
                0.8f to colorScheme.surfaceContainerLowest,
                1.0f to colorScheme.surfaceContainerLowest
            )
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            onSearchBarActiveChange(false)
            playerViewModel.updateSearchFieldFocused(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Redesign 2: "Search" 36/44 sp bold on the Browse page (AndroidSearch mockup), 28/36 sp
            // once results show (AndroidSearchResults); Settings as a 48 dp neutral square button.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = statusBarTopInset + 12.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RedesignScreenTitle(
                    text = stringResource(R.string.nav_bar_search),
                    style = if (pane == SearchPane.Results) {
                        RedesignType.CompactTitle.copy(lineHeight = 36.sp)
                    } else {
                        RedesignType.ScreenTitle
                    },
                    modifier = Modifier.weight(1f)
                )
                RedesignHeaderButton(
                    icon = painterResource(R.drawable.rounded_settings_24),
                    contentDescription = stringResource(R.string.library_cd_open_settings),
                    onClick = { navController.navigateSafely(Screen.Settings.route) },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            }

            AnimatedContent(
                targetState = pane,
                transitionSpec = {
                    if (targetState != SearchPane.Results && initialState != SearchPane.Results) {
                        // Genre grid <-> recent searches (the field gained or lost focus): a spring cross-fade.
                        fadeIn(animationSpec = motionScheme.defaultEffectsSpec()) togetherWith
                            fadeOut(animationSpec = motionScheme.fastEffectsSpec())
                    } else {
                        val switchingToGenre = targetState != SearchPane.Results
                        val enter = fadeIn(animationSpec = tween(durationMillis = 320, delayMillis = 70)) +
                            slideInVertically(animationSpec = tween(durationMillis = 320)) { fullHeight ->
                                if (switchingToGenre) -fullHeight / 10 else fullHeight / 10
                            }
                        val exit = fadeOut(animationSpec = tween(durationMillis = 220)) +
                            slideOutVertically(animationSpec = tween(durationMillis = 220)) { fullHeight ->
                                if (switchingToGenre) fullHeight / 12 else -fullHeight / 12
                            }
                        (enter togetherWith exit).using(SizeTransform(clip = false))
                    }
                },
                label = "search_mode_transition"
            ) { currentPane ->
                if (currentPane == SearchPane.History) {
                    SearchHistoryPanel(
                        items = shownHistory,
                        onOpen = openHistoryItem,
                        onFill = { item -> playerViewModel.fillSearchQuery(item.query) },
                        onRemove = removeHistoryItem,
                        onClearAll = clearHistory,
                        bottomPadding = if (imeTargetBottom > 8.dp) {
                            imeTargetBottom + NavPillHeight + 24.dp
                        } else {
                            bottomBarHeightDp + 16.dp
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else if (currentPane == SearchPane.Browse) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (privateOn) {
                            SearchHistoryPrivateHint()
                        } else {
                            SearchRecentStrip(
                                items = recentStripItems,
                                onOpen = openHistoryItem,
                                onRemove = removeHistoryItem,
                                modifier = Modifier.padding(top = 20.dp)
                            )
                        }
                        if (isGenreSelectionMode) {
                            SelectionActionRow(
                                selectedCount = selectedGenres.size,
                                onSelectAll = {
                                    selectedGenres = genres
                                },
                                onDeselect = { selectedGenres = emptyList() },
                                onOptionsClick = { showGenreMultiSelectionSheet = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            GenreCategoriesGrid(
                                gridState = genreGridState,
                                genres = genres,
                                onGenreClick = { genre ->
                                    Timber.tag("SearchScreen")
                                        .d("Genre clicked: ${genre.name} (ID: ${genre.id})")
                                    val encodedGenreId = java.net.URLEncoder.encode(genre.id, "UTF-8")
                                    navController.navigateSafely(Screen.GenreDetail.createRoute(encodedGenreId))
                                },
                                // Las categorías no filtran la biblioteca: abren el
                                // explorador del catálogo con esa búsqueda, así que lo que
                                // sale es una selección viva y no las mismas canciones ya
                                // importadas.
                                onCategoryClick = { category ->
                                    val browse: () -> Unit = {
                                        navController.navigateSafely(Screen.SpotifyBrowse.createRoute(category.query))
                                    }
                                    // With the brain engine on a tile is a station when the atlas knows the genre and the
                                    // listener has songs under it (8.3); otherwise it is the live search it always was.
                                    if (brainTiles != null) playerViewModel.startGenreStationByName(category.name, browse) else browse()
                                },
                                categories = tileCategories,
                                playerViewModel = playerViewModel,
                                modifier = Modifier.padding(top = 20.dp),
                                isSelectionMode = isGenreSelectionMode,
                                selectedGenreIds = selectedGenreIds,
                                onGenreLongPress = { genre ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (selectedGenres.any { it.id == genre.id }) {
                                        selectedGenres = selectedGenres.filterNot { it.id == genre.id }
                                    } else {
                                        selectedGenres = selectedGenres + genre
                                    }
                                },
                                onGenreSelectionToggle = { genre ->
                                    if (selectedGenres.any { it.id == genre.id }) {
                                        selectedGenres = selectedGenres.filterNot { it.id == genre.id }
                                    } else {
                                        selectedGenres = selectedGenres + genre
                                    }
                                },
                                getSelectionIndex = { genreId ->
                                    val idx = selectedGenres.indexOfFirst { it.id == genreId }
                                    if (idx >= 0) idx + 1 else null
                                }
                            )

                            SelectionCountPill(
                                selectedCount = selectedGenres.size,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .zIndex(2f)
                                    .padding(top = 16.dp)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        if (anySelectionMode) {
                            val count = when {
                                isSongSelectionMode -> selectedSongs.size
                                isPlaylistSelectionMode -> selectedPlaylists.size
                                isAlbumSelectionMode -> selectedAlbums.size
                                else -> 0
                            }
                            SelectionActionRow(
                                selectedCount = count,
                                onSelectAll = {
                                    when {
                                        isSongSelectionMode -> {
                                            val songsToSelect = searchResults.filterIsInstance<SearchResultItem.SongItem>().map { it.song }
                                            multiSelectionState.selectAll(songsToSelect)
                                        }
                                        isPlaylistSelectionMode -> {
                                            val playlistsToSelect = searchResults.filterIsInstance<SearchResultItem.PlaylistItem>().map { it.playlist }
                                            playlistSelectionState.selectAll(playlistsToSelect)
                                        }
                                        isAlbumSelectionMode -> {
                                            val albumsToSelect = searchResults.filterIsInstance<SearchResultItem.AlbumItem>().map { it.album }
                                            val remaining = MAX_ALBUM_MULTI_SELECTION - selectedAlbums.size
                                            if (remaining <= 0) {
                                                playerViewModel.sendToast(
                                                    context.getString(
                                                        R.string.library_toast_max_albums_selection,
                                                        MAX_ALBUM_MULTI_SELECTION
                                                    )
                                                )
                                            } else {
                                                val toAdd = albumsToSelect.filterNot { selectedAlbumIds.contains(it.id) }.take(remaining)
                                                selectedAlbums = selectedAlbums + toAdd
                                            }
                                        }
                                    }
                                },
                                onDeselect = {
                                    multiSelectionState.clearSelection()
                                    playlistSelectionState.clearSelection()
                                    selectedAlbums = emptyList()
                                },
                                onOptionsClick = {
                                    when {
                                        isSongSelectionMode -> showMultiSelectionSheet = true
                                        isPlaylistSelectionMode -> showPlaylistMultiSelectionSheet = true
                                        isAlbumSelectionMode -> showAlbumMultiSelectionSheet = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp, horizontal = 8.dp)
                            )
                        } else {
// Redesign 2: one connected button group (AndroidSearchResults mockup): 44 dp, 2 dp
                            // gaps, each segment as wide as its share, outer corners 22, inner 8; the selected
                            // one goes primary and fully round.
                            SearchFilterGroup(
                                current = currentFilter,
                                onSelect = { playerViewModel.updateSearchFilter(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 14.dp, bottom = 14.dp)
                            )
                        }
                        if (!anySelectionMode) {
                            SearchHistorySuggestions(
                                items = historySuggestions,
                                onPick = { item -> runHistoryQuery(item.query) },
                            )
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            Crossfade(
                                // Mientras Spotify sigue contestando no se declara vacío:
                                // sin esto la pantalla parpadea a "sin resultados" durante
                                // la petición de red aunque esté a punto de llegar algo.
                                targetState = searchResults.isEmpty() && !isCatalogSearching && !isYoutubeMusicSearching,
                                animationSpec = tween(durationMillis = 190),
                                label = "search_results_fade"
                            ) { isEmpty ->
                                if (isEmpty) {
                                    EmptySearchResults(
                                        searchQuery = searchQuery,
                                        colorScheme = colorScheme
                                    )
                                } else {
                                    SearchResultsList(
                                        results = searchResults,
                                        searchQuery = searchQueryProvider,
                                        playerViewModel = playerViewModel,
                                        onItemSelected = onSearchResultSelected,
                                        playback = playbackRowState,
                                        onSongMoreOptionsClick = handleSongMoreOptionsClick,
                                        navController = navController,
                                        isSelectionMode = isSongSelectionMode,
                                        selectedSongIds = selectedSongIds,
                                        getSelectionIndex = { songId -> multiSelectionState.getSelectionIndex(songId) },
                                        onSongLongPress = onSongLongPress,
                                        selectedAlbums = selectedAlbums,
                                        selectedPlaylists = selectedPlaylists,
                                        isAlbumSelectionMode = isAlbumSelectionMode,
                                        isPlaylistSelectionMode = isPlaylistSelectionMode,
                                        onAlbumLongPress = { album ->
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (selectedAlbums.any { it.id == album.id }) {
                                                selectedAlbums = selectedAlbums.filterNot { it.id == album.id }
                                            } else if (selectedAlbums.size >= MAX_ALBUM_MULTI_SELECTION) {
                                                playerViewModel.sendToast(
                                                    context.getString(
                                                        R.string.library_toast_max_albums_selection,
                                                        MAX_ALBUM_MULTI_SELECTION
                                                    )
                                                )
                                            } else {
                                                selectedAlbums = selectedAlbums + album
                                            }
                                        },
                                        onAlbumSelectionToggle = { album ->
                                            if (selectedAlbums.any { it.id == album.id }) {
                                                selectedAlbums = selectedAlbums.filterNot { it.id == album.id }
                                            } else if (selectedAlbums.size >= MAX_ALBUM_MULTI_SELECTION) {
                                                playerViewModel.sendToast(
                                                    context.getString(
                                                        R.string.library_toast_max_albums_selection,
                                                        MAX_ALBUM_MULTI_SELECTION
                                                    )
                                                )
                                            } else {
                                                selectedAlbums = selectedAlbums + album
                                            }
                                        },
                                        showTopResult = currentFilter == SearchFilterType.ALL && !anySelectionMode,
                                        getAlbumSelectionIndex = { albumId ->
                                            val idx = selectedAlbums.indexOfFirst { it.id == albumId }
                                            if (idx >= 0) idx + 1 else null
                                        },
                                        onPlaylistLongPress = { playlist ->
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            playlistSelectionState.toggleSelection(playlist)
                                        },
                                        onPlaylistSelectionToggle = { playlist ->
                                            playlistSelectionState.toggleSelection(playlist)
                                        },
                                        getPlaylistSelectionIndex = { playlistId ->
                                            playlistSelectionState.getSelectionIndex(playlistId)
                                        }
                                    )
                                }
                            }

                            val count = when {
                                isSongSelectionMode -> selectedSongs.size
                                isPlaylistSelectionMode -> selectedPlaylists.size
                                isAlbumSelectionMode -> selectedAlbums.size
                                else -> 0
                            }
                            SelectionCountPill(
                                selectedCount = count,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .zIndex(2f)
                                    .padding(top = 16.dp)
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(bottomGradientHeight)
                .background(brush = bottomGradientBrush)
        )

        SnackbarHost(
            hostState = historySnackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = if (imeTargetBottom > 8.dp) {
                        imeTargetBottom + NavPillHeight + 16.dp
                    } else {
                        bottomBarHeightDp + 16.dp + (if (hasMiniPlayer) MiniPlayerHeight else 0.dp)
                    }
                )
        )

    }

    if (showSongInfoBottomSheet && selectedSongForInfo != null) {
        val currentSong = selectedSongForInfo
        val isFavorite = remember(currentSong?.id, favoriteSongIds) {
            derivedStateOf {
                currentSong?.let { favoriteSongIds.contains(it.id) }
            }
        }.value ?: false
        val removeFromListTrigger = remember(currentSong) {
            {
                // Re-run the same query (each request gets a fresh id) so the removed row goes.
                playerViewModel.performSearch(playerViewModel.searchQuery)
            }
        }

        if (currentSong != null) {
            SongInfoBottomSheet(
                song = currentSong,
                isFavorite = isFavorite,
                removeFromListTrigger = removeFromListTrigger,
                onToggleFavorite = {
                    playerViewModel.toggleFavoriteSpecificSong(currentSong)
                },
                onDismiss = { showSongInfoBottomSheet = false },
                onPlaySong = {
                    playerViewModel.showAndPlaySong(currentSong)
                },
                onAddToQueue = {
                    playerViewModel.addSongToQueue(currentSong)
                },
                onAddNextToQueue = {
                    playerViewModel.addSongNextToQueue(currentSong)
                },
                onAddToPlayList = {
                    showPlaylistBottomSheet = true;
                },
                onDeleteFromDevice = playerViewModel::deleteFromDevice,
                onNavigateToAlbum = {
                    navController.navigateSafelyReplacing(
                        route = Screen.AlbumDetail.createRoute(currentSong.albumId),
                        patternToPop = Screen.AlbumDetail.route
                    )
                    showSongInfoBottomSheet = false
                },
                onNavigateToArtist = {
                    navController.navigateSafelyReplacing(
                        route = Screen.ArtistDetail.createRoute(currentSong.artistId),
                        patternToPop = Screen.ArtistDetail.route
                    )
                    showSongInfoBottomSheet = false
                },
                onNavigateToArtistById = { artistId ->
                    navController.navigateSafelyReplacing(
                        route = Screen.ArtistDetail.createRoute(artistId),
                        patternToPop = Screen.ArtistDetail.route
                    )
                    showSongInfoBottomSheet = false
                },
                onNavigateToGenre = {
                    currentSong.genre?.let {
                        navController.navigateSafely(Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")))
                    }
                    showSongInfoBottomSheet = false
                },
                onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                    playerViewModel.editSongMetadata(
                        currentSong,
                        newTitle,
                        newArtist,
                        newAlbum,
                        newAlbumArtist,
                        newComposer,
                        newGenre,
                        newLyrics,
                        newTrackNumber,
                        newDiscNumber,
                        replayGainTrackGainDb,
                        replayGainAlbumGainDb,
                        coverArtUpdate
                    )
                },
            )
        }
    }

    // Multi-Selection Bottom Sheet
    if (showMultiSelectionSheet && selectedSongs.isNotEmpty()) {
        val activity = context as? android.app.Activity
        val favoriteIds = favoriteSongIds.toSet()

        MultiSelectionBottomSheet(
            selectedSongs = selectedSongs,
            favoriteSongIds = favoriteIds,
            onDismiss = { showMultiSelectionSheet = false },
            onPlayAll = {
                playerViewModel.playSelectedSongs(selectedSongs)
                showMultiSelectionSheet = false
            },
            onAddToQueue = {
                playerViewModel.addSelectedToQueue(selectedSongs)
                showMultiSelectionSheet = false
            },
            onPlayNext = {
                playerViewModel.addSelectedAsNext(selectedSongs)
                showMultiSelectionSheet = false
            },
            onAddToPlaylist = {
                playlistSheetSongs = selectedSongs
                showMultiSelectionSheet = false
                showPlaylistBottomSheet = true
            },
            onToggleLikeAll = { shouldLike ->
                if (shouldLike) {
                    playerViewModel.likeSelectedSongs(selectedSongs)
                } else {
                    playerViewModel.unlikeSelectedSongs(selectedSongs)
                }
                showMultiSelectionSheet = false
            },
            onShareAll = {
                playerViewModel.shareSelectedAsZip(selectedSongs)
                showMultiSelectionSheet = false
            },
            onDeleteAll = { _, onComplete ->
                activity?.let {
                    playerViewModel.deleteSelectedFromDevice(it, selectedSongs) {
                        showMultiSelectionSheet = false
                        onComplete(true)
                    }
                }
            },
            onBatchEdit = {
                showMultiSelectionSheet = false
            }
        )
    }

    // Album Multi-Selection Option Sheet
    if (showAlbumMultiSelectionSheet && selectedAlbums.isNotEmpty()) {
        AlbumMultiSelectionOptionSheet(
            selectedAlbums = selectedAlbums,
            maxSelection = MAX_ALBUM_MULTI_SELECTION,
            onDismiss = { showAlbumMultiSelectionSheet = false },
            onPlay = {
                playerViewModel.playSelectedAlbums(selectedAlbums)
                selectedAlbums = emptyList()
                showAlbumMultiSelectionSheet = false
            },
            onPlayNext = {
                playerViewModel.addSelectedAlbumsAsNext(selectedAlbums)
                selectedAlbums = emptyList()
                showAlbumMultiSelectionSheet = false
            },
            onAddToQueue = {
                playerViewModel.addSelectedAlbumsToQueue(selectedAlbums)
                selectedAlbums = emptyList()
                showAlbumMultiSelectionSheet = false
            },
            onAddToPlaylist = {
                scope.launch {
                    val songs = playerViewModel.getSongsForAlbums(selectedAlbums)
                    playlistSheetSongs = songs
                    showPlaylistBottomSheet = true
                    selectedAlbums = emptyList()
                    showAlbumMultiSelectionSheet = false
                }
            }
        )
    }

    // Playlist Multi-Selection Bottom Sheet
    if (showPlaylistMultiSelectionSheet && selectedPlaylists.isNotEmpty()) {
        val activity = context as? android.app.Activity

        PlaylistMultiSelectionBottomSheet(
            selectedPlaylists = selectedPlaylists,
            onDismiss = {
                showPlaylistMultiSelectionSheet = false
            },
            onDeleteAll = {
                playlistViewModel.deletePlaylistsInBatch(selectedPlaylistIds.toList())
                showPlaylistMultiSelectionSheet = false
                playlistSelectionState.clearSelection()
            },
            onExportAll = {
                playlistViewModel.exportPlaylistsAsM3u(selectedPlaylistIds.toList())
                showPlaylistMultiSelectionSheet = false
                playlistSelectionState.clearSelection()
            },
            onMergeAll = {
                showPlaylistMultiSelectionSheet = false
                playlistSelectionState.clearSelection()
            },
            onShareAll = {
                activity?.let {
                    playlistViewModel.shareSelectedPlaylistsAsZip(selectedPlaylistIds.toList(), it)
                }
                showPlaylistMultiSelectionSheet = false
                playlistSelectionState.clearSelection()
            }
        )
    }

    // Genre Multi-Selection Option Sheet
    if (showGenreMultiSelectionSheet && selectedGenres.isNotEmpty()) {
        GenreMultiSelectionOptionSheet(
            selectedGenres = selectedGenres,
            onDismiss = { showGenreMultiSelectionSheet = false },
            onPlay = {
                playerViewModel.playSelectedGenres(selectedGenres)
                selectedGenres = emptyList()
                showGenreMultiSelectionSheet = false
            },
            onPlayNext = {
                playerViewModel.addSelectedGenresAsNext(selectedGenres)
                selectedGenres = emptyList()
                showGenreMultiSelectionSheet = false
            },
            onAddToQueue = {
                playerViewModel.addSelectedGenresToQueue(selectedGenres)
                selectedGenres = emptyList()
                showGenreMultiSelectionSheet = false
            },
            onAddToPlaylist = {
                scope.launch {
                    val songs = playerViewModel.getSongsForGenres(selectedGenres)
                    playlistSheetSongs = songs
                    showPlaylistBottomSheet = true
                    selectedGenres = emptyList()
                    showGenreMultiSelectionSheet = false
                }
            }
        )
    }

    // Playlist Bottom Sheet (Single or Multi additions)
    if (showPlaylistBottomSheet) {
        val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
        val songsToAddToPlaylist = if (playlistSheetSongs.isNotEmpty()) {
            playlistSheetSongs
        } else {
            selectedSongForInfo?.let { listOf(it) } ?: emptyList()
        }

        if (songsToAddToPlaylist.isNotEmpty()) {
            PlaylistBottomSheet(
                playlistUiState = playlistUiState,
                songs = songsToAddToPlaylist,
                onDismiss = {
                    showPlaylistBottomSheet = false
                    playlistSheetSongs = emptyList()
                },
                bottomBarHeight = bottomBarHeightDp,
                playerViewModel = playerViewModel,
            )
        }
    }
}

/**
 * Fila de un resultado del catálogo.
 *
 * Se distingue a propósito de las filas de la biblioteca: Spotify sólo entrega metadatos, así
 * que tocarla trae la pista a la biblioteca y la empareja con YouTube ahí mismo (en vez de
 * esperar al lote en segundo plano) antes de reproducirla — la fila muestra un spinner
 * mientras tanto. El corazón sigue siendo un atajo aparte para sólo guardarla sin reproducir.
 */
@Composable
private fun SearchResultCatalogItem(
    track: com.theveloper.pixelplay.data.model.CatalogTrack,
    onLikeClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    var isBusy by remember(track.spotifyId) { mutableStateOf(false) }

    AdaptiveClickableSurface(
        onClick = {
            if (!isBusy) {
                isBusy = true
                onPlayClick()
            }
        },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartImage(
                model = track.albumArtUrl,
                contentDescription = track.title,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(50.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FilledTonalIconButton(
                onClick = {
                    if (!isBusy) {
                        isBusy = true
                        onLikeClick()
                    }
                },
                enabled = !isBusy
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.FavoriteBorder,
                        contentDescription = "Like — adds to your library without playing"
                    )
                }
            }
        }
    }
}

/**
 * Fila de un resultado de buscar directamente en YouTube Music. Sólo tiene una acción (tocar
 * para reproducir) porque, a diferencia del catálogo de Spotify, aquí no hace falta un paso
 * de "sólo guardar" — el `videoId` ya se conoce, así que importar y reproducir es una sola
 * operación instantánea sin esperar a un emparejador.
 */
@Composable
private fun SearchResultYouTubeMusicItem(
    track: com.theveloper.pixelplay.data.model.YouTubeMusicTrack,
    onPlayClick: () -> Unit
) {
    var isBusy by remember(track.videoId) { mutableStateOf(false) }

    AdaptiveClickableSurface(
        onClick = {
            if (!isBusy) {
                isBusy = true
                onPlayClick()
            }
        },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartImage(
                model = track.thumbnailUrl,
                contentDescription = track.title,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(50.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun SearchResultSectionHeader(title: String) {
    // Redesign 2: 18/22 sp bold section titles ("Songs", "Artists", ...).
    Text(
        text = title,
        style = RedesignType.SectionTitle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
            .semantics { heading() }
    )
}

@Composable
fun EmptySearchResults(searchQuery: String, colorScheme: ColorScheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = stringResource(R.string.search_cd_no_results),
            modifier = Modifier
                .size(80.dp)
                .padding(bottom = 16.dp),
            tint = colorScheme.primary.copy(alpha = 0.6f)
        )

        Text(
            text = if (searchQuery.isNotBlank()) {
                stringResource(R.string.search_no_results_for_query, searchQuery)
            } else {
                stringResource(R.string.search_nothing_found)
            },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.search_try_different_or_filters),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}


/** Section order of the result list. A constant, so the LazyColumn content lambda is not rebuilt on every pass. */
private val SEARCH_SECTION_ORDER = listOf(
    SearchFilterType.SONGS,
    SearchFilterType.ALBUMS,
    SearchFilterType.ARTISTS,
    SearchFilterType.PLAYLISTS,
    // Siempre las últimas: lo que el usuario ya tiene va antes que lo que tendría que
    // importar.
    SearchFilterType.CATALOG,
    SearchFilterType.YOUTUBE_MUSIC
)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SearchResultsList(
    results: List<SearchResultItem>,
    searchQuery: () -> String,
    playerViewModel: PlayerViewModel,
    onItemSelected: (SearchHistoryKind?, String?, String?, String?) -> Unit,
    playback: State<PlaybackRowState>,
    onSongMoreOptionsClick: (Song) -> Unit,
    navController: NavHostController,
    isSelectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    getSelectionIndex: (String) -> Int? = { null },
    onSongLongPress: (Song) -> Unit = {},
    selectedAlbums: List<Album> = emptyList(),
    selectedPlaylists: List<Playlist> = emptyList(),
    isAlbumSelectionMode: Boolean = false,
    isPlaylistSelectionMode: Boolean = false,
    onAlbumLongPress: (Album) -> Unit = {},
    onAlbumSelectionToggle: (Album) -> Unit = {},
    getAlbumSelectionIndex: (Long) -> Int? = { null },
    onPlaylistLongPress: (Playlist) -> Unit = {},
    onPlaylistSelectionToggle: (Playlist) -> Unit = {},
    getPlaylistSelectionIndex: (String) -> Int? = { null },
    /** The first song as the big "Top result" card (the All filter, not while selecting). */
    showTopResult: Boolean = false
) {
    val localDensity = LocalDensity.current

    if (results.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.search_no_results_found), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val groupedResults = remember(results) {
        results.groupBy { item ->
            when (item) {
                is SearchResultItem.SongItem -> SearchFilterType.SONGS
                is SearchResultItem.AlbumItem -> SearchFilterType.ALBUMS
                is SearchResultItem.ArtistItem -> SearchFilterType.ARTISTS
                is SearchResultItem.PlaylistItem -> SearchFilterType.PLAYLISTS
                is SearchResultItem.CatalogItem -> SearchFilterType.CATALOG
                is SearchResultItem.YouTubeMusicItem -> SearchFilterType.YOUTUBE_MUSIC
            }
        }
    }
    val songResultsQueue = remember(groupedResults) {
        buildList {
            groupedResults[SearchFilterType.SONGS]
                ?.forEach { item ->
                    val song = (item as? SearchResultItem.SongItem)?.song ?: return@forEach
                    add(song)
                }
        }
    }
    // One click handler for the lifetime of the list: it reads the newest queue / query / callback when a
    // row is tapped (the same values the old per-keystroke lambda had captured), so a keystroke or a
    // late result batch no longer gives every visible row a new onClick to recompose with.
    val latestSongResultsQueue by rememberUpdatedState(songResultsQueue)
    val latestOnItemSelected by rememberUpdatedState(onItemSelected)
    val latestSearchQuery by rememberUpdatedState(searchQuery)
    val onSongResultClick = remember(playerViewModel) {
        { song: Song ->
            val queue = latestSongResultsQueue
            val playbackQueue = if (queue.any { it.id == song.id }) {
                queue
            } else {
                listOf(song)
            }
            val searchQueueName = latestSearchQuery().trim()
                .takeIf { it.isNotEmpty() }
                ?.let { "Search: $it" }
                ?: "Search Results"
            playerViewModel.showAndPlaySong(song, playbackQueue, searchQueueName)
            latestOnItemSelected(SearchHistoryKind.SONG, song.id, song.title, song.albumArtUriString)
        }
    }

    // The search field rides above the keyboard in the nav pill, so with the keyboard up the list
    // has to clear both. The animation *target* changes once per show/hide, not on every frame of
    // the keyboard sliding, so this does not recompose the list while it moves. (It used to read
    // the live inset in px as if it were dp.)
    @OptIn(ExperimentalLayoutApi::class)
    val imeTargetBottom = with(localDensity) {
        WindowInsets.imeAnimationTarget.getBottom(localDensity).toDp()
    }
    val imePadding = if (imeTargetBottom > 8.dp) imeTargetBottom + NavPillHeight + 24.dp else 0.dp
    val systemBarPaddingBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 94.dp

    LazyColumn(
        state = rememberAppListState(),
        modifier = Modifier
            .fillMaxSize()
            .clip(
                RoundedCornerShape(
                    topStart = 28.dp,
                    topEnd = 28.dp
                )
            ),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = if (imePadding <= 8.dp) (MiniPlayerHeight + systemBarPaddingBottom) else imePadding
        )
    ) {
        val topSong = if (showTopResult) {
            (groupedResults[SearchFilterType.SONGS]?.firstOrNull() as? SearchResultItem.SongItem)?.song
        } else null
        if (topSong != null) {
            item(key = "top_result", contentType = "search_top_result") {
                SearchTopResultCard(
                    song = topSong,
                    onClick = { onSongResultClick(topSong) },
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }
        }
        SEARCH_SECTION_ORDER.forEach { filterType ->
            val itemsForSection = groupedResults[filterType] ?: emptyList()

            if (itemsForSection.isNotEmpty()) {
                item(key = "header_${filterType.name}") {
                    SearchResultSectionHeader(
                        title = when (filterType) {
                            SearchFilterType.SONGS -> "Songs"
                            SearchFilterType.ALBUMS -> "Albums"
                            SearchFilterType.ARTISTS -> "Artists"
                            SearchFilterType.PLAYLISTS -> "Playlists"
                            SearchFilterType.CATALOG -> "More on Spotify"
                            SearchFilterType.YOUTUBE_MUSIC -> "From YouTube Music"
                            else -> "Results"
                        }
                    )
                }

                items(
                    count = itemsForSection.size,
                    key = { index ->
                        val item = itemsForSection[index]
                        when (item) {
                            is SearchResultItem.SongItem -> "song_${item.song.id}"
                            is SearchResultItem.AlbumItem -> "album_${item.album.id}"
                            is SearchResultItem.ArtistItem -> "artist_${item.artist.id}"
                            is SearchResultItem.PlaylistItem -> "playlist_${item.playlist.id}_${index}"
                            is SearchResultItem.CatalogItem -> "catalog_${item.track.spotifyId}"
                            is SearchResultItem.YouTubeMusicItem -> "ytmusic_${item.track.videoId}"
                        }
                    },
                    contentType = { index ->
                        when (itemsForSection[index]) {
                            is SearchResultItem.SongItem -> "search_song"
                            is SearchResultItem.AlbumItem -> "search_album"
                            is SearchResultItem.ArtistItem -> "search_artist"
                            is SearchResultItem.PlaylistItem -> "search_playlist"
                            is SearchResultItem.CatalogItem -> "search_catalog"
                            is SearchResultItem.YouTubeMusicItem -> "search_youtube_music"
                        }
                    }
                ) { index ->
                    val item = itemsForSection[index]
                    Box(modifier = Modifier.padding(bottom = 12.dp)) {
                        when (item) {
                            is SearchResultItem.SongItem -> {
                                val isSelected = selectedSongIds.contains(item.song.id)
                                val selectionIndex = getSelectionIndex(item.song.id)
                                EnhancedSongListItem(
                                    song = item.song,
                                    isPlaying = rememberIsSongPlaying(playback, item.song.id).value,
                                    isCurrentSong = rememberIsCurrentSong(playback, item.song.id).value,
                                    onMoreOptionsClick = onSongMoreOptionsClick,
                                    onClick = { onSongResultClick(item.song) },
                                    isSelected = isSelected,
                                    selectionIndex = selectionIndex,
                                    isSelectionMode = isSelectionMode,
                                    onLongPress = { onSongLongPress(item.song) }
                                )
                            }

                            is SearchResultItem.AlbumItem -> {
                                val onPlayClick = remember(item.album, playerViewModel, onItemSelected) {
                                    {
                                        Timber.tag("SearchScreen")
                                            .d("Album clicked: ${item.album.title}")
                                        playerViewModel.playAlbum(item.album)
                                        onItemSelected(SearchHistoryKind.ALBUM, item.album.id.toString(), item.album.title, item.album.albumArtUriString)
                                    }
                                }
                                val onOpenClick = remember(
                                    item.album,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafelyReplacing(
                                            route = Screen.AlbumDetail.createRoute(item.album.id),
                                            patternToPop = Screen.AlbumDetail.route
                                        )
                                        onItemSelected(SearchHistoryKind.ALBUM, item.album.id.toString(), item.album.title, item.album.albumArtUriString)
                                    }
                                }
                                val isSelected = selectedAlbums.any { it.id == item.album.id }
                                val selectionIndex = getAlbumSelectionIndex(item.album.id)
                                SearchResultAlbumItem(
                                    album = item.album,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick,
                                    isSelected = isSelected,
                                    selectionIndex = selectionIndex,
                                    isSelectionMode = isAlbumSelectionMode,
                                    onLongPress = { onAlbumLongPress(item.album) },
                                    onSelectionToggle = { onAlbumSelectionToggle(item.album) }
                                )
                            }

                            is SearchResultItem.ArtistItem -> {
                                val onPlayClick = remember(item.artist, playerViewModel, onItemSelected) {
                                    {
                                        Timber.tag("SearchScreen")
                                            .d("Artist clicked: ${item.artist.name}")
                                        playerViewModel.playArtist(item.artist)
                                        onItemSelected(SearchHistoryKind.ARTIST, item.artist.id.toString(), item.artist.name, item.artist.effectiveImageUrl)
                                    }
                                }
                                val onOpenClick = remember(
                                    item.artist,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafelyReplacing(
                                            route = Screen.ArtistDetail.createRoute(item.artist.id),
                                            patternToPop = Screen.ArtistDetail.route
                                        )
                                        onItemSelected(SearchHistoryKind.ARTIST, item.artist.id.toString(), item.artist.name, item.artist.effectiveImageUrl)
                                    }
                                }
                                SearchResultArtistItem(
                                    artist = item.artist,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick
                                )
                            }

                            is SearchResultItem.PlaylistItem -> {
                                val playlistSongs by remember(item.playlist.songIds, playerViewModel) {
                                    playerViewModel.observeSongs(item.playlist.songIds)
                                }.collectAsStateWithLifecycle(initialValue = emptyList())
                                val coroutineScope = rememberCoroutineScope()
                                val onPlayClick: () -> Unit = {
                                    coroutineScope.launch {
                                        val songs = playerViewModel.getSongs(item.playlist.songIds)
                                        if (songs.isNotEmpty()) {
                                            playerViewModel.playSongs(
                                                songs,
                                                songs.first(),
                                                item.playlist.name
                                            )
                                            if (playerViewModel.stablePlayerState.value.isShuffleEnabled) playerViewModel.toggleShuffle()
                                        } else {
                                            playerViewModel.sendToast("Empty playlist")
                                        }
                                        onItemSelected(SearchHistoryKind.PLAYLIST, item.playlist.id, item.playlist.name, item.playlist.coverImageUri)
                                    }
                                }
                                val onOpenClick = remember(
                                    item.playlist,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafely(Screen.PlaylistDetail.createRoute(item.playlist.id))
                                        onItemSelected(SearchHistoryKind.PLAYLIST, item.playlist.id, item.playlist.name, item.playlist.coverImageUri)
                                    }
                                }
                                val isSelected = selectedPlaylists.any { it.id == item.playlist.id }
                                val selectionIndex = getPlaylistSelectionIndex(item.playlist.id)
                                SearchResultPlaylistItem(
                                    playlist = item.playlist,
                                    playlistSongs = playlistSongs,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick,
                                    isSelected = isSelected,
                                    selectionIndex = selectionIndex,
                                    isSelectionMode = isPlaylistSelectionMode,
                                    onLongPress = { onPlaylistLongPress(item.playlist) },
                                    onSelectionToggle = { onPlaylistSelectionToggle(item.playlist) }
                                )
                            }

                            is SearchResultItem.CatalogItem -> {
                                SearchResultCatalogItem(
                                    track = item.track,
                                    onLikeClick = {
                                        playerViewModel.likeCatalogTrack(
                                            item.track.spotifyId,
                                            item.track.title
                                        )
                                    },
                                    onPlayClick = {
                                        playerViewModel.playCatalogTrack(
                                            item.track.spotifyId,
                                            item.track.title
                                        )
                                        onItemSelected(null, null, null, null)
                                    }
                                )
                            }

                            is SearchResultItem.YouTubeMusicItem -> {
                                SearchResultYouTubeMusicItem(
                                    track = item.track,
                                    onPlayClick = {
                                        playerViewModel.playYouTubeMusicTrack(
                                            item.track.videoId,
                                            item.track.title
                                        )
                                        onItemSelected(null, null, null, null)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultAlbumItem(
    album: Album,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onSelectionToggle: () -> Unit = {}
) {
    val itemShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 26.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusTR = 26.dp,
            smoothnessAsPercentBR = 60,
            cornerRadiusBR = 26.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusBL = 26.dp,
            smoothnessAsPercentTL = 60
        )
    }

    val selectionScale by animateFloatAsState(
        targetValue = if (isSelected) 0.98f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "albumSelectionScale"
    )
    val selectionBorderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "albumSelectionBorder"
    )

    Card(
        shape = itemShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .scale(selectionScale)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = selectionBorderWidth,
                        color = MaterialTheme.colorScheme.primary,
                        shape = itemShape
                    )
                } else {
                    Modifier
                }
            )
            .clip(itemShape)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onSelectionToggle()
                    } else {
                        onOpenClick()
                    }
                },
                onLongClick = onLongPress
            )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmartImage(
                    model = album.albumArtUriString,
                    contentDescription = "Album Art: ${album.title}",
                    targetSize = SmartImageListTargetSize,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(itemShape)
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = album.artist,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledIconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.common_play_album), modifier = Modifier.size(24.dp))
                }
            }
            if (isSelectionMode && isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = selectionIndex?.toString() ?: "✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultArtistItem(
    artist: Artist,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val itemShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 26.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusTR = 26.dp,
            smoothnessAsPercentBR = 60,
            cornerRadiusBR = 26.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusBL = 26.dp,
            smoothnessAsPercentTL = 60
        )
    }

    AdaptiveClickableCard(
        onClick = onOpenClick,
        shape = itemShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!artist.effectiveImageUrl.isNullOrBlank()) {
                SmartImage(
                    model = artist.effectiveImageUrl,
                    contentDescription = "Artist: ${artist.name}",
                    targetSize = SmartImageListTargetSize,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_artist_24),
                    contentDescription = "Artist",
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                        .padding(12.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSongCount(artist.songCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledIconButton(
                onClick = onPlayClick,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play Artist", modifier = Modifier.size(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultPlaylistItem(
    playlist: Playlist,
    playlistSongs: List<Song>,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onSelectionToggle: () -> Unit = {}
) {
    val itemShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 26.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusTR = 26.dp,
            smoothnessAsPercentBR = 60,
            cornerRadiusBR = 26.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusBL = 26.dp,
            smoothnessAsPercentTL = 60
        )
    }

    val selectionScale by animateFloatAsState(
        targetValue = if (isSelected) 0.98f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "playlistSelectionScale"
    )
    val selectionBorderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "playlistSelectionBorder"
    )

    Card(
        shape = itemShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .scale(selectionScale)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = selectionBorderWidth,
                        color = MaterialTheme.colorScheme.primary,
                        shape = itemShape
                    )
                } else {
                    Modifier
                }
            )
            .clip(itemShape)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onSelectionToggle()
                    } else {
                        onOpenClick()
                    }
                },
                onLongClick = onLongPress
            )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaylistCover(
                    playlist = playlist,
                    playlistSongs = playlistSongs,
                    size = 56.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatSongCount(playlist.songIds.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledIconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play Playlist", modifier = Modifier.size(24.dp))
                }
            }
            if (isSelectionMode && isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = selectionIndex?.toString() ?: "✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SearchFilterChip(
    filterType: SearchFilterType,
    currentFilter: SearchFilterType,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val selected = filterType == currentFilter

    val labelResId = when (filterType) {
        SearchFilterType.ALL -> R.string.common_all
        SearchFilterType.SONGS -> R.string.library_tab_songs
        SearchFilterType.ALBUMS -> R.string.library_tab_albums
        SearchFilterType.ARTISTS -> R.string.library_tab_artists
        SearchFilterType.PLAYLISTS -> R.string.library_tab_playlists
        // No son filtros elegibles; nunca se construye un chip para ellos.
        SearchFilterType.CATALOG -> R.string.library_tab_songs
        SearchFilterType.YOUTUBE_MUSIC -> R.string.library_tab_songs
    }

    FilterChip(
        selected = selected,
        onClick = { playerViewModel.updateSearchFilter(filterType) },
        label = { Text(stringResource(labelResId)) },
        modifier = modifier,
        shape = CircleShape,
        border = BorderStroke(
            width = 0.dp,
            color = Color.Transparent
        ),
        colors = FilterChipDefaults.filterChipColors(
            containerColor =  MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
         leadingIcon = if (selected) {
             {
                 Icon(
                     painter = painterResource(R.drawable.rounded_check_circle_24),
                     contentDescription = "Selected",
                     tint = MaterialTheme.colorScheme.onPrimary,
                     modifier = Modifier.size(FilterChipDefaults.IconSize)
                 )
             }
         } else {
             null
         }
    )
}

/** The results filter as one connected group (redesign 2): All, Songs, Artists, Albums, Playlists. */
@Composable
private fun SearchFilterGroup(
    current: SearchFilterType,
    onSelect: (SearchFilterType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = SearchFilterGroupOrder
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        filters.forEachIndexed { index, type ->
            val labelRes = when (type) {
                SearchFilterType.ALL -> R.string.common_all
                SearchFilterType.SONGS -> R.string.library_tab_songs
                SearchFilterType.ALBUMS -> R.string.library_tab_albums
                SearchFilterType.ARTISTS -> R.string.library_tab_artists
                else -> R.string.library_tab_playlists
            }
            RedesignGroupButton(
                position = when (index) {
                    0 -> GroupPosition.First
                    filters.lastIndex -> GroupPosition.Last
                    else -> GroupPosition.Middle
                },
                selected = type == current,
                onClick = { onSelect(type) },
                height = 44.dp,
                inner = 8.dp,
                contentPadding = PaddingValues(horizontal = 6.dp),
                contentColor = MaterialTheme.colorScheme.onSurface,
                role = androidx.compose.ui.semantics.Role.Tab,
                modifier = Modifier.weight(1f)
            ) { tint ->
                Text(
                    text = stringResource(labelRes),
                    style = RedesignType.Label,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private val SearchFilterGroupOrder = listOf(
    SearchFilterType.ALL,
    SearchFilterType.SONGS,
    SearchFilterType.ARTISTS,
    SearchFilterType.ALBUMS,
    SearchFilterType.PLAYLISTS,
)

/**
 * "Top result" (redesign 2, AndroidSearchResults mockup): tertiaryContainer card, 28 dp corners,
 * 14 dp padding, 88 dp art with 20 dp corners, 14 dp gap, "Top result" 12/16 sp medium, the title
 * 26/32 sp bold, artist 14/20, "Song" 13/18; a 64 dp cookie play button on the right. The whole
 * card plays the song (same handler as its row).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchTopResultCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.tertiaryContainer)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SmartImage(
            model = song.albumArtUriString,
            contentDescription = null,
            shape = RoundedCornerShape(20.dp),
            targetSize = TopResultArtSize,
            modifier = Modifier.size(88.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.search_top_result),
                style = RedesignType.Label.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
                color = scheme.onTertiaryContainer
            )
            Text(
                text = song.title,
                style = RedesignType.ScreenTitle.copy(fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
                color = scheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.displayArtist,
                style = RedesignType.Label.copy(fontWeight = FontWeight.Normal),
                color = scheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.search_history_kind_song),
                style = RedesignType.RowSubtitle,
                color = scheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape())
                .background(scheme.onTertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.common_play),
                tint = scheme.tertiaryContainer,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

private val TopResultArtSize = coil.size.Size(264, 264)

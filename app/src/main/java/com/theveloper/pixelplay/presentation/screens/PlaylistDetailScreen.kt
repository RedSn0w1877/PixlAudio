package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.components.AdaptiveAlertDialog
import com.theveloper.pixelplay.presentation.components.AdaptiveModalBottomSheet
import com.theveloper.pixelplay.presentation.components.rememberAppListState
import com.theveloper.pixelplay.presentation.components.rememberIsSongPlaying
import com.theveloper.pixelplay.presentation.components.rememberIsCurrentSong
import com.theveloper.pixelplay.presentation.components.rememberPlaybackRowState
import androidx.compose.material3.Scaffold
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.navigation.navigateSafelyReplacing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Search
import com.theveloper.pixelplay.presentation.components.FindInListBar
import com.theveloper.pixelplay.presentation.components.FindInListEmpty
import com.theveloper.pixelplay.presentation.components.rememberFindInList
import com.theveloper.pixelplay.presentation.components.rememberFindPullConnection
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil.size.Size
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.QueuePlaylistSongItem
import com.theveloper.pixelplay.presentation.components.SongPickerBottomSheet
import com.theveloper.pixelplay.presentation.components.ExpressiveScrollBar
import com.theveloper.pixelplay.presentation.screens.cloudstudio.CloudMessageToasts
import com.theveloper.pixelplay.presentation.screens.cloudstudio.CloudStudioCopy
import com.theveloper.pixelplay.ui.theme.LocalShowScrollbar
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.SongInfoBottomSheet
import com.theveloper.pixelplay.presentation.components.resolveNavBarOccupiedHeight
import com.theveloper.pixelplay.presentation.components.subcomps.TightWrapText
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel.Companion.FOLDER_PLAYLIST_PREFIX
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.performAppCompatHapticFeedback
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistSongsOrderMode
import com.theveloper.pixelplay.utils.formatSongCount
import com.theveloper.pixelplay.utils.formatTotalDuration
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.theveloper.pixelplay.presentation.components.LibrarySortBottomSheet
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.model.PlaylistShapeType
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.presentation.components.CollapsingHeaderHeight
import com.theveloper.pixelplay.presentation.components.ScreenLayer
import com.theveloper.pixelplay.presentation.components.rememberCollapseFraction
import com.theveloper.pixelplay.presentation.components.hero.CollapsingPageHeroHeader
import com.theveloper.pixelplay.presentation.components.hero.PageContentShownFlag
import com.theveloper.pixelplay.presentation.components.hero.PageHeroDefaults
import com.theveloper.pixelplay.presentation.components.hero.PagePlayShuffleGroup
import com.theveloper.pixelplay.presentation.components.hero.rememberCrossfadedPageColorScheme
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import com.theveloper.pixelplay.utils.resolvePlaylistCoverContentColor
import kotlin.math.roundToInt

/*
 * Playlist page, new UI (stage 5). The cover fills the top edge to edge, sharp at the top and
 * progressively blurred into the page (the shared [CollapsingPageHeroHeader], same as the artist
 * page); the name sits over the blurred part in a fit-by-width Google Sans Flex cut that collapses
 * into the top bar; under it the owner / counts line and Play + Shuffle as an M3 Expressive
 * connected button group. Below: Add / Remove / Reorder, find-in-list and the songs, as before.
 * The page takes the cover's colours (the album palette cache), fading them in on a first visit.
 *
 * Performance: the header's height and collapse fraction are read only in its own measure /
 * placement and in layer and draw blocks, and the list follows it through an offset read in
 * placement, so scrolling never recomposes the header or the list.
 */

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(
    ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBackClick: () -> Unit,
    onDeletePlayListClick: () -> Unit,
    playerViewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    navController: NavController
) {
    val uiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val playbackRowState = rememberPlaybackRowState(playerViewModel.stablePlayerState)
    val hasCurrentSong by remember(playbackRowState) {
        derivedStateOf { playbackRowState.value.hasCurrentSong }
    }
    val context = LocalContext.current
    val fallbackPlaylistName = stringResource(R.string.common_playlist)
    val sortSongsLabel = stringResource(R.string.playlist_sort_songs_title)
    val moreOptionsLabel = stringResource(R.string.playlist_more_options_title)
    val addSongsCd = stringResource(R.string.playlist_cd_add_songs)
    val addLabel = stringResource(R.string.playlist_action_add_songs)
    val removeLabel = stringResource(R.string.playlist_action_remove_songs)
    val removeSongsCd = stringResource(R.string.playlist_cd_remove_songs)
    val reorderLabel = stringResource(R.string.playlist_action_reorder_songs)
    val reorderSongsCd = stringResource(R.string.playlist_cd_reorder_songs)
    val reorderSongCd = stringResource(R.string.playlist_cd_reorder_songs)
    val playlistEmptyTitle = stringResource(R.string.playlist_empty_title)
    val playlistEmptyFolder = stringResource(R.string.playlist_empty_folder_label)
    val playlistEmptyAddHint = stringResource(R.string.playlist_empty_add_hint)
    val playlistOptionsTitle = stringResource(R.string.playlist_options_title)
    val editPlaylistLabel = stringResource(R.string.playlist_action_edit_playlist)
    val deletePlaylistLabel = stringResource(R.string.playlist_action_delete_playlist)
    val setDefaultTransitionLabel = stringResource(R.string.playlist_action_set_default_transition)
    val exportPlaylistLabel = stringResource(R.string.playlist_action_export_playlist)
    val deletePlaylistConfirmTitle = stringResource(R.string.playlist_dialog_delete_title)
    val deletePlaylistConfirmBody = stringResource(R.string.playlist_dialog_delete_body)
    val sortSheetTitle = stringResource(R.string.playlist_sort_songs_title)
    val toastAddedToQueue = stringResource(R.string.library_toast_added_to_queue)
    val toastPlayingNext = stringResource(R.string.library_toast_playing_next)
    val currentPlaylist = uiState.currentPlaylistDetails
    val isFolderPlaylist = currentPlaylist?.id?.startsWith(FOLDER_PLAYLIST_PREFIX) == true
    val songsInPlaylist = uiState.currentPlaylistSongs
    val cloudStudio: com.theveloper.pixelplay.presentation.viewmodel.CloudStudioViewModel = hiltViewModel()
    CloudMessageToasts(cloudStudio)

    LaunchedEffect(playlistId) {
        playlistViewModel.loadPlaylistDetails(playlistId)
    }

    var showAddSongsSheet by remember { mutableStateOf(false) }

    var isReorderModeEnabled by remember { mutableStateOf(false) }
    var isRemoveModeEnabled by remember { mutableStateOf(false) }
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var showPlaylistOptionsSheet by remember { mutableStateOf(false) }
    var showEditPlaylistDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val m3uExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        uri?.let {
            currentPlaylist?.let { playlist ->
                playlistViewModel.exportM3u(playlist, it, context)
            }
        }
    }

    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle() // Reintroducir favoriteIds aquí
    val stableOnMoreOptionsClick: (Song) -> Unit = remember {
        { song ->
            playerViewModel.selectSongForInfo(song)
            showSongInfoBottomSheet = true
        }
    }
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    var localReorderableSongs by remember(playlistId) { mutableStateOf(songsInPlaylist) }

    val listState = rememberAppListState()
    val findState = rememberFindInList(localReorderableSongs)
    val findPullConnection = rememberFindPullConnection(findState, listState)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val appHapticsConfig = LocalAppHapticsConfig.current
    var hasPendingSongOrder by remember(playlistId) { mutableStateOf(false) }

    val reorderableState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            val fromIndex = localReorderableSongs.indexOfFirst { it.id == from.key }
            val toIndex = localReorderableSongs.indexOfFirst { it.id == to.key }
            if (fromIndex >= 0 && toIndex >= 0) {
                localReorderableSongs = localReorderableSongs.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
                hasPendingSongOrder = true
            }
        }
    )

    LaunchedEffect(reorderableState.isAnyItemDragging, songsInPlaylist, isFolderPlaylist) {
        if (!reorderableState.isAnyItemDragging) {
            if (hasPendingSongOrder && !isFolderPlaylist) {
                currentPlaylist?.let {
                    playlistViewModel.savePlaylistSongOrder(it.id, localReorderableSongs.map { song -> song.id })
                }
                hasPendingSongOrder = false
            } else {
                localReorderableSongs = songsInPlaylist
            }
        }
    }

    // --- Portada del héroe ---
    // Imagen propia de la lista; si la lista tiene color + icono propios, ese color llena el
    // héroe; si no, la carátula de la primera canción (por el orden guardado, así que ordenar la
    // vista no cambia la portada), como el collage de la biblioteca.
    val heroImageUrl = remember(
        currentPlaylist?.coverImageUri,
        currentPlaylist?.coverColorArgb,
        currentPlaylist?.songIds,
        songsInPlaylist
    ) {
        resolvePlaylistHeroImage(currentPlaylist, songsInPlaylist)
    }

    // --- Paleta dinámica de la portada (la misma caché que álbumes y reproductor) ---
    val isDarkTheme = LocalPixelPlayDarkTheme.current
    val baseColorScheme = MaterialTheme.colorScheme
    val heroColorSchemeFlow = remember(heroImageUrl) {
        heroImageUrl?.let { playerViewModel.themeStateHolder.getAlbumColorSchemeFlow(it, eager = false) }
    }
    val heroColorSchemePair = heroColorSchemeFlow?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(heroImageUrl) {
        heroImageUrl?.let { playerViewModel.themeStateHolder.ensureAlbumColorScheme(it) }
    }
    val targetPageColorScheme = remember(heroColorSchemePair, isDarkTheme, baseColorScheme) {
        heroColorSchemePair?.let { pair -> if (isDarkTheme) pair.dark else pair.light } ?: baseColorScheme
    }
    val pageContentShown = remember { PageContentShownFlag() }
    val pageContentVisible = currentPlaylist != null
    SideEffect {
        pageContentShown.shown = pageContentVisible
        pageContentShown.darkTheme = isDarkTheme
    }
    val pageColorScheme = rememberCrossfadedPageColorScheme(
        target = targetPageColorScheme,
        // Only a palette that arrives after the page is already on screen fades in.
        animateChange = remember(targetPageColorScheme) {
            pageContentShown.shown && pageContentShown.darkTheme == isDarkTheme
        }
    )

    // --- Cabecera colapsable (la misma que la del artista) ---
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = PageHeroDefaults.BarHeight + statusBarHeight
    val maxTopBarHeight = remember(configuration.screenWidthDp, configuration.screenHeightDp, statusBarHeight) {
        PageHeroDefaults.maxHeight(configuration.screenWidthDp, configuration.screenHeightDp, statusBarHeight)
    }
    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }
    val headerImageRequestSize = remember(configuration.screenWidthDp, density.density, maxTopBarHeightPx) {
        Size(
            width = with(density) { configuration.screenWidthDp.dp.roundToPx() },
            height = maxTopBarHeightPx.roundToInt()
        )
    }
    val topBarHeight = remember { CollapsingHeaderHeight(maxTopBarHeightPx) }
    // Read only inside derivedStateOf and the header's layout / draw: the per-frame header height
    // never recomposes the screen body, its list or the header itself.
    val collapseFractionState = rememberCollapseFraction(topBarHeight, minTopBarHeightPx, maxTopBarHeightPx)

    val headerScrollConnection = remember(minTopBarHeightPx, maxTopBarHeightPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0
                // Hacia arriba con la lista ya desplazada: el scroll es de la lista, no de la cabecera.
                if (!isScrollingDown && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
                    return Offset.Zero
                }
                val previousHeight = topBarHeight.value
                val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight
                if (consumed.roundToInt() != 0) {
                    topBarHeight.snapTo(newHeight)
                }
                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            val targetValue = if (shouldExpand && canExpand) maxTopBarHeightPx else minTopBarHeightPx
            if (topBarHeight.value != targetValue) {
                scope.launch {
                    topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium))
                }
            }
        }
    }

    val songCountText = formatSongCount(songsInPlaylist.size)
    val totalDurationText = remember(songsInPlaylist) { formatTotalDuration(songsInPlaylist) }
    val ownerText = when {
        currentPlaylist == null -> null
        currentPlaylist.source == SpotifyRepository.SPOTIFY_PLAYLIST_SOURCE -> stringResource(R.string.playlist_owner_spotify)
        // Taizo and the older AI generator both only set isAiGenerated, so the app can't tell
        // which one made it: say AI rather than claim Taizo.
        currentPlaylist.isAiGenerated -> stringResource(R.string.playlist_owner_ai)
        else -> null
    }
    val countsText = stringResource(R.string.playlist_header_meta, songCountText, totalDurationText)
    val metaLine = if (ownerText != null) stringResource(R.string.playlist_header_meta, ownerText, countsText) else countsText

    val playInOrder: () -> Unit = {
        if (localReorderableSongs.isNotEmpty() && currentPlaylist != null) {
            playerViewModel.playSongs(
                localReorderableSongs,
                localReorderableSongs.first(),
                currentPlaylist.name
            )
            if (playerViewModel.stablePlayerState.value.isShuffleEnabled) playerViewModel.toggleShuffle()
        }
    }
    val playShuffled: () -> Unit = {
        if (localReorderableSongs.isNotEmpty() && currentPlaylist != null) {
            playerViewModel.playSongsShuffled(
                songsToPlay = localReorderableSongs,
                queueName = currentPlaylist.name,
                playlistId = currentPlaylist.id,
                startAtZero = true,
            )
        }
    }
    val toggleFind: () -> Unit = {
        if (findState.visible) {
            findState.hide()
        } else {
            findState.show()
            // The search field is the list's first row: bring it into view under the bar.
            if (listState.firstVisibleItemIndex > 1) scope.launch { listState.scrollToItem(0) }
        }
    }

    MaterialTheme(
        colorScheme = pageColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        when {
            uiState.isLoading && currentPlaylist == null -> {
                PlaylistStatusPlaceholder(onBackClick = onBackClick) { CircularProgressIndicator() }
            }
            uiState.playlistNotFound -> {
                PlaylistStatusPlaceholder(onBackClick = onBackClick) {
                    Text(stringResource(id = R.string.playlist_not_found))
                }
            }
            currentPlaylist == null -> {
                PlaylistStatusPlaceholder(onBackClick = onBackClick) { CircularProgressIndicator() }
            }
            else -> {
                val playlistName = currentPlaylist.name
                val isScrollbarEnabled = LocalShowScrollbar.current
                val showScrollBar by remember(isScrollbarEnabled) {
                    derivedStateOf {
                        isScrollbarEnabled &&
                            collapseFractionState.value > 0.95f &&
                            (listState.canScrollForward || listState.canScrollBackward)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(headerScrollConnection)
                ) {
                    ScreenLayer {
                        val shownSongs = findState.displayed(localReorderableSongs)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .offset {
                                    // Read in placement: the list follows the header without recomposing.
                                    IntOffset(0, (topBarHeight.value - minTopBarHeightPx).roundToInt())
                                }
                                .nestedScroll(findPullConnection),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(
                                top = minTopBarHeight + 8.dp,
                                bottom = MiniPlayerHeight + systemNavBarInset + 16.dp,
                                start = 12.dp,
                                end = if (showScrollBar) 24.dp else 12.dp
                            )
                        ) {
                            if (!isFolderPlaylist) {
                                item(key = "playlist_edit_tools", contentType = "playlist_edit_tools") {
                                    PlaylistEditToolsRow(
                                        isReorderModeEnabled = isReorderModeEnabled,
                                        isRemoveModeEnabled = isRemoveModeEnabled,
                                        addLabel = addLabel,
                                        addSongsCd = addSongsCd,
                                        removeLabel = removeLabel,
                                        removeSongsCd = removeSongsCd,
                                        reorderLabel = reorderLabel,
                                        reorderSongsCd = reorderSongsCd,
                                        onAddClick = { showAddSongsSheet = true },
                                        onToggleRemove = { isRemoveModeEnabled = !isRemoveModeEnabled },
                                        onToggleReorder = { isReorderModeEnabled = !isReorderModeEnabled },
                                    )
                                }
                            }
                            item(key = "playlist_find_bar", contentType = "playlist_find_bar") {
                                FindInListBar(findState)
                            }
                            if (localReorderableSongs.isEmpty()) {
                                item(key = "playlist_empty", contentType = "playlist_empty") {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 48.dp, bottom = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Filled.MusicOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(8.dp))
                                        Text(playlistEmptyTitle, style = MaterialTheme.typography.titleMedium)
                                        val emptyMessage = if (isFolderPlaylist) playlistEmptyFolder else playlistEmptyAddHint
                                        Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            } else if (findState.isFiltering && shownSongs.isEmpty()) {
                                item(key = "playlist_find_empty", contentType = "playlist_find_empty") {
                                    FindInListEmpty(findState.query)
                                }
                            } else {
                                itemsIndexed(
                                    shownSongs,
                                    key = { _, item -> item.id },
                                    contentType = { _, _ -> "playlist_song" }
                                ) { _, song ->
                                    ReorderableItem(
                                        state = reorderableState,
                                        key = song.id,
                                        enabled = !findState.isFiltering,
                                    ) { isDragging ->
                                        val scale by animateFloatAsState(
                                            if (isDragging) 1.05f else 1f,
                                            label = "scale"
                                        )

                                        QueuePlaylistSongItem(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .graphicsLayer {
                                                    scaleX = scale
                                                    scaleY = scale
                                                },
                                            onClick = {
                                                playerViewModel.playSongs(
                                                    shownSongs,
                                                    song,
                                                    currentPlaylist.name,
                                                    currentPlaylist.id
                                                )
                                            },
                                            song = song,
                                            isCurrentSong = rememberIsCurrentSong(playbackRowState, song.id).value,
                                            isPlaying = rememberIsSongPlaying(playbackRowState, song.id).value,
                                            isDragging = isDragging,
                                            onRemoveClick = {
                                                if (!isFolderPlaylist) {
                                                    currentPlaylist.let {
                                                        playlistViewModel.removeSongFromPlaylist(it.id, song.id)
                                                    }
                                                }
                                            },
                                            isFromPlaylist = true,
                                            isReorderModeEnabled = isReorderModeEnabled,
                                            isDragHandleVisible = isReorderModeEnabled && !findState.isFiltering,
                                            isRemoveButtonVisible = isRemoveModeEnabled,
                                            onMoreOptionsClick = stableOnMoreOptionsClick,
                                            dragHandle = {
                                                IconButton(
                                                    onClick = {},
                                                    modifier = Modifier
                                                        .draggableHandle(
                                                            onDragStarted = {
                                                                performAppCompatHapticFeedback(
                                                                    view,
                                                                    appHapticsConfig,
                                                                    HapticFeedbackConstantsCompat.GESTURE_START
                                                                )
                                                            },
                                                            onDragStopped = {
                                                                performAppCompatHapticFeedback(
                                                                    view,
                                                                    appHapticsConfig,
                                                                    HapticFeedbackConstantsCompat.GESTURE_END
                                                                )
                                                            }
                                                        )
                                                        .size(40.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.DragIndicator,
                                                        contentDescription = reorderSongCd,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (showScrollBar) {
                            ExpressiveScrollBar(
                                listState = listState,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(
                                        top = minTopBarHeight + 12.dp,
                                        bottom = if (hasCurrentSong) MiniPlayerHeight + systemNavBarInset + 20.dp else systemNavBarInset + 16.dp,
                                        end = 4.dp
                                    )
                            )
                        }

                        CollapsingPageHeroHeader(
                            title = playlistName,
                            imageUrl = heroImageUrl,
                            imageContentDescription = stringResource(R.string.playlist_cd_cover, playlistName),
                            imageRequestSize = headerImageRequestSize,
                            height = topBarHeight,
                            collapseFraction = collapseFractionState,
                            minHeightPx = minTopBarHeightPx,
                            maxHeightPx = maxTopBarHeightPx,
                            placeholder = { PlaylistHeroPlaceholder(playlist = currentPlaylist) },
                            navigation = {
                                FilledIconButton(
                                    onClick = onBackClick,
                                    colors = PageHeroDefaults.iconButtonColors(),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back))
                                }
                            },
                            actions = {
                                val heroButtonColors = PageHeroDefaults.iconButtonColors()
                                FilledIconButton(
                                    onClick = toggleFind,
                                    colors = heroButtonColors,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.find_in_list_label))
                                }
                                FilledIconButton(
                                    onClick = { playerViewModel.showSortingSheet() },
                                    colors = heroButtonColors,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = sortSongsLabel)
                                }
                                if (!isFolderPlaylist) {
                                    FilledIconButton(
                                        onClick = { showPlaylistOptionsSheet = true },
                                        colors = heroButtonColors,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(Icons.Filled.MoreVert, moreOptionsLabel)
                                    }
                                }
                            },
                            bottomContent = {
                                Text(
                                    text = metaLine,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                PagePlayShuffleGroup(
                                    enabled = localReorderableSongs.isNotEmpty(),
                                    onPlayClick = playInOrder,
                                    onShuffleClick = playShuffled,
                                    shuffleContentDescription = stringResource(R.string.playlist_cd_shuffle_play)
                                )
                            },
                            titleExpandedSize = PlaylistTitleExpandedSize
                        )
                    }
                }
            }
        }
    }

    if (showAddSongsSheet && currentPlaylist != null && !isFolderPlaylist) {
        SongPickerBottomSheet(
            playerViewModel = playerViewModel,
            initiallySelectedSongIds = currentPlaylist.songIds.toSet(),
            onDismiss = { showAddSongsSheet = false },
            onConfirm = { selectedIds ->
                playlistViewModel.addSongsToPlaylist(currentPlaylist.id, selectedIds.toList())
                showAddSongsSheet = false
            }
        )
    }
    if (showPlaylistOptionsSheet && !isFolderPlaylist) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        AdaptiveModalBottomSheet(
            onDismissRequest = { showPlaylistOptionsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 4.dp,
//            dragHandle = {
//                SheetDefaults.DragHandle(
//                    color = MaterialTheme.colorScheme.onSurfaceVariant
//                )
//            }
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = playlistOptionsTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    currentPlaylist?.name?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                PlaylistActionItem(
                    icon = painterResource(R.drawable.rounded_edit_24),
                    label = editPlaylistLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        showEditPlaylistDialog = true
                    }
                )
                PlaylistActionItem(
                    icon = painterResource(R.drawable.rounded_delete_24),
                    label = deletePlaylistLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        showDeleteConfirmation = true
                    }
                )
                PlaylistActionItem(
                    icon = painterResource(R.drawable.outline_graph_1_24),
                    label = setDefaultTransitionLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        navController.navigateSafely(Screen.EditTransition.createRoute(playlistId))
                    }
                )
                PlaylistActionItem(
                    icon = painterResource(R.drawable.rounded_attach_file_24),
                    label = exportPlaylistLabel,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        val sanitizedName = PlaylistViewModel.sanitizeFileName(currentPlaylist?.name ?: fallbackPlaylistName)
                        m3uExportLauncher.launch("$sanitizedName.m3u")
                    }
                )
                PlaylistActionItem(
                    icon = rememberVectorPainter(Icons.Rounded.FileDownload),
                    label = stringResource(R.string.playlist_download_all_label),
                    onClick = {
                        showPlaylistOptionsSheet = false
                        playlistViewModel.downloadAllSongsInCurrentPlaylist()
                    }
                )
                PlaylistActionItem(
                    icon = rememberVectorPainter(Icons.Rounded.AutoAwesome),
                    label = stringResource(R.string.playlist_instrumentalize_all_label),
                    onClick = {
                        showPlaylistOptionsSheet = false
                        playlistViewModel.instrumentalizeAllSongsInCurrentPlaylist()
                    }
                )
                // Always offered; with Cloud processing off the toast says where to turn it on.
                PlaylistActionItem(
                    icon = rememberVectorPainter(Icons.Rounded.CloudUpload),
                    label = stringResource(R.string.playlist_process_all_cloud_label),
                    supportingText = CloudStudioCopy.WIFI_HINT,
                    onClick = {
                        showPlaylistOptionsSheet = false
                        cloudStudio.startNow(songsInPlaylist, currentPlaylist?.name ?: fallbackPlaylistName)
                    }
                )
            }
            }
        }
    }

    if (showEditPlaylistDialog && currentPlaylist != null) {
        val initialShapeType = try {
            currentPlaylist.coverShapeType?.let { PlaylistShapeType.valueOf(it) } ?: PlaylistShapeType.Circle
        } catch (e: Exception) {
            PlaylistShapeType.Circle
        }
        
        EditPlaylistDialog(
            visible = showEditPlaylistDialog,
            currentName = currentPlaylist.name,
            currentImageUri = currentPlaylist.coverImageUri,
            currentColor = currentPlaylist.coverColorArgb,
            currentIconName = currentPlaylist.coverIconName,
            currentShapeType = initialShapeType,
            currentShapeDetail1 = currentPlaylist.coverShapeDetail1,
            currentShapeDetail2 = currentPlaylist.coverShapeDetail2,
            currentShapeDetail3 = currentPlaylist.coverShapeDetail3,
            currentShapeDetail4 = currentPlaylist.coverShapeDetail4,
            onDismiss = { showEditPlaylistDialog = false },
            onSave = { name, imageUri, color, icon, scale, panX, panY, shapeType, d1, d2, d3, d4 ->
                playlistViewModel.updatePlaylistParameters(
                    playlistId = currentPlaylist.id,
                    name = name,
                    coverImageUri = imageUri,
                    coverColor = color,
                    coverIcon = icon,
                    cropScale = scale,
                    cropPanX = panX,
                    cropPanY = panY,
                    coverShapeType = shapeType,
                    coverShapeDetail1 = d1,
                    coverShapeDetail2 = d2,
                    coverShapeDetail3 = d3,
                    coverShapeDetail4 = d4
                )
                showEditPlaylistDialog = false
            }
        )
    }
    if (showDeleteConfirmation && currentPlaylist != null) {
        AdaptiveAlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(deletePlaylistConfirmTitle) },
            text = {
                Text(deletePlaylistConfirmBody)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        playlistViewModel.deletePlaylist(currentPlaylist.id)
                        onDeletePlayListClick()
                        showDeleteConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.common_delete), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }

    if (showSongInfoBottomSheet && selectedSongForInfo != null) {
        val currentSong = selectedSongForInfo
        val isFavorite = remember(currentSong?.id, favoriteIds) {
            derivedStateOf {
                currentSong?.let {
                    favoriteIds.contains(
                        it.id
                    )
                }
            }
        }.value ?: false

        if (currentSong != null) {
            SongInfoBottomSheet(
                song = currentSong,
                isFavorite = isFavorite,
                onToggleFavorite = {
                    // Directly use PlayerViewModel's method to toggle, which should handle UserPreferencesRepository
                    playerViewModel.toggleFavoriteSpecificSong(currentSong) // Assumes such a method exists or will be added to PlayerViewModel
                },
                onDismiss = { showSongInfoBottomSheet = false },
                onPlaySong = {
                    playerViewModel.showAndPlaySong(currentSong)
                },
                onAddToQueue = {
                    playerViewModel.addSongToQueue(currentSong) // Assumes such a method exists or will be added
                    playerViewModel.sendToast(toastAddedToQueue)
                },
                onAddNextToQueue = {
                    playerViewModel.addSongNextToQueue(currentSong)
                    playerViewModel.sendToast(toastPlayingNext)
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
                        navController.navigateSafelyReplacing(
                            route = Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")),
                            patternToPop = Screen.GenreDetail.route
                        )
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
                removeFromListTrigger = {
                    playlistViewModel.removeSongFromPlaylist(playlistId, currentSong.id)
                }
            )
            if (showPlaylistBottomSheet) {
                val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

                PlaylistBottomSheet(
                    playlistUiState = playlistUiState,
                    songs = listOf(currentSong),
                    onDismiss = {
                        showPlaylistBottomSheet = false
                    },
                    currentPlaylistId = playlistId,
                    bottomBarHeight = bottomBarHeightDp,
                    playerViewModel = playerViewModel,
                )
            }
        }
    }

    val isSortSheetVisible by playerViewModel.isSortingSheetVisible.collectAsStateWithLifecycle()

    if (isSortSheetVisible) {
        // Check if playlist is in Manual mode (which corresponds to Default Order)
        val isManualMode = uiState.playlistSongsOrderMode is PlaylistSongsOrderMode.Manual
        val rawOption = uiState.currentPlaylistSongsSortOption
        // If in Manual mode, show SongDefaultOrder as selected; otherwise use the stored sort option
        val currentSortOption = if (isManualMode) {
            SortOption.SongDefaultOrder
        } else if (currentPlaylist != null) {
            rawOption
        } else {
            SortOption.SongTitleAZ
        }

        // Build options list inline to avoid potential static initialization issues
        val songSortOptions = listOf(
            SortOption.SongDefaultOrder,
            SortOption.SongTitleAZ,
            SortOption.SongTitleZA,
            SortOption.SongArtist,
            SortOption.SongArtistDesc,
            SortOption.SongAlbum,
            SortOption.SongAlbumDesc,
            SortOption.SongDateAdded,
            SortOption.SongDateAddedAsc,
            SortOption.SongDuration,
            SortOption.SongDurationAsc
        )

        LibrarySortBottomSheet(
            title = sortSheetTitle,
            options = songSortOptions,
            selectedOption = currentSortOption,
            onDismiss = { playerViewModel.hideSortingSheet() },
            onOptionSelected = { option ->
                 playlistViewModel.sortPlaylistSongs(option)
                 playerViewModel.hideSortingSheet()
                 // Auto-scroll to first item after sorting (delay to allow list to update)
                 scope.launch {
                     kotlinx.coroutines.delay(100)
                     listState.animateScrollToItem(0)
                 }
            },
            onDirectionToggle = { option ->
                playlistViewModel.sortPlaylistSongs(option)
                scope.launch {
                    kotlinx.coroutines.delay(100)
                    listState.animateScrollToItem(0)
                }
            },
            showViewToggle = false 
        )
    }
    } // End MaterialTheme
}


@Composable
private fun PlaylistActionItem(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    supportingText: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The playlist hero's title at full size (the mockup's 44 sp; the artist page uses 48). */
private val PlaylistTitleExpandedSize = 44.sp

/**
 * The picture the playlist hero shows: the playlist's own image; nothing when it has its own
 * colour + icon cover (the placeholder draws that); otherwise the first song with artwork in the
 * playlist's saved order (sorting the view doesn't change the cover).
 */
private fun resolvePlaylistHeroImage(playlist: Playlist?, songs: List<Song>): String? {
    if (playlist == null) return null
    playlist.coverImageUri?.takeIf { it.isNotBlank() }?.let { return it }
    if (playlist.coverColorArgb != null) return null
    if (songs.isEmpty()) return null
    val byId = HashMap<String, Song>(songs.size * 2)
    for (song in songs) byId[song.id] = song
    for (id in playlist.songIds) {
        val art = byId[id]?.albumArtUriString
        if (!art.isNullOrBlank()) return art
    }
    // Folder playlists and others whose ids don't line up: the first song with art as shown.
    return songs.firstNotNullOfOrNull { it.albumArtUriString?.takeIf { art -> art.isNotBlank() } }
}

/**
 * The hero without a picture: the playlist's own cover colour and icon filling the top, or a
 * quiet container with the playlist icon. The hero's scrim melts it into the page like a photo.
 */
@Composable
private fun PlaylistHeroPlaceholder(playlist: Playlist) {
    val colors = MaterialTheme.colorScheme
    val coverColor = playlist.coverColorArgb
    val background = if (coverColor != null) Color(coverColor) else colors.surfaceContainerHigh
    val tint = if (coverColor != null) resolvePlaylistCoverContentColor(coverColor, colors) else colors.onSurfaceVariant
    val icon = getIconByName(playlist.coverIconName)
        ?: if (coverColor != null) Icons.Rounded.MusicNote else Icons.AutoMirrored.Rounded.QueueMusic
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        // Above centre: the bottom of the hero is under the title and the scrim.
        contentAlignment = BiasAlignment(0f, -0.3f)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(112.dp)
        )
    }
}

/** Loading / not found: the message centred, with a way back while there is no hero yet. */
@Composable
private fun PlaylistStatusPlaceholder(onBackClick: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.Center)) { content() }
        FilledIconButton(
            onClick = onBackClick,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 12.dp, top = 8.dp)
                .size(48.dp)
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back))
        }
    }
}

/**
 * Add / Remove / Reorder, unchanged from the old layout: Add as a tonal pill, then Remove and
 * Reorder as toggles that stretch to share the row (or scroll, with soft edge fades, when the
 * labels don't fit).
 */
@Composable
private fun PlaylistEditToolsRow(
    isReorderModeEnabled: Boolean,
    isRemoveModeEnabled: Boolean,
    addLabel: String,
    addSongsCd: String,
    removeLabel: String,
    removeSongsCd: String,
    reorderLabel: String,
    reorderSongsCd: String,
    onAddClick: () -> Unit,
    onToggleRemove: () -> Unit,
    onToggleReorder: () -> Unit,
) {
    val actionButtonsHeight = 42.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 4.dp, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val reorderCornerRadius by animateDpAsState(
            targetValue = if (isReorderModeEnabled) 24.dp else 12.dp,
            label = "reorderCornerRadius"
        )
        val reorderButtonColor by animateColorAsState(
            targetValue = if (isReorderModeEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHigh,
            label = "reorderButtonColor"
        )
        val reorderIconColor by animateColorAsState(
            targetValue = if (isReorderModeEnabled) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
            label = "reorderIconColor"
        )

        val removeCornerRadius by animateDpAsState(
            targetValue = if (isRemoveModeEnabled) 24.dp else 12.dp,
            label = "removeCornerRadius"
        )
        val removeButtonColor by animateColorAsState(
            targetValue = if (isRemoveModeEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHigh,
            label = "removeButtonColor"
        )
        val removeIconColor by animateColorAsState(
            targetValue = if (isRemoveModeEnabled) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
            label = "removeIconColor"
        )

        Button(
            onClick = onAddClick,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ),
            modifier = Modifier
                .height(actionButtonsHeight)
                .animateContentSize()
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = addSongsCd,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                modifier = Modifier.padding(end = 4.dp),
                text = addLabel,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false
            )
        }

        val scrollState = rememberScrollState()
        val showStartFade by remember { derivedStateOf { scrollState.value > 0 } }
        val showEndFade by remember { derivedStateOf { scrollState.value < scrollState.maxValue } }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val gradientWidth = 8.dp.toPx()

                    if (showStartFade) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                                endX = gradientWidth
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }

                    if (showEndFade) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Black, Color.Transparent),
                                startX = this.size.width - gradientWidth
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
        ) {
            val containerWidthPx = constraints.maxWidth

            Layout(
                modifier = Modifier.horizontalScroll(scrollState),
                content = {
                    Button(
                        onClick = onToggleRemove,
                        shape = RoundedCornerShape(removeCornerRadius),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = removeButtonColor,
                            contentColor = removeIconColor
                        ),
                        modifier = Modifier
                            .height(actionButtonsHeight)
                            .animateContentSize()
                            .clip(RoundedCornerShape(removeCornerRadius))
                    ) {
                        Icon(
                            imageVector = Icons.Default.RemoveCircleOutline,
                            contentDescription = removeSongsCd,
                            modifier = Modifier.size(16.dp),
                            tint = removeIconColor
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            modifier = Modifier.padding(end = 4.dp),
                            text = removeLabel,
                            color = removeIconColor,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Button(
                        onClick = onToggleReorder,
                        shape = RoundedCornerShape(reorderCornerRadius),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = reorderButtonColor,
                            contentColor = reorderIconColor
                        ),
                        modifier = Modifier
                            .height(actionButtonsHeight)
                            .animateContentSize()
                            .clip(RoundedCornerShape(reorderCornerRadius))
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.drag_order_icon),
                            contentDescription = reorderSongsCd,
                            modifier = Modifier.size(22.dp),
                            tint = reorderIconColor
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            modifier = Modifier.padding(end = 4.dp),
                            text = reorderLabel,
                            color = reorderIconColor,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            ) { measurables, childConstraints ->
                val spacingPx = 8.dp.roundToPx()
                val totalSpacing = spacingPx * (measurables.size - 1)

                // Query maxIntrinsicWidth of each child without measuring
                val totalNaturalWidth = measurables.sumOf { measurable ->
                    measurable.maxIntrinsicWidth(Constraints.Infinity)
                } + totalSpacing

                val finalPlaceables = if (totalNaturalWidth <= containerWidthPx && containerWidthPx > 0) {
                    // Stretch them equally to fill containerWidthPx
                    val availableSpace = containerWidthPx - totalSpacing
                    val equalWidth = (availableSpace / measurables.size).coerceAtLeast(0)
                    measurables.map { measurable ->
                        measurable.measure(
                            childConstraints.copy(
                                minWidth = equalWidth,
                                maxWidth = equalWidth
                            )
                        )
                    }
                } else {
                    // Measure naturally
                    measurables.map { measurable ->
                        measurable.measure(
                            childConstraints.copy(
                                minWidth = 0,
                                maxWidth = Constraints.Infinity
                            )
                        )
                    }
                }

                val layoutWidth = if (totalNaturalWidth <= containerWidthPx && containerWidthPx > 0) {
                    containerWidthPx
                } else {
                    totalNaturalWidth
                }

                val height = finalPlaceables.maxOfOrNull { it.height } ?: 0

                layout(layoutWidth, height) {
                    var xPosition = 0
                    finalPlaceables.forEach { placeable ->
                        placeable.placeRelative(x = xPosition, y = 0)
                        xPosition += placeable.width + spacingPx
                    }
                }
            }
        }
    }
}

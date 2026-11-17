@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil.size.Size
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.AdaptiveClickableSurface
import com.theveloper.pixelplay.presentation.components.CollapsingHeaderHeight
import com.theveloper.pixelplay.presentation.components.ExpressiveScrollBar
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.ScreenLayer
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.SmartImageCompactListTargetSize
import com.theveloper.pixelplay.presentation.components.SongInfoBottomSheet
import com.theveloper.pixelplay.presentation.components.hero.HeroBlurMath
import com.theveloper.pixelplay.presentation.components.hero.CollapsingPageHeroHeader
import com.theveloper.pixelplay.presentation.components.hero.PageContentShownFlag
import com.theveloper.pixelplay.presentation.components.hero.PageHeroDefaults
import com.theveloper.pixelplay.presentation.components.hero.PagePlayShuffleGroup
import com.theveloper.pixelplay.presentation.components.hero.ProgressiveBlurPageHero
import com.theveloper.pixelplay.presentation.components.hero.rememberPageSurfaceFade
import com.theveloper.pixelplay.presentation.components.hero.LocalPageSurfaceFade
import com.theveloper.pixelplay.presentation.components.hero.rememberHeroBlur
import com.theveloper.pixelplay.presentation.components.rememberAppListState
import com.theveloper.pixelplay.presentation.components.rememberCollapseFraction
import com.theveloper.pixelplay.presentation.components.rememberIsCurrentSong
import com.theveloper.pixelplay.presentation.components.rememberIsSongPlaying
import com.theveloper.pixelplay.presentation.components.rememberPlaybackRowState
import com.theveloper.pixelplay.presentation.components.resolveNavBarOccupiedHeight
import com.theveloper.pixelplay.presentation.components.scoped.lerpColorScheme
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.navigation.navigateSafelyReplacing
import com.theveloper.pixelplay.presentation.viewmodel.ArtistAlbumSection
import com.theveloper.pixelplay.presentation.viewmodel.ArtistDetailViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.ui.theme.FlexFitCollapsingTitle
import com.theveloper.pixelplay.ui.theme.FlexMath
import com.theveloper.pixelplay.ui.theme.FlexPresets
import com.theveloper.pixelplay.ui.theme.FlexSpec
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import com.theveloper.pixelplay.ui.theme.LocalShowScrollbar
import com.theveloper.pixelplay.ui.theme.PixelPlayStatusBarStyle
import com.theveloper.pixelplay.ui.theme.collapseScale
import com.theveloper.pixelplay.ui.theme.flexStyle
import com.theveloper.pixelplay.utils.formatSongCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import kotlin.math.roundToInt

/*
 * Artist page, new UI (stage 4). The artist photo fills the top edge to edge, sharp at the top and
 * progressively blurred into the page (the shared [ProgressiveBlurPageHero]); the name sits over
 * the blurred part in a fit-by-width Google Sans Flex cut that collapses into the top bar as the
 * page scrolls; Play + Shuffle are an M3 Expressive connected button group. Below: most played,
 * an albums row, the per-album song sections and "More from this artist", as before.
 *
 * Performance: the header's height and collapse fraction are read only in its own measure /
 * placement and in layer and draw blocks, so scrolling never recomposes the header or the list.
 */

/** Section titles: a heavier, slightly wide Flex cut so they read as signposts over the list. */
private val SectionTitleSpec = FlexSpec(weight = 680f, width = 106f)

/** Rank numerals in Most played: wide, heavy and fully round, like a chart position. */
private val RankNumeralSpec = FlexSpec(weight = 760f, width = 125f, roundness = 100f)

/** Album card shapes, cycled across the row (the mockup's expressive mix), built once. */
private val AlbumCardShapes: List<Shape> = listOf(
    AbsoluteSmoothCornerShape(28.dp, 60),
    AbsoluteSmoothCornerShape(64.dp, 60),
    AbsoluteSmoothCornerShape(
        cornerRadiusTL = 28.dp, smoothnessAsPercentTL = 60,
        cornerRadiusTR = 28.dp, smoothnessAsPercentTR = 60,
        cornerRadiusBR = 28.dp, smoothnessAsPercentBR = 60,
        cornerRadiusBL = 8.dp, smoothnessAsPercentBL = 60
    )
)

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArtistDetailScreen(
    artistId: String,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackRowState = rememberPlaybackRowState(playerViewModel.stablePlayerState)

    // Optimization: Defer heavy list rendering until navigation transition settles
    var isTransitionFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        isTransitionFinished = true
    }

    val lazyListState = rememberAppListState()
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val isDarkTheme = LocalPixelPlayDarkTheme.current
    val baseColorScheme = MaterialTheme.colorScheme

    // --- Dynamic color palette from the ViewModel ---
    // A cached palette is published together with the content, so it is there on the first
    // frame. On a first visit the content shows at once in the base colours and the palette
    // crossfades in when it is ready (it used to wait behind a spinner).
    val artistColorSchemePair by viewModel.artistColorScheme.collectAsStateWithLifecycle()
    val targetArtistColorScheme = remember(artistColorSchemePair, isDarkTheme, baseColorScheme) {
        artistColorSchemePair?.let { pair -> if (isDarkTheme) pair.dark else pair.light }
            ?: baseColorScheme
    }
    val artistContentShown = remember { PageContentShownFlag() }
    val artistContentVisible = !uiState.isLoading && uiState.artist != null
    SideEffect {
        artistContentShown.shown = artistContentVisible
        artistContentShown.darkTheme = isDarkTheme
    }
    val artistColorScheme = targetArtistColorScheme
    // The theme switches once; only the painted surfaces fade (in draw), so a first-visit palette
    // no longer recomposes the whole page on every frame of its fade.
    val artistSurface = rememberPageSurfaceFade(
        target = targetArtistColorScheme.surface,
        // Only a palette that arrives after the content is already on screen fades in; one
        // published together with the content (or a light/dark switch) applies at once, as before.
        animateChange = remember(targetArtistColorScheme) {
            artistContentShown.shown && artistContentShown.darkTheme == isDarkTheme
        }
    )

    // --- Image picker for custom artist image ---
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.setCustomImage(it) }
    }

    LaunchedEffect(Unit) {
        playerViewModel.collapsePlayerSheet()
    }

    // --- Lógica del Header Colapsable ---
    // El héroe ocupa el ancho completo y casi otro tanto de alto (390 × 360 en el mockup), con
    // un mínimo que garantiza sitio para los botones, dos líneas de nombre y la fila de acciones,
    // y un tope en pantallas apaisadas para que no se coma toda la vista.
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = remember(configuration.screenWidthDp, configuration.screenHeightDp, statusBarHeight) {
        PageHeroDefaults.maxHeight(configuration.screenWidthDp, configuration.screenHeightDp, statusBarHeight)
    }

    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }
    val headerImageRequestSize = remember(
        configuration.screenWidthDp,
        density.density,
        maxTopBarHeightPx
    ) {
        Size(
            width = with(density) { configuration.screenWidthDp.dp.roundToPx() },
            height = maxTopBarHeightPx.roundToInt()
        )
    }

    val topBarHeight = remember { CollapsingHeaderHeight(maxTopBarHeightPx) }
    // Read only inside derivedStateOf and the header's layout / draw: the per-frame header height
    // never recomposes the screen body, its list or the header itself.
    val collapseFractionState = rememberCollapseFraction(topBarHeight, minTopBarHeightPx, maxTopBarHeightPx)
    val collapseFraction by collapseFractionState

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0

                // Si estamos scrolleando hacia arriba y no estamos en el tope de la lista,
                // el scroll es para la lista, no para la TopBar.
                if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
                    return Offset.Zero
                }

                val previousHeight = topBarHeight.value
                val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight

                if (consumed.roundToInt() != 0) {
                    topBarHeight.snapTo(newHeight)
                }

                // Si estamos en el tope y scrolleamos hacia arriba, la lista no debe moverse.
                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return super.onPostFling(consumed, available)
            }
        }
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0

            val targetValue = if (shouldExpand && canExpand) {
                maxTopBarHeightPx
            } else {
                minTopBarHeightPx
            }

            if (topBarHeight.value != targetValue) {
                coroutineScope.launch {
                    topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium))
                }
            }
        }
    }
    // --- Fin de la lógica del Header ---

    // Wrap in dynamic theme derived from the artist's image
    MaterialTheme(
        colorScheme = artistColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
    CompositionLocalProvider(LocalPageSurfaceFade provides artistSurface) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(artistSurface.value) },
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box(modifier = Modifier.nestedScroll(nestedScrollConnection)) {
            when {
                uiState.isLoading && uiState.artist == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator()
                    }
                }
                uiState.error != null && uiState.artist == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                uiState.artist != null -> {
                    val artist = uiState.artist!!
                    val songs = uiState.songs
                    val albumSections = uiState.albumSections
                    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
                    LaunchedEffect(albumSections) {
                        val currentKeys = albumSections.map { it.collapseKey() }.toSet()
                        currentKeys.forEach { key ->
                            if (expandedSections[key] == null) {
                                expandedSections[key] = true
                            }
                        }
                        expandedSections.keys
                            .filterNot { it in currentKeys }
                            .forEach { staleKey -> expandedSections.remove(staleKey) }
                    }

                    val isScrollbarEnabled = LocalShowScrollbar.current
                    val showScrollBar by remember(isScrollbarEnabled) {
                        derivedStateOf {
                            isScrollbarEnabled &&
                            collapseFraction > 0.95f &&
                                (lazyListState.canScrollForward || lazyListState.canScrollBackward)
                        }
                    }

                    val headerCounts = stringResource(
                        R.string.artist_header_counts,
                        formatSongCount(songs.size),
                        pluralStringResource(R.plurals.artist_album_count, albumSections.size, albumSections.size)
                    )
                    val sectionTitleStyle = flexStyle(MaterialTheme.typography.titleLarge, SectionTitleSpec)

                    ScreenLayer {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .offset {
                                    val extraHeight =
                                        (topBarHeight.value - minTopBarHeightPx).roundToInt()
                                    IntOffset(0, extraHeight)
                                },
                            contentPadding = PaddingValues(
                                top = minTopBarHeight + 8.dp,
                                start = 16.dp,
                                end = if (showScrollBar) 24.dp else 16.dp,
                                // The nav pills stay on this page, the mini player above them.
                                bottom = bottomBarHeightDp + MiniPlayerHeight + 8.dp
                            )
                        ) {
                            // Lo más escuchado va antes que los discos: al abrir un artista lo
                            // que casi siempre se busca es una canción concreta que ya se
                            // conoce, no navegar su discografía por orden de publicación.
                            val topSongs = uiState.topSongs
                            if (topSongs.isNotEmpty()) {
                                item(key = "top_songs_header", contentType = "artist_top_header") {
                                    ArtistSectionTitle(
                                        title = stringResource(R.string.artist_section_most_played),
                                        style = sectionTitleStyle,
                                        onPlayAll = {
                                            topSongs.firstOrNull()?.let { first ->
                                                playerViewModel.showAndPlaySong(first, topSongs)
                                            }
                                        }
                                    )
                                }

                                itemsIndexed(
                                    items = topSongs,
                                    key = { i, song -> "top_song_${song.id}_$i" },
                                    contentType = { _, _ -> "artist_top_song" }
                                ) { songIndex, song ->
                                    ArtistPopularSongRow(
                                        rank = songIndex + 1,
                                        song = song,
                                        isCurrentSong = rememberIsCurrentSong(playbackRowState, song.id).value,
                                        isPlaying = rememberIsSongPlaying(playbackRowState, song.id).value,
                                        onSongClick = {
                                            playerViewModel.showAndPlaySong(song, topSongs)
                                        },
                                        onMoreOptionsClick = {
                                            playerViewModel.selectSongForInfo(song)
                                            showSongInfoBottomSheet = true
                                        }
                                    )
                                }

                                item(key = "top_songs_footer", contentType = "artist_section_footer") {
                                    Spacer(modifier = Modifier.height(20.dp))
                                }
                            }

                            // Fila de discos (nuevo): un vistazo a la discografía de la biblioteca
                            // con acceso directo a cada álbum. Las secciones por álbum de abajo
                            // siguen ahí para reproducir sin salir de la página.
                            if (albumSections.isNotEmpty()) {
                                item(key = "albums_row_header", contentType = "artist_albums_header") {
                                    ArtistSectionTitle(
                                        title = stringResource(R.string.artist_section_albums),
                                        style = sectionTitleStyle,
                                        onPlayAll = null
                                    )
                                }
                                item(key = "albums_row", contentType = "artist_albums_row") {
                                    ArtistAlbumsRow(
                                        sections = albumSections,
                                        onAlbumClick = { section ->
                                            navController.navigateSafely(Screen.AlbumDetail.createRoute(section.albumId))
                                        }
                                    )
                                }
                                item(key = "songs_by_album_header", contentType = "artist_albums_header") {
                                    ArtistSectionTitle(
                                        title = stringResource(R.string.artist_section_songs_by_album),
                                        style = sectionTitleStyle,
                                        onPlayAll = null,
                                        modifier = Modifier.padding(top = 20.dp)
                                    )
                                }
                            }

                            albumSections.forEachIndexed { index, section ->
                                if (section.songs.isEmpty()) return@forEachIndexed

                                val sectionKey = section.collapseKey()
                                val isExpanded = expandedSections[sectionKey] ?: true
                                val sectionSongs = if (isTransitionFinished) section.songs else section.songs.take(5)

                                item(
                                    key = "${sectionKey}_header",
                                    contentType = "artist_section_header"
                                ) {
                                    CollapsibleAlbumSectionHeader(
                                        section = section,
                                        isExpanded = isExpanded,
                                        onToggleExpanded = {
                                            expandedSections[sectionKey] = !isExpanded
                                        },
                                        onPlayAlbum = {
                                            section.songs.firstOrNull()?.let { firstSong ->
                                                playerViewModel.showAndPlaySong(firstSong, section.songs)
                                            }
                                        }
                                    )
                                }

                                if (isExpanded) {
                                    item(
                                        key = "${sectionKey}_song_group_spacer",
                                        contentType = "artist_section_spacer"
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .animateItem(
                                                    fadeInSpec = tween(durationMillis = 160),
                                                    fadeOutSpec = tween(durationMillis = 120),
                                                    placementSpec = tween(durationMillis = 180)
                                                )
                                                .fillMaxWidth()
                                                .height(10.dp)
                                                .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
                                        )
                                    }

                                    itemsIndexed(
                                        items = sectionSongs,
                                        key = { songIndex, song -> "${sectionKey}_song_${song.id}_$songIndex" },
                                        contentType = { _, _ -> "artist_section_song" }
                                    ) { songIndex, song ->
                                        ArtistAlbumSectionSongItem(
                                            modifier = Modifier.animateItem(
                                                fadeInSpec = tween(durationMillis = 180),
                                                fadeOutSpec = tween(durationMillis = 120),
                                                placementSpec = tween(durationMillis = 200)
                                            ),
                                            song = song,
                                            songIndex = songIndex,
                                            songCount = section.songs.size,
                                            isCurrentSong = rememberIsCurrentSong(playbackRowState, song.id).value,
                                            isPlaying = rememberIsSongPlaying(playbackRowState, song.id).value,
                                            onSongClick = {
                                                playerViewModel.showAndPlaySong(song, section.songs)
                                            },
                                            onMoreOptionsClick = {
                                                playerViewModel.selectSongForInfo(song)
                                                showSongInfoBottomSheet = true
                                            }
                                        )
                                    }
                                }

                                item(
                                    key = "${sectionKey}_footer",
                                    contentType = "artist_section_footer"
                                ) {
                                    Spacer(
                                        modifier = Modifier.height(
                                            if (index == albumSections.lastIndex) 24.dp else 16.dp
                                        )
                                    )
                                }
                            }

                            val moreFromArtist = uiState.moreFromArtist
                            if (moreFromArtist.isNotEmpty()) {
                                item(key = "more_from_artist_header", contentType = "artist_more_header") {
                                    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                                        Text(
                                            text = stringResource(R.string.artist_section_more_from_artist),
                                            style = sectionTitleStyle,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier
                                                .padding(start = 6.dp, end = 4.dp, bottom = 2.dp)
                                                .semantics { heading() }
                                        )
                                        Text(
                                            text = stringResource(R.string.artist_section_more_from_artist_subtitle),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 6.dp, end = 4.dp, bottom = 8.dp)
                                        )
                                    }
                                }

                                moreFromArtist.forEach { section ->
                                    item(key = "more_from_artist_album_${section.title}", contentType = "artist_more_album_header") {
                                        Text(
                                            text = section.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp)
                                        )
                                    }
                                    items(
                                        items = section.tracks,
                                        key = { "more_from_artist_track_${it.videoId}" },
                                        contentType = { "artist_more_track" }
                                    ) { track ->
                                        Box(modifier = Modifier.padding(bottom = 8.dp)) {
                                            ArtistYouTubeMusicTrackItem(
                                                track = track,
                                                onPlayClick = {
                                                    coroutineScope.launch {
                                                        val song = viewModel.playYouTubeMusicTrack(track.videoId)
                                                        if (song != null) {
                                                            playerViewModel.playSong(song)
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                item(key = "more_from_artist_footer", contentType = "artist_section_footer") {
                                    Spacer(modifier = Modifier.height(24.dp))
                                }
                            }
                        }

                        if (showScrollBar) {
                            ExpressiveScrollBar(
                                listState = lazyListState,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(
                                        top = minTopBarHeight + 12.dp,
                                        bottom = bottomBarHeightDp + MiniPlayerHeight + 8.dp
                                    )
                            )
                        }

                        ArtistHeroHeader(
                            artist = artist,
                            imageUrl = uiState.effectiveImageUrl,
                            subtitle = headerCounts,
                            height = topBarHeight,
                            collapseFraction = collapseFractionState,
                            minHeightPx = minTopBarHeightPx,
                            maxHeightPx = maxTopBarHeightPx,
                            imageRequestSize = headerImageRequestSize,
                            hasCustomImage = !artist.customImageUri.isNullOrBlank(),
                            canPlay = songs.isNotEmpty(),
                            onBackPressed = { navController.popBackStack() },
                            onPlayClick = {
                                songs.firstOrNull()?.let { first ->
                                    playerViewModel.playSongs(songs, first, artist.name)
                                }
                            },
                            onShuffleClick = {
                                if (songs.isNotEmpty()) {
                                    playerViewModel.playSongsShuffled(
                                        songs,
                                        artist.name,
                                        startAtZero = true
                                    )
                                }
                            },
                            onChangeImage = { imagePickerLauncher.launch("image/*") },
                            onClearCustomImage = { viewModel.clearCustomImage() }
                        )
                    }
                }
            }
        }
    } // End Surface
    }

    // Bottom sheets inherit the artist's dynamic color palette — same approach as AlbumDetailScreen
    if (showSongInfoBottomSheet && selectedSongForInfo != null) {
        val currentSong = selectedSongForInfo
        val isFavorite = remember(currentSong?.id, favoriteIds) {
            currentSong?.let { favoriteIds.contains(it.id) } ?: false
        }

        if (currentSong != null) {
            val removeFromListTrigger = remember(uiState.songs) {
                {
                    viewModel.removeSongFromAlbumSection(currentSong.id)
                }
            }
            SongInfoBottomSheet(
                song = currentSong,
                isFavorite = isFavorite,
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
                    showPlaylistBottomSheet = true
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
                removeFromListTrigger = removeFromListTrigger
            )
            if (showPlaylistBottomSheet) {
                val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

                PlaylistBottomSheet(
                    playlistUiState = playlistUiState,
                    songs = listOf(currentSong),
                    onDismiss = { showPlaylistBottomSheet = false },
                    bottomBarHeight = bottomBarHeightDp,
                    playerViewModel = playerViewModel,
                )
            }
        }
    }
    } // End MaterialTheme
}

/**
 * The artist hero: the shared [CollapsingPageHeroHeader] (photo, progressive blur, fit-by-width
 * name collapsing into the bar) with the artist's counts, Play / Shuffle group, back button and
 * the photo menu (block artist, change photo, reset).
 */
@Composable
private fun ArtistHeroHeader(
    artist: Artist,
    imageUrl: String?,
    subtitle: String,
    height: FloatState,
    collapseFraction: State<Float>,
    minHeightPx: Float,
    maxHeightPx: Float,
    imageRequestSize: Size,
    hasCustomImage: Boolean,
    canPlay: Boolean,
    onBackPressed: () -> Unit,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onChangeImage: () -> Unit,
    onClearCustomImage: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var showImageMenu by remember { mutableStateOf(false) }

    CollapsingPageHeroHeader(
        title = artist.name,
        imageUrl = imageUrl,
        imageContentDescription = artist.name,
        imageRequestSize = imageRequestSize,
        height = height,
        collapseFraction = collapseFraction,
        minHeightPx = minHeightPx,
        maxHeightPx = maxHeightPx,
        placeholder = { MusicIconPattern(modifier = Modifier.fillMaxSize()) },
        navigation = {
            FilledIconButton(
                onClick = onBackPressed,
                colors = PageHeroDefaults.iconButtonColors(),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
        actions = {
            Box {
                FilledIconButton(
                    onClick = { showImageMenu = true },
                    colors = PageHeroDefaults.iconButtonColors(),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.artist_cd_more_options))
                }
                DropdownMenu(
                    expanded = showImageMenu,
                    onDismissRequest = { showImageMenu = false }
                ) {
                    com.theveloper.pixelplay.presentation.components.BlockArtistMenuItem(artist.id, artist.name) { showImageMenu = false }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.artist_action_change_photo)) },
                        leadingIcon = { Icon(Icons.Rounded.AddAPhoto, contentDescription = null) },
                        onClick = {
                            showImageMenu = false
                            onChangeImage()
                        }
                    )
                    if (hasCustomImage) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.artist_action_reset_to_default)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = {
                                showImageMenu = false
                                onClearCustomImage()
                            }
                        )
                    }
                }
            }
        },
        bottomContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            // No Follow toggle yet (the mockup has one): library artists carry no Spotify artist
            // id, and following needs the `user-follow-modify` scope, which existing accounts
            // would have to reconnect for. Left out on purpose until the owner decides; monthly
            // listeners are not in the Web API at all.
            PagePlayShuffleGroup(
                enabled = canPlay,
                onPlayClick = onPlayClick,
                onShuffleClick = onShuffleClick,
                shuffleContentDescription = stringResource(R.string.artist_cd_shuffle_play)
            )
        },
        titleExpandedSize = PageHeroDefaults.TitleExpandedSize
    )
}

/** A Most played row: a chart-style rank numeral beside the regular song row. */
@Composable
private fun ArtistPopularSongRow(
    rank: Int,
    song: Song,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onSongClick: () -> Unit,
    onMoreOptionsClick: () -> Unit,
) {
    val rankStyle = flexStyle(MaterialTheme.typography.titleMedium, RankNumeralSpec)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            style = rankStyle,
            color = if (isCurrentSong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp)
        )
        EnhancedSongListItem(
            modifier = Modifier.weight(1f),
            song = song,
            isCurrentSong = isCurrentSong,
            isPlaying = isPlaying,
            showAlbumArt = true,
            albumArtSize = 44.dp,
            containerColorOverride = Color.Transparent,
            onMoreOptionsClick = { onMoreOptionsClick() },
            onClick = onSongClick
        )
    }
}

/** The albums row: square-ish covers in a cycle of expressive shapes, each opening its album. */
@Composable
private fun ArtistAlbumsRow(
    sections: List<ArtistAlbumSection>,
    onAlbumClick: (ArtistAlbumSection) -> Unit,
) {
    val bleed = 16.dp
    LazyRow(
        // Bleeds to the screen edges past the list's own side padding, like the mockup.
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val extra = bleed.roundToPx() * 2
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = constraints.minWidth + extra,
                        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
                    )
                )
                layout(constraints.maxWidth, placeable.height) {
                    placeable.place(-bleed.roundToPx(), 0)
                }
            },
        contentPadding = PaddingValues(horizontal = bleed),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(
            items = sections,
            key = { _, section -> "artist_album_card_${section.albumId}_${section.title}" },
            contentType = { _, _ -> "artist_album_card" }
        ) { index, section ->
            ArtistAlbumCard(
                section = section,
                shape = AlbumCardShapes[index % AlbumCardShapes.size],
                onClick = { onAlbumClick(section) }
            )
        }
    }
}

@Composable
private fun ArtistAlbumCard(
    section: ArtistAlbumSection,
    shape: Shape,
    onClick: () -> Unit,
) {
    val openLabel = stringResource(R.string.artist_cd_open_album, section.title)
    Column(
        modifier = Modifier
            .width(128.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = openLabel, onClick = onClick)
    ) {
        SmartImage(
            model = section.albumArtUriString,
            contentDescription = section.title,
            shape = shape,
            modifier = Modifier.size(128.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        section.year?.takeIf { it > 0 }?.let { year ->
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun ArtistAlbumSection.collapseKey(): String = "artist_album_${albumId}_${title}"

/**
 * Cabecera de sección, al estilo de las de álbum: título en un corte Flex más pesado y, si la
 * sección se puede reproducir entera, su botón de reproducir.
 */
@Composable
private fun ArtistSectionTitle(
    title: String,
    style: TextStyle,
    onPlayAll: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        if (onPlayAll != null) {
            FilledIconButton(
                onClick = onPlayAll,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.artist_cd_play_title, title)
                )
            }
        }
    }
}

@Composable
private fun CollapsibleAlbumSectionHeader(
    section: ArtistAlbumSection,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayAlbum: () -> Unit
) {
    val expandIconRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "ArtistAlbumExpandRotation"
    )
    val subtitle = remember(section.year, section.songs.size) {
        buildString {
            section.year?.takeIf { it > 0 }?.let {
                append(it.toString())
                append(" • ")
            }
            append(formatSongCount(section.songs.size))
        }
    }

    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
    val shape = if (isExpanded) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTR = 24.dp, smoothnessAsPercentTR = 60,
            cornerRadiusTL = 24.dp, smoothnessAsPercentTL = 60,
            cornerRadiusBR = 0.dp, smoothnessAsPercentBR = 0,
            cornerRadiusBL = 0.dp, smoothnessAsPercentBL = 0
        )
    } else {
        AbsoluteSmoothCornerShape(24.dp, 60)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SmartImage(
                model = section.albumArtUriString,
                contentDescription = section.title,
                targetSize = SmartImageCompactListTargetSize,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalIconButton(
                onClick = onPlayAlbum,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.artist_cd_play_title, section.title))
            }
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (isExpanded) {
                    stringResource(R.string.artist_cd_collapse_title, section.title)
                } else {
                    stringResource(R.string.artist_cd_expand_title, section.title)
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer {
                    rotationZ = expandIconRotation
                }
            )
        }
    }
}

@Composable
private fun ArtistAlbumSectionSongItem(
    modifier: Modifier = Modifier,
    song: Song,
    songIndex: Int,
    songCount: Int,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onSongClick: () -> Unit,
    onMoreOptionsClick: () -> Unit
) {
    val isLastSong = songIndex == songCount - 1

    val songItemShape = when {
        songCount == 1 -> RoundedCornerShape(16.dp)
        songIndex == 0 -> RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )
        isLastSong -> RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 16.dp,
            bottomEnd = 16.dp
        )
        else -> RoundedCornerShape(4.dp)
    }

    val containerShape = if (isLastSong) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTR = 0.dp, smoothnessAsPercentTR = 0,
            cornerRadiusTL = 0.dp, smoothnessAsPercentTL = 0,
            cornerRadiusBR = 24.dp, smoothnessAsPercentBR = 60,
            cornerRadiusBL = 24.dp, smoothnessAsPercentBL = 60
        )
    } else {
        RectangleShape
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f), containerShape)
            .padding(horizontal = 8.dp)
    ) {
        Column {
            if (songIndex > 0) {
                Spacer(modifier = Modifier.height(2.dp))
            }

            EnhancedSongListItem(
                modifier = Modifier.fillMaxWidth(),
                song = song,
                isCurrentSong = isCurrentSong,
                isPlaying = isPlaying,
                showAlbumArt = false,
                customShape = songItemShape,
                onMoreOptionsClick = { onMoreOptionsClick() },
                onClick = onSongClick
            )

            if (isLastSong) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Fila de una pista de la sección "More from [artist]" — resultado de YouTube Music, no de
 * la biblioteca. Tocarla importa y reproduce directamente, mismo patrón que
 * `SearchResultYouTubeMusicItem` en la pantalla de búsqueda.
 */
@Composable
private fun ArtistYouTubeMusicTrackItem(
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.theveloper.pixelplay.presentation.components.SmartImage(
                model = track.thumbnailUrl,
                contentDescription = track.title,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(48.dp)
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
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.common_play),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun MusicIconPattern(modifier: Modifier = Modifier) {
    val color1 = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
    val color2 = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

    Box(modifier = modifier.background(MaterialTheme.colorScheme.primaryContainer)) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 76.dp, y = 72.dp)
                .size(60.dp)
                .graphicsLayer { rotationZ = -12f }
        )
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 4.dp, y = 78.dp)
                .size(50.dp)
                .graphicsLayer { rotationZ = 18f }
        )
        Icon(
            imageVector = Icons.Rounded.Album,
            contentDescription = null, tint = color2,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 4.dp, y = (-70).dp)
                .size(70.dp)
                .graphicsLayer { rotationZ = 12f }
        )
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = 14.dp, y = (-6).dp)
                .size(60.dp)
                .graphicsLayer { rotationZ = 14f }
        )
        Icon(
            imageVector = Icons.Rounded.SurroundSound,
            contentDescription = null, tint = color2,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(x = (-24).dp, y = 32.dp)
                .size(80.dp)
                .graphicsLayer { rotationZ = 10f }
        )
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null, tint = color1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-18).dp, y = (-138).dp)
                .size(45.dp)
                .graphicsLayer { rotationZ = -18f }
        )
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null, tint = color2,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 72.dp, y = 34.dp)
                .size(45.dp)
                .graphicsLayer { rotationZ = -8f }
        )
    }
}

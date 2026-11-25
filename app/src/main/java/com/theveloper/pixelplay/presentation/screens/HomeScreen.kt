package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.components.AdaptiveModalBottomSheet
import com.theveloper.pixelplay.presentation.components.rememberIsCurrentSong
import com.theveloper.pixelplay.presentation.components.rememberPlaybackRowState
import com.theveloper.pixelplay.presentation.components.rememberNotCoveredByPlayer
import kotlinx.coroutines.Job
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.navigation.navigateSafelyReplacing

import android.content.Intent
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.BetaInfoBottomSheet
import com.theveloper.pixelplay.presentation.components.ChangelogBottomSheet
import com.theveloper.pixelplay.presentation.components.HomeGradientTopBar
import com.theveloper.pixelplay.presentation.components.HomeGreetingCard
import com.theveloper.pixelplay.presentation.components.HomeDiscoveryMixes
import com.theveloper.pixelplay.presentation.components.HomeDiscoveryShelf
import com.theveloper.pixelplay.presentation.components.HomeAllMixes
import com.theveloper.pixelplay.presentation.components.HomeDateLine
import com.theveloper.pixelplay.presentation.components.HomeHeroCard
import com.theveloper.pixelplay.presentation.components.HomeJumpBackIn
import com.theveloper.pixelplay.presentation.components.HomeKnowingStrip
import com.theveloper.pixelplay.presentation.components.HomeMixShelf
import com.theveloper.pixelplay.presentation.components.HomeStationShelf
import com.theveloper.pixelplay.presentation.components.HomeQuickAction
import com.theveloper.pixelplay.presentation.components.HomeQuickActionsRow
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.navBarEdgeInset
import com.theveloper.pixelplay.presentation.components.RecentlyAddedSection
import com.theveloper.pixelplay.presentation.components.RecentlyPlayedSection
import com.theveloper.pixelplay.presentation.components.RecentlyPlayedSectionMinSongsToShow
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.StatsOverviewCard
import com.theveloper.pixelplay.presentation.components.YourMixShelfSection
import com.theveloper.pixelplay.presentation.components.resolveMainScreenBottomGradientHeight
import com.theveloper.pixelplay.presentation.model.collectRecentlyPlayedSongIds
import com.theveloper.pixelplay.presentation.model.mapRecentlyPlayedSongs
import com.theveloper.pixelplay.presentation.components.subcomps.PlayingEqIcon
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.HomeDiscoveryViewModel
import com.theveloper.pixelplay.presentation.viewmodel.StatsViewModel
import com.theveloper.pixelplay.ui.theme.ExpTitleTypography
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.compose.ui.res.stringResource

private const val HomeLoadingPlaceholderMinDurationMillis = 1200L

// Modern HomeScreen with collapsible top bar and staggered grid layout
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    paddingValuesParent: PaddingValues,
    playerViewModel: PlayerViewModel,
    onOpenSidebar: () -> Unit
) {
    val context = LocalContext.current
    // DETECTAR MODO BENCHMARK
    val isBenchmarkMode = remember {
        (context as? android.app.Activity)?.intent?.getBooleanExtra("is_benchmark", false) ?: false
    }
    val statsViewModel: StatsViewModel = hiltViewModel()
    val dailyMixSongs by playerViewModel.dailyMixSongs.collectAsStateWithLifecycle()
    val curatedYourMixSongs by playerViewModel.yourMixSongs.collectAsStateWithLifecycle()
    // Home's mix card: today's saved brain mix is read back once the first seconds have passed (no state load).
    androidx.compose.runtime.LaunchedEffect(Unit) { playerViewModel.onMixSurfaceOpened(full = false) }
    val homeMixPreviewSongs by playerViewModel.homeMixPreviewSongs.collectAsStateWithLifecycle()
    val playbackHistory by playerViewModel.playbackHistory.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val discoveryViewModel: HomeDiscoveryViewModel = hiltViewModel()
    // The one-time "import your Spotify history" card (Settings › Library & storage › Listening history).
    val listeningHistoryViewModel: com.theveloper.pixelplay.presentation.viewmodel.ListeningHistoryViewModel = hiltViewModel()
    val listeningHistoryStatus by listeningHistoryViewModel.status.collectAsStateWithLifecycle()
    val showListeningHistoryCard = !listeningHistoryStatus.homeCardDismissed && !listeningHistoryStatus.hasSpotifyExport
    val discovery by discoveryViewModel.state.collectAsStateWithLifecycle()
    // The brain's Home plan (docs/brain-spec.md, 8.1, stage 2): read from its file before anything else; `owned` false is the legacy page.
    val planUi by discoveryViewModel.planState.collectAsStateWithLifecycle()
    // "See all" of Mix & match (the old "Mixes" chip) and of Fresh finds (the old "New" chip).
    var showAllMixes by rememberSaveable { mutableStateOf(false) }
    var showNewShelves by rememberSaveable { mutableStateOf(false) }
    // Until the saved plan has been looked for, Home does not know which page it is: it draws nothing, then fades in once.
    val homeContentAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (planUi.resolved) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 120),
        label = "homeContentAlpha",
    )
    LaunchedEffect(Unit) { discoveryViewModel.onHomeShown() }
    // "Import your listening history" from the setup page: the existing import opens once, as soon as Home is here.
    val pendingHistoryImport by discoveryViewModel.pendingHistoryImport.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(pendingHistoryImport) {
        if (pendingHistoryImport) {
            discoveryViewModel.clearPendingHistoryImport()
            navController.navigateSafely(Screen.ListeningHistory.route)
        }
    }
    val discoverySnackbar = remember { SnackbarHostState() }
    LaunchedEffect(homeMixPreviewSongs.isNotEmpty()) { discoveryViewModel.refresh() }
    val discoveryRefreshScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner, discoveryViewModel) {
        var pendingStartRefresh: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                // Coming back to Home is a pop transition: start the whole-library pass once it
                // has settled instead of during it (the holder still throttles repeat passes).
                pendingStartRefresh?.cancel()
                pendingStartRefresh = discoveryRefreshScope.launch {
                    delay(HomeDiscoveryStartRefreshDelayMs)
                    discoveryViewModel.refresh()
                    discoveryViewModel.onHomeShown()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(discovery.message) {
        discovery.message?.let {
            discoverySnackbar.showSnackbar(it)
            discoveryViewModel.clearMessage()
        }
    }

    val usesFallbackHomeMix = remember(curatedYourMixSongs, dailyMixSongs) {
        curatedYourMixSongs.isEmpty() && dailyMixSongs.isEmpty()
    }
    val yourMixSongs = remember(curatedYourMixSongs, dailyMixSongs, homeMixPreviewSongs) {
        when {
            curatedYourMixSongs.isNotEmpty() -> curatedYourMixSongs
            dailyMixSongs.isNotEmpty() -> dailyMixSongs
            else -> homeMixPreviewSongs
        }
    }
    var homePlaceholderRefreshGeneration by rememberSaveable { mutableIntStateOf(0) }
    var hasHomeLoadingMinimumElapsed by rememberSaveable(homePlaceholderRefreshGeneration) {
        mutableStateOf(false)
    }

    LaunchedEffect(homePlaceholderRefreshGeneration, yourMixSongs.isEmpty()) {
        if (yourMixSongs.isEmpty()) {
            hasHomeLoadingMinimumElapsed = false
            delay(HomeLoadingPlaceholderMinDurationMillis)
            hasHomeLoadingMinimumElapsed = true
        } else {
            hasHomeLoadingMinimumElapsed = true
        }
    }

    val shouldShowYourMixLoadingPlaceholder = yourMixSongs.isEmpty() && !hasHomeLoadingMinimumElapsed
    val recentSongIds = remember(playbackHistory) {
        collectRecentlyPlayedSongIds(
            playbackHistory = playbackHistory,
            maxItems = 64
        )
    }
    val recentlyPlayedSourceSongsInitialValue = remember(recentSongIds) {
        if (recentSongIds.isEmpty()) persistentListOf<Song>() else null
    }
    val recentlyPlayedSourceSongs by remember(recentSongIds, playerViewModel) {
        playerViewModel.observeSongs(recentSongIds)
            .map<List<Song>, List<Song>?> { it }
    }.collectAsStateWithLifecycle(initialValue = recentlyPlayedSourceSongsInitialValue)
    val latestRecentlyPlayedSongs = remember(playbackHistory, recentlyPlayedSourceSongs) {
        val sourceSongs = recentlyPlayedSourceSongs ?: return@remember emptyList()
        mapRecentlyPlayedSongs(
            playbackHistory = playbackHistory,
            songs = sourceSongs,
            maxItems = 64
        )
    }
    // Keep the visible Home snapshot stable and only refresh it once the screen is off-screen.
    var recentlyPlayedSongs by rememberSaveable { mutableStateOf(latestRecentlyPlayedSongs) }
    val latestRecentlyPlayedSongsState = rememberUpdatedState(latestRecentlyPlayedSongs)

    LaunchedEffect(latestRecentlyPlayedSongs, lifecycleOwner) {
        val isHomeVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (recentlyPlayedSongs.isEmpty() || !isHomeVisible) {
            recentlyPlayedSongs = latestRecentlyPlayedSongs
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                recentlyPlayedSongs = latestRecentlyPlayedSongsState.value
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val recentlyPlayedQueue = remember(recentlyPlayedSongs) {
        recentlyPlayedSongs.map { it.song }.toImmutableList()
    }

    ReportDrawnWhen {
        yourMixSongs.isNotEmpty() || hasHomeLoadingMinimumElapsed || isBenchmarkMode
    }

    // 2) Observar sólo el currentSong (o null) para saber si mostrar padding
    val currentSong by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong }
    }.collectAsStateWithLifecycle(initialValue = null)

    // 3) Observe shuffle state for sync
    val isShuffleEnabled by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState
            .map { it.isShuffleEnabled }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // Padding inferior si hay canción en reproducción
    val bottomPadding = if (currentSong != null) MiniPlayerHeight else 0.dp
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomGradientHeight = resolveMainScreenBottomGradientHeight(navBarCompactMode)

    var showChangelogBottomSheet by remember { mutableStateOf(false) }
    var showJobsBottomSheet by remember { mutableStateOf(false) }
    val activeJobs by playerViewModel.activeJobs.collectAsStateWithLifecycle()
    var showAskTaizo by remember { mutableStateOf(false) }
    var showBetaInfoBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val betaSheetState = rememberModalBottomSheetState()
    val jobsSheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    LocalContext.current

    val homeStatsOverview by statsViewModel.homeOverview.collectAsStateWithLifecycle()

    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val density = LocalDensity.current

    // Persist the scroll position across navigation away/back. The Stats card and other
    // conditional sections can shift indices while data re-emits when returning, which
    // would otherwise leave the list scrolled to the wrong place or jump to the top.
    var savedScrollIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    var needsScrollRestore by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, listState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                savedScrollIndex = listState.firstVisibleItemIndex
                savedScrollOffset = listState.firstVisibleItemScrollOffset
                needsScrollRestore = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        needsScrollRestore,
        yourMixSongs.isNotEmpty(),
        dailyMixSongs.isNotEmpty(),
        recentlyPlayedSongs.size,
        homeStatsOverview
    ) {
        if (!needsScrollRestore) return@LaunchedEffect
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems == 0) return@LaunchedEffect
        val targetIndex = savedScrollIndex.coerceIn(0, (totalItems - 1).coerceAtLeast(0))
        listState.scrollToItem(targetIndex, savedScrollOffset)
        needsScrollRestore = false
    }

    // ---- Redesign batch 3 (Main.dc.html / AndroidHomeScrolled.dc.html): see components/redesign/HomeRedesign.kt for the numbers. ----
    val weekOverview = homeStatsOverview?.takeIf { it.range == com.theveloper.pixelplay.data.stats.StatsTimeRange.WEEK }
    val topSong = weekOverview?.topSongs?.firstOrNull()
    val greetingWords = com.theveloper.pixelplay.presentation.components.redesign.rememberHomeGreeting(
        weekPlays = weekOverview?.totalPlayCount,
        topSong = topSong?.title,
        topSongPlays = topSong?.playCount ?: 0,
    )
    val sectionTitles = com.theveloper.pixelplay.presentation.components.redesign.rememberHomeSectionTitles()
    val showCollapsedHeader by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val collapsedAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (showCollapsedHeader) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        label = "homeCollapsedHeader",
    )
    val dailyMixTitle = stringResource(R.string.daily_mix_title)
    val yourMixQueueName = stringResource(R.string.home_your_mix_queue_name)

    // Every made-for-you card except Your Mix (the hero) and Endless Listening (its own pill), in the plan's order.
    val planMixCards = remember(planUi.shelves, planUi.allMixes) {
        val seen = HashSet<String>()
        val out = ArrayList<com.theveloper.pixelplay.presentation.viewmodel.MixCardUi>()
        planUi.shelves.forEach { shelf ->
            if (shelf is com.theveloper.pixelplay.presentation.viewmodel.HomeShelfUi.Mixes) shelf.cards.forEach { if (seen.add(it.mixId)) out.add(it) }
        }
        planUi.allMixes.forEach { if (seen.add(it.mixId)) out.add(it) }
        out.filter { it.mixId != "your" && it.kind != com.theveloper.pixelplay.data.brain.MixKinds.ENDLESS }
    }
    val hasPlanYourMix = remember(planUi.shelves, planUi.allMixes) {
        planUi.allMixes.any { it.mixId == "your" } || planUi.shelves.any { shelf ->
            shelf is com.theveloper.pixelplay.presentation.viewmodel.HomeShelfUi.Mixes && shelf.cards.any { it.mixId == "your" }
        }
    }
    val legacyDailyArtists = remember(dailyMixSongs) {
        dailyMixSongs.asSequence().map { it.displayArtist }.filter { it.isNotBlank() }.distinct().take(3).joinToString(", ")
    }
    val mixTiles = remember(planMixCards, planUi.owned, dailyMixSongs.isEmpty(), legacyDailyArtists, dailyMixTitle) {
        if (planUi.owned || planMixCards.isNotEmpty()) {
            planMixCards.map { com.theveloper.pixelplay.presentation.components.redesign.HomeMixTile(it.mixId, it.title, it.subtitle) }
        } else if (dailyMixSongs.isNotEmpty()) {
            listOf(com.theveloper.pixelplay.presentation.components.redesign.HomeMixTile("daily", dailyMixTitle, legacyDailyArtists))
        } else {
            emptyList()
        }
    }
    val openMixTile: (com.theveloper.pixelplay.presentation.components.redesign.HomeMixTile) -> Unit = { tile ->
        val card = planMixCards.firstOrNull { it.mixId == tile.key }
        if (card != null) {
            discoveryViewModel.onShelfActivated("made_for_you")
            openMix(navController, card)
        } else {
            openMixById(navController, tile.key)
        }
    }

    // "Rewind, replay": the places played from (the plan's tiles), or the last songs when there are none.
    val rewindTiles = remember(planUi.tiles, recentlyPlayedSongs) {
        if (planUi.tiles.isNotEmpty()) {
            planUi.tiles.map { com.theveloper.pixelplay.presentation.components.redesign.HomeRewindTile(it.key, it.title, it.art) }
        } else {
            recentlyPlayedSongs.take(6).map {
                com.theveloper.pixelplay.presentation.components.redesign.HomeRewindTile("song:" + it.song.id, it.song.title, it.song.albumArtUriString)
            }
        }
    }
    val onRewindTile: (com.theveloper.pixelplay.presentation.components.redesign.HomeRewindTile) -> Unit = { tile ->
        val planTile = planUi.tiles.firstOrNull { it.key == tile.key }
        if (planTile != null) {
            if (planTile.mixId.isNotEmpty()) openMixById(navController, planTile.mixId)
            else discoveryViewModel.playTile(planTile) { songs, start -> playerViewModel.showAndPlaySong(start, songs, planTile.title) }
        } else {
            val song = recentlyPlayedQueue.firstOrNull { "song:" + it.id == tile.key }
            if (song != null) playerViewModel.playSongs(songsToPlay = recentlyPlayedQueue, startSong = song, queueName = "Recently Played")
        }
    }

    // "Fresh finds" is the first track shelf; the others follow it in the same style.
    val trackShelves = remember(planUi.owned, planUi.shelves, discovery.shelves) {
        if (planUi.owned) {
            planUi.shelves.filterIsInstance<com.theveloper.pixelplay.presentation.viewmodel.HomeShelfUi.Tracks>().map { it.section }
        } else {
            discovery.shelves
        }
    }
    val stationShelves = remember(planUi.owned, planUi.shelves) {
        if (planUi.owned) planUi.shelves.filterIsInstance<com.theveloper.pixelplay.presentation.viewmodel.HomeShelfUi.Stations>() else emptyList()
    }
    val playTrackShelf: (com.theveloper.pixelplay.data.recommendation.HomeMusicSection, Song) -> Unit = { section, song ->
        if (planUi.owned) {
            discoveryViewModel.playPlanShelf(section, song) { songs, start -> playerViewModel.showAndPlaySong(start, songs, section.title) }
        } else {
            discoveryViewModel.play(section, song) { songs, start -> playerViewModel.showAndPlaySong(start, songs, section.title) }
        }
    }
    val openEndless: () -> Unit = {
        discoveryViewModel.onShelfActivated("made_for_you")
        scope.launch {
            // First open shows the introduction; every other starts (or picks up) the station and opens it.
            if (playerViewModel.brainSession.endless.isIntroSeen()) {
                if (!playerViewModel.brainSession.endless.active.value) playerViewModel.startEndlessListening()
                navController.navigateSafely(Screen.EndlessStation.route)
            } else {
                navController.navigateSafely(Screen.EndlessIntro.route)
            }
        }
    }
    val playYourMix: (() -> Unit)? = if (yourMixSongs.isEmpty()) null else {
        {
            playerViewModel.expectMixPlay(yourMixQueueName, isYourMix = true)
            if (usesFallbackHomeMix) playerViewModel.shuffleAllSongs(queueName = yourMixQueueName)
            else playerViewModel.showAndPlaySong(yourMixSongs.first(), yourMixSongs, yourMixQueueName)
        }
    }
    val footerLinks = listOf(
        com.theveloper.pixelplay.presentation.components.redesign.HomeFooterLink(stringResource(R.string.home_spatial_entry)) {
            navController.navigateSafely(Screen.CloudProcessing.route)
        },
        com.theveloper.pixelplay.presentation.components.redesign.HomeFooterLink(stringResource(R.string.home_shuffle_all_link)) {
            playerViewModel.shuffleAllSongs(queueName = "Shuffle All")
        },
        com.theveloper.pixelplay.presentation.components.redesign.HomeFooterLink(stringResource(R.string.home_stats_link)) {
            navController.navigateSafely(Screen.Stats.route)
        },
        com.theveloper.pixelplay.presentation.components.redesign.HomeFooterLink(stringResource(R.string.home_whats_new)) { showChangelogBottomSheet = true },
        com.theveloper.pixelplay.presentation.components.redesign.HomeFooterLink(stringResource(R.string.home_beta_info)) { showBetaInfoBottomSheet = true },
    )
    val openSettings: () -> Unit = { navController.navigateSafely(Screen.Settings.route) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(discoverySnackbar) },
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        ) { _ ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .graphicsLayer { alpha = homeContentAlpha },
                contentPadding = PaddingValues(
                    bottom = paddingValuesParent.calculateBottomPadding() + 38.dp + bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item(key = "home_header", contentType = "home_header") {
                    com.theveloper.pixelplay.presentation.components.redesign.HomeRedesignHeader(
                        words = greetingWords,
                        onAskTaizo = { showAskTaizo = true },
                        onSettings = openSettings,
                        jobCount = activeJobs.size,
                        onJobs = { showJobsBottomSheet = true },
                    )
                }

                if (showListeningHistoryCard) {
                    item(key = "home_listening_history_card", contentType = "home_listening_history_card") {
                        HomeListeningHistoryCard(
                            onImport = { navController.navigateSafely(Screen.ListeningHistory.route) },
                            onDismiss = listeningHistoryViewModel::dismissHomeCard,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        )
                    }
                }
                // 8.10: while the first radios are fetched, Home says so; it never shows a spinner or an empty state for it.
                if (planUi.knowingTotal > 0) {
                    item(key = "home_knowing", contentType = "home_knowing") { HomeKnowingStrip(planUi.knowingDone, planUi.knowingTotal) }
                }
                if (planUi.offerPicker) {
                    item(key = "home_artist_picker", contentType = "home_artist_picker") {
                        HomeArtistPickerCard(
                            onPick = { navController.navigateSafely(Screen.ArtistPicker.route) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                }

                // Your mix: the day's hero card. Without one (and no plan) the old loading / empty states keep their place.
                if (yourMixSongs.isNotEmpty() || hasPlanYourMix) {
                    item(key = "home_your_mix_hero", contentType = "home_your_mix_hero") {
                        com.theveloper.pixelplay.presentation.components.redesign.HomeYourMixHero(
                            onOpen = { navController.navigateSafely(Screen.YourMixScreen.route) },
                            onPlay = playYourMix,
                        )
                    }
                } else if (!planUi.owned) {
                    item(key = "your_mix_placeholder", contentType = "your_mix_placeholder") {
                        if (shouldShowYourMixLoadingPlaceholder) {
                            YourMixLoadingPlaceholder()
                        } else {
                            YourMixEmptyPlaceholder(
                                onRefresh = {
                                    homePlaceholderRefreshGeneration++
                                    discoveryViewModel.refreshLibrary()
                                    playerViewModel.forceUpdateDailyMix()
                                }
                            )
                        }
                    }
                }

                item(key = "home_endless_entry", contentType = "home_endless_entry") {
                    com.theveloper.pixelplay.presentation.components.redesign.HomeEndlessEntry(
                        onClick = openEndless,
                        onLongClick = { navController.navigateSafely(Screen.EndlessIntro.route) },
                    )
                }

                if (mixTiles.isNotEmpty()) {
                    item(key = "home_mixes", contentType = "home_mixes") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            com.theveloper.pixelplay.presentation.components.redesign.HomeSectionHeader(
                                wide = sectionTitles.mixesA,
                                accent = sectionTitles.mixesB,
                                actionLabel = if (mixTiles.size > 2) stringResource(if (showAllMixes) R.string.home_show_less else R.string.home_see_all) else null,
                                onAction = { showAllMixes = !showAllMixes },
                            )
                            if (showAllMixes) {
                                com.theveloper.pixelplay.presentation.components.redesign.HomeMixGrid(mixTiles, openMixTile)
                            } else {
                                com.theveloper.pixelplay.presentation.components.redesign.HomeMixShelfRow(mixTiles, openMixTile)
                            }
                        }
                    }
                }

                planUi.hero?.let { hero ->
                    item(key = "home_hero", contentType = "home_hero") {
                        HomeHeroCard(hero, onClick = {
                            val song = hero.song
                            if (song != null) discoveryViewModel.playHeroRelease(song) { songs, start -> playerViewModel.showAndPlaySong(start, songs, hero.title) }
                            else openMixById(navController, hero.mixId)
                        })
                    }
                }

                if (rewindTiles.isNotEmpty()) {
                    item(key = "home_rewind", contentType = "home_rewind") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            com.theveloper.pixelplay.presentation.components.redesign.HomeSectionHeader(
                                wide = sectionTitles.recentA,
                                accent = sectionTitles.recentB,
                                actionLabel = if (recentlyPlayedSongs.isNotEmpty()) stringResource(R.string.home_see_all) else null,
                                onAction = { navController.navigateSafely(Screen.RecentlyPlayed.route) },
                            )
                            com.theveloper.pixelplay.presentation.components.redesign.HomeRewindGrid(rewindTiles, onRewindTile)
                        }
                    }
                }

                trackShelves.forEachIndexed { index, section ->
                    item(key = "home_tracks_${section.id}", contentType = "home_tracks") {
                        // On screen for a second counts as seen (the shelf bandit, 5.10); the effect leaves with the item.
                        if (planUi.owned) {
                            LaunchedEffect(section.id) {
                                delay(1_000L)
                                discoveryViewModel.onShelfSeen(section.id)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (index == 0) {
                                com.theveloper.pixelplay.presentation.components.redesign.HomeSectionHeader(
                                    wide = sectionTitles.freshA,
                                    accent = sectionTitles.freshB,
                                    subtitle = stringResource(R.string.home_fresh_subtitle),
                                    actionLabel = if (planUi.newShelves.isNotEmpty()) stringResource(if (showNewShelves) R.string.home_show_less else R.string.home_see_all) else null,
                                    onAction = { showNewShelves = !showNewShelves },
                                )
                            } else {
                                com.theveloper.pixelplay.presentation.components.redesign.HomeSectionHeader(
                                    wide = section.title,
                                    accent = "",
                                    subtitle = section.subtitle,
                                )
                            }
                            com.theveloper.pixelplay.presentation.components.redesign.HomeFreshRow(section.songs, onPlay = { song -> playTrackShelf(section, song) })
                        }
                    }
                    if (index == 0 && showNewShelves) {
                        planUi.newShelves.forEach { shelf ->
                            item(key = "home_new_${shelf.id}", contentType = "home_new_shelf") {
                                when (shelf) {
                                    is com.theveloper.pixelplay.presentation.viewmodel.HomeShelfUi.Tracks ->
                                        HomeDiscoveryShelf(section = shelf.section, currentSongId = currentSong?.id, preparingSection = null, onPlay = playTrackShelf)
                                    is com.theveloper.pixelplay.presentation.viewmodel.HomeShelfUi.Mixes -> HomeMixShelf(shelf, { card ->
                                        discoveryViewModel.onShelfActivated("made_for_you")
                                        openMix(navController, card)
                                    })
                                    is com.theveloper.pixelplay.presentation.viewmodel.HomeShelfUi.Stations -> HomeStationShelf(shelf, { card ->
                                        discoveryViewModel.onShelfActivated("stations")
                                        playerViewModel.startGenreStation(card.kind, card.atlasId, card.name)
                                    })
                                }
                            }
                        }
                    }
                }

                stationShelves.forEach { shelf ->
                    item(key = "home_plan_${shelf.id}", contentType = "home_plan_stations") {
                        LaunchedEffect(shelf.id) {
                            delay(1_000L)
                            discoveryViewModel.onShelfSeen(shelf.id)
                        }
                        HomeStationShelf(shelf, { card ->
                            discoveryViewModel.onShelfActivated("stations")
                            playerViewModel.startGenreStation(card.kind, card.atlasId, card.name)
                        })
                    }
                }

                if (!planUi.owned && discovery.mixes.isNotEmpty()) {
                    item(key = "home_adaptive_mixes", contentType = "home_adaptive_mixes") {
                        HomeDiscoveryMixes(
                            mixes = discovery.mixes,
                            isRefreshing = discovery.isRefreshing,
                            preparingSection = discovery.preparingSection,
                            onRefresh = { discoveryViewModel.refresh(force = true) },
                            onPlay = { section, song ->
                                discoveryViewModel.play(section, song) { songs, start ->
                                    playerViewModel.showAndPlaySong(start, songs, section.title)
                                }
                            }
                        )
                    }
                }

                if (homeMixPreviewSongs.isNotEmpty()) {
                    item(key = "recently_added_section", contentType = "recently_added_section") {
                        RecentlyAddedSection(
                            songs = homeMixPreviewSongs,
                            onSongClick = { song ->
                                playerViewModel.showAndPlaySongFromLibrary(song, queueName = "Just Added")
                            }
                        )
                    }
                }

                item(key = "home_ask_taizo_card", contentType = "home_ask_taizo_card") {
                    com.theveloper.pixelplay.presentation.components.redesign.HomeAskTaizoCard(
                        exampleSong = topSong?.title ?: currentSong?.title,
                        onClick = { showAskTaizo = true },
                    )
                }

                if (homeStatsOverview != null) {
                    item(key = "listening_stats_preview", contentType = "listening_stats_preview") {
                        StatsOverviewCard(
                            summary = homeStatsOverview,
                            onClick = { navController.navigateSafely(Screen.Stats.route) }
                        )
                    }
                }

                item(key = "home_footer_links", contentType = "home_footer_links") {
                    com.theveloper.pixelplay.presentation.components.redesign.HomeFooterLinks(footerLinks)
                }
            }
        }
        if (collapsedAlpha > 0.01f) {
            com.theveloper.pixelplay.presentation.components.redesign.HomeCollapsedHeader(
                words = greetingWords,
                alpha = { collapsedAlpha },
                onAskTaizo = { showAskTaizo = true },
                onSettings = openSettings,
                jobCount = activeJobs.size,
                onJobs = { showJobsBottomSheet = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(bottomGradientHeight)
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.2f to Color.Transparent,
                            0.8f to MaterialTheme.colorScheme.surfaceContainerLowest,
                            1.0f to MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                )
        )
    }
    if (showChangelogBottomSheet) {
        AdaptiveModalBottomSheet(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            onDismissRequest = { showChangelogBottomSheet = false },
            sheetState = sheetState
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
            ChangelogBottomSheet()
            }
        }
    }
    if (showAskTaizo) {
        com.theveloper.pixelplay.presentation.components.taizo.AskTaizoSheet(
            playerViewModel = playerViewModel,
            onDismissRequest = { showAskTaizo = false }
        )
    }

    if (showJobsBottomSheet) {
        AdaptiveModalBottomSheet(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            onDismissRequest = { showJobsBottomSheet = false },
            sheetState = jobsSheetState
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                com.theveloper.pixelplay.presentation.components.JobsBottomSheet(jobs = activeJobs)
            }
        }
    }
    if (showBetaInfoBottomSheet) {
        AdaptiveModalBottomSheet(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            onDismissRequest = { showBetaInfoBottomSheet = false },
            sheetState = betaSheetState,
            //contentWindowInsets = { WindowInsets.statusBars.only(WindowInsets.statusBars) }
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
            BetaInfoBottomSheet()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun YourMixLoadingPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(256.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        LoadingIndicator(
            modifier = Modifier.size(128.dp),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun YourMixEmptyPlaceholder(
    onRefresh: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 256.dp)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.size(76.dp),
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusTL = 28.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusBR = 28.dp,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusBL = 28.dp,
                    smoothnessAsPercentBR = 60,
                    cornerRadiusTR = 28.dp,
                    smoothnessAsPercentBL = 60,
                ),
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_empty_placeholder_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.home_empty_placeholder_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            LocalConnectSpotify.current?.let { connectSpotify ->
                androidx.compose.material3.Button(onClick = connectSpotify) {
                    Text(text = stringResource(R.string.library_connect_spotify))
                }
            }

            FilledTonalButton(
                onClick = onRefresh,
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusTL = 22.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusBR = 22.dp,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusBL = 22.dp,
                    smoothnessAsPercentBR = 60,
                    cornerRadiusTR = 22.dp,
                    smoothnessAsPercentBL = 60,
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.home_empty_placeholder_refresh))
            }
        }
    }
}

// SongListItem (modificado para aceptar parámetros individuales)
@Composable
fun SongListItemFavs(
    modifier: Modifier = Modifier,
    cardCorners: Dp = 12.dp,
    title: String,
    artist: String,
    albumArtUrl: String?,
    isPlaying: Boolean,
    isCurrentSong: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = if (isCurrentSong) colors.primaryContainer.copy(alpha = 0.46f) else colors.surfaceContainer
    val contentColor = if (isCurrentSong) colors.primary else colors.onSurface

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(cardCorners),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(0.9f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmartImage(
                    model = albumArtUrl,
                    contentDescription = stringResource(R.string.common_album_art_for_title, title),
                    contentScale = ContentScale.Crop,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.Normal,
                        color = contentColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist, style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            if (isCurrentSong) {
                PlayingEqIcon(
                    modifier = Modifier
                        .weight(0.1f)
                        .padding(start = 8.dp)
                        .size(width = 18.dp, height = 16.dp), // similar al tamaño del ícono
                    color = colors.primary,
                    isPlaying = isPlaying  // o conectalo a tu estado real de reproducción
                )
            }
        }
    }
}

// Wrapper Composable for SongListItemFavs to isolate state observation
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SongListItemFavsWrapper(
    song: Song,
    playerViewModel: PlayerViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Only (currentSongId, isPlaying): lyrics, duration and buffering emissions don't reach the row.
    val playbackRowState = rememberPlaybackRowState(playerViewModel.stablePlayerState)
    val isCurrentSong by rememberIsCurrentSong(playbackRowState, song.id)
    val isPlaying by remember(playbackRowState) { derivedStateOf { playbackRowState.value.isPlaying } }

    // Call the presentational composable
    SongListItemFavs(
        modifier = modifier,
        cardCorners = 0.dp,
        title = song.title,
        artist = song.displayArtist,
        albumArtUrl = song.albumArtUriString,
        isPlaying = isPlaying,
        isCurrentSong = isCurrentSong,
        onClick = onClick
    )
}

/** A little longer than the 350 ms shared-axis pop into Home. */
private const val HomeDiscoveryStartRefreshDelayMs = 450L

/** A card of "Made for you" was tapped (8.2): Daily Mix 1 and Your Mix have their own screens, the others open the list. */
private fun openMix(navController: NavController, card: com.theveloper.pixelplay.presentation.viewmodel.MixCardUi) {
    when {
        card.mixId == "your" -> navController.navigateSafely(Screen.YourMixScreen.route)
        card.mixId == "daily" -> navController.navigateSafely(Screen.DailyMixScreen.route)
        else -> navController.navigateSafely(Screen.MixDetail.createRoute(card.mixId))
    }
}

private fun openMixById(navController: NavController, mixId: String) {
    when {
        mixId.isEmpty() -> Unit
        mixId == "your" -> navController.navigateSafely(Screen.YourMixScreen.route)
        mixId == "daily" -> navController.navigateSafely(Screen.DailyMixScreen.route)
        else -> navController.navigateSafely(Screen.MixDetail.createRoute(mixId))
    }
}

/** "Pick three artists you love" (8.10): offered to a thin library. */
@androidx.compose.runtime.Composable
private fun HomeArtistPickerCard(onPick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Card(
        onClick = onPick,
        modifier = modifier,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(com.theveloper.pixelplay.R.string.pick_artists_title),
                style = MaterialTheme.typography.titleLarge,
            )
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(com.theveloper.pixelplay.R.string.home_artist_picker_card),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

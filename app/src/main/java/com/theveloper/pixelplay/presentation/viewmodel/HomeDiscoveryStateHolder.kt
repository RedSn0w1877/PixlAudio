package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import com.theveloper.pixelplay.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.history.ImportedTasteMerge
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.HomeCatalogRepository
import com.theveloper.pixelplay.data.recommendation.HomeCatalogSnapshot
import com.theveloper.pixelplay.data.recommendation.HomeMusicSection
import com.theveloper.pixelplay.data.recommendation.HomeRecommendationPlanner
import com.theveloper.pixelplay.data.recommendation.MusicRecommendationEngine
import com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
import com.theveloper.pixelplay.data.recommendation.MuselleVariant
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.worker.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class HomeDiscoveryState(
    val mixes: ImmutableList<HomeMusicSection> = persistentListOf(),
    val shelves: ImmutableList<HomeMusicSection> = persistentListOf(),
    val isRefreshing: Boolean = false,
    val discoveryEnabled: Boolean = true,
    val preparingSection: String? = null,
    val message: String? = null
)

/** Process-lived snapshots stay stable while scrolling and are rebuilt when Home is revisited. */
@Singleton
class HomeDiscoveryStateHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val music: MusicRepository,
    private val engagement: EngagementDao,
    private val taste: MusicTasteRepository,
    private val catalog: HomeCatalogRepository,
    private val blockedArtists: com.theveloper.pixelplay.data.blocking.BlockedArtistsRepository,
    private val tasteAdjustments: TasteAdjustmentRepository,
    /** The music brain's "Because you listened to" rows (docs/brain-spec.md, 8.1). */
    private val brainHomeRows: com.theveloper.pixelplay.data.brain.BrainHomeRows,
    private val brainPlayTags: com.theveloper.pixelplay.data.brain.BrainPlayTags,
    /** "New from artists you play" (docs/brain-spec.md, 6.3): found by the refresh worker, read from one small file. */
    private val newReleases: com.theveloper.pixelplay.data.brain.NewReleases,
    private val brainSwitch: com.theveloper.pixelplay.data.brain.BrainSwitch,
    /** Stage 2b: when the brain's Home plan owns the page, this holder does not rank the library at all. */
    private val plan: HomePlanStateHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // The ranking/planning passes are pure CPU over the whole library: run them on one Default
    // thread instead of the 64-thread IO pool, so they never compete with Coil decodes and the
    // UI for more than one core. DB, preferences and network stay on IO.
    private val planningDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val mutableState = MutableStateFlow(HomeDiscoveryState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var refreshPending = false
    // Written on IO when a refresh finishes, read on Main.
    @Volatile private var lastRefreshFinishedAt = 0L
    @Volatile private var lastRefreshHadLibrary = false
    // The brain rows on screen, by section id: what a tap on one of their cards plays.
    @Volatile private var brainRowsById: Map<String, com.theveloper.pixelplay.data.brain.PlayableBrainRow> = emptyMap()

    init {
        // A taste change (boost, mute, block...) makes the snapshot stale: the next visit to Home re-plans
        // instead of waiting out the short auto-refresh window.
        scope.launch { tasteAdjustments.version.collect { lastRefreshFinishedAt = 0L } }
    }

    // Called on Main by the ViewModel; keep one catalog refresh in flight across Home recreation.
    fun refresh(force: Boolean = false) {
        if (refreshJob?.isActive == true) {
            refreshPending = true
            return
        }
        val now = System.currentTimeMillis()
        // Home asks on every ON_START (every pop back to it) and again once its mixes load, so a
        // cold start ran this whole-library pass twice and every return re-ran it during the pop
        // transition. A snapshot built from a non-empty library moments ago is still current.
        if (!force && lastRefreshHadLibrary && now - lastRefreshFinishedAt < AUTO_REFRESH_MIN_INTERVAL_MS) {
            return
        }
        refreshJob = scope.launch {
            // The brain's plan owns Home (8.1, stage 2): it is read from its own file and built in the background when due, so the
            // whole-library ranking below, which used to run on every Home start, does not run at all.
            try {
                if (plan.ownsHome()) {
                    refreshPending = false
                    val withoutBrainRows = mutableState.value.shelves
                    if (withoutBrainRows.isNotEmpty() || mutableState.value.mixes.isNotEmpty()) {
                        mutableState.update { it.copy(mixes = persistentListOf(), shelves = persistentListOf(), isRefreshing = false) }
                    }
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Home: asking the plan failed; the legacy Home runs")
            }
            mutableState.update { it.copy(isRefreshing = true) }
            try {
                val enabled = taste.state.first().discoveryEnabled
                // The brain's rows come from their saved file, before anything is planned: Home shows
                // them at once and they do not change under the thumb. A rebuild (below) is what
                // the NEXT visit shows; only a Home with no row yet takes a new one right away.
                var brainRows = if (enabled) {
                    try {
                        brainHomeRows.cached()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else emptyList()
                // New from artists you play: from its saved file, with the brain engine on. A missing row leaves the
                // legacy "New from artists you enjoy" shelf where it was; a present one replaces it (6.3).
                var releaseRow: com.theveloper.pixelplay.data.brain.BrainReleaseRow? = if (enabled) {
                    try {
                        if (brainSwitch.engine() == com.theveloper.pixelplay.data.brain.BrainEngineChoice.BRAIN) {
                            newReleases.row(
                                blockedArtists.current,
                                tasteAdjustments.snapshot(),
                                // A card the listener has saved since is in the library now: it leaves the row.
                                com.theveloper.pixelplay.data.brain.LibraryIndex(music.getAllSongsOnce()),
                            )
                        } else null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                } else null
                var legacyShelves: ImmutableList<HomeMusicSection> = mutableState.value.shelves
                    .filterNot {
                        it.id.startsWith(com.theveloper.pixelplay.data.brain.BrainHomeRow.ID_PREFIX) ||
                            it.id == com.theveloper.pixelplay.data.brain.BrainReleaseRow.ID
                    }
                    .toImmutableList()
                fun publishShelves() {
                    val byId = HashMap<String, com.theveloper.pixelplay.data.brain.PlayableBrainRow>()
                    for (row in brainRows) byId[row.id] = row
                    releaseRow?.let { byId[it.id] = it }
                    brainRowsById = byId
                    val sections = ArrayList<HomeMusicSection>(brainRows.size + 1)
                    for (row in brainRows) sections.add(brainSection(row))
                    releaseRow?.let { sections.add(releaseSection(it)) }
                    mutableState.update { it.copy(shelves = (sections + legacyShelves).toImmutableList()) }
                }
                if (brainRows.isNotEmpty() || brainRowsById.isNotEmpty() || releaseRow != null) publishShelves()
                // Blocked artists never reach Home recommendations.
                val library = blockedArtists.current.filterAllowed(music.getAllSongsOnce())
                lastRefreshHadLibrary = library.isNotEmpty()
                val favorites = music.getFavoriteSongIdsOnce()
                val storedSignals = taste.signals()
                val engagements = engagement.getAllEngagements()
                // Listening history imported from Spotify / YouTube Music: play counts and artist affinity.
                val importedHistory = taste.importedHistory()
                val artistPriors = taste.importedArtistPriors()
                // What the listener asked Ask Taizo to favour, mute or block.
                val adjustments = tasteAdjustments.snapshot()
                val (signals, history) = withContext(planningDispatcher) {
                    val storedHistory = engagements.associate { it.songId to MusicRecommendationEngine.History(
                        it.playCount, it.totalPlayDurationMs, it.lastPlayedTimestamp
                    ) }
                    val signals = library.associate { it.id to (storedSignals[it.id] ?: storedSignals["spotify_${it.spotifyId}"] ?: MusicRecommendationEngine.Signal()) }
                    val history = library.associate {
                        it.id to ImportedTasteMerge.history(storedHistory[it.id] ?: storedHistory["spotify_${it.spotifyId}"], importedHistory[it.id])
                    }
                    signals to history
                }
                val cache = if (enabled) catalog.cached() else HomeCatalogSnapshot()
                suspend fun publish(snapshot: HomeCatalogSnapshot) {
                    // Nothing about picks is paid (decision D17): the Muselle 2 gate on Home is gone, every listener gets the full ranking.
                    val variant = MuselleVariant.PLUS
                    val planned = withContext(planningDispatcher) {
                        HomeRecommendationPlanner.plan(
                            library, favorites, signals, history,
                            // No `discoveries`: the legacy "Beyond your library" shelf is replaced by the
                            // brain's rows, which keep their own order and reasons (decision D10).
                            discoveries = emptyList(),
                            // The brain's New releases row replaces the legacy shelf when it has one (6.3).
                            releases = if (releaseRow != null) emptyList()
                            else blockedArtists.current.filterAllowed(snapshot.releases.filter { HomeRecommendationPlanner.isRecentRelease(it.releaseDate, LocalDate.now()) }.map { it.song }),
                            nowMs = now, seed = LocalDate.now().toEpochDay(), variant = variant,
                            artistPriors = artistPriors, adjustments = adjustments
                        )
                    }
                    legacyShelves = planned.shelves
                    mutableState.update { it.copy(mixes = planned.mixes, discoveryEnabled = enabled) }
                    publishShelves()
                }
                // Local shelves and cached metadata render before any network request begins.
                publish(cache)
                // Ask first: with the catalog cached (under six hours), offline or backing off, refresh()
                // hands back the snapshot we already planned, so ranking the library for seeds and
                // planning the same Home a second time was pure waste.
                if (enabled && library.isNotEmpty() && catalog.isRefreshDue(force)) {
                    val seeds = withContext(planningDispatcher) {
                        MusicRecommendationEngine.select(
                            MusicRecommendationEngine.rank(library, favorites, signals, history, now, LocalDate.now().toEpochDay(), artistPriors, adjustments), 20
                        ).map { it.song }
                    }
                    val refreshed = catalog.refresh(seeds, library, force)
                    // An unchanged snapshot plans to exactly what is already published.
                    if (refreshed != cache) publish(refreshed)
                }
                if (enabled) {
                    // Never at app start: this returns at once until the brain is in memory, which
                    // takes music having played in this process.
                    val rebuilt = brainHomeRows.refreshIfDue()
                    if (rebuilt != null && brainRows.isEmpty() && rebuilt.isNotEmpty()) {
                        brainRows = rebuilt
                        publishShelves()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Timber.w(e, "Home recommendations unavailable; retaining the last snapshot")
                if (force) mutableState.update { it.copy(message = "Couldn't refresh right now. Your saved picks are still here.") }
            } finally {
                lastRefreshFinishedAt = System.currentTimeMillis()
                mutableState.update { it.copy(isRefreshing = false) }
                withContext(Dispatchers.Main.immediate) {
                    refreshJob = null
                    if (refreshPending) {
                        refreshPending = false
                        refresh()
                    }
                }
            }
        }
    }

    suspend fun prepare(section: HomeMusicSection, start: Song, onReady: (List<Song>, Song) -> Unit) {
        if (state.value.preparingSection != null) return
        mutableState.update { it.copy(preparingSection = section.id) }
        try {
            val brainRow = brainRowsById[section.id]
            if (brainRow != null) {
                // Tracks from outside the library: they go into `playable` first, so they can play, and
                // the play carries the row and each card's reason into the queue and the event log.
                withContext(Dispatchers.IO) { brainHomeRows.prepareForPlayback(brainRow) }
                brainPlayTags.expect(queueName = section.title, bySongId = brainRow.tags())
                onReady(section.songs, start)
                return
            }
            val songs = withContext(Dispatchers.IO) { catalog.playableSongs(section.songs) }
            val selected = songs.firstOrNull { it.id == start.id }
            if (selected == null) mutableState.update { it.copy(message = "This song isn't available right now. Connect to the internet and try again.") }
            else onReady(songs, selected)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Timber.w(e, "Could not prepare Home selection")
            mutableState.update { it.copy(message = "Couldn't prepare these songs. Please try again.") }
        } finally {
            mutableState.update { it.copy(preparingSection = null) }
        }
    }

    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    /** The New releases row as the shelf component draws it: each card says whose release it is. */
    private fun releaseSection(row: com.theveloper.pixelplay.data.brain.BrainReleaseRow): HomeMusicSection {
        val reasons = HashMap<String, String>(row.items.size * 2)
        for (item in row.items) {
            row.artistBySongId[item.song.id]?.takeIf { it.isNotBlank() }
                ?.let { reasons[item.song.id] = context.getString(R.string.brain_why_new_release, it) }
        }
        return HomeMusicSection(
            id = row.id,
            title = context.getString(R.string.brain_home_new_releases),
            subtitle = context.getString(R.string.brain_home_new_releases_subtitle),
            songs = row.items.map { it.song }.toImmutableList(),
            reasons = reasons.toImmutableMap()
        )
    }

    /** A brain row as the shelf component draws it: the title names the anchor, each card says why. */
    private fun brainSection(row: com.theveloper.pixelplay.data.brain.BrainHomeRow): HomeMusicSection {
        val reasons = HashMap<String, String>(row.items.size * 2)
        for (item in row.items) {
            // On the card: what the pick's arm says (8.8). The row's own "because you listened to" is
            // its title, and is the line the track carries into the queue.
            val reason = if (item.arm == com.theveloper.pixelplay.brain.Arm.ARTIST) {
                item.track.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                    ?.let { context.getString(R.string.brain_why_artist_more, it) }
            } else {
                row.anchor.primaryArtist.takeIf { it.isNotBlank() }
                    ?.let { context.getString(R.string.brain_why_radio_fans, it) }
            }
            if (reason != null) reasons[item.song.id] = reason
        }
        return HomeMusicSection(
            id = row.id,
            title = context.getString(R.string.brain_home_because_listened, row.anchor.title),
            subtitle = context.getString(R.string.brain_home_because_listened_subtitle),
            songs = row.items.map { it.song }.toImmutableList(),
            reasons = reasons.toImmutableMap()
        )
    }

    private companion object {
        const val AUTO_REFRESH_MIN_INTERVAL_MS = 60_000L
    }
}

@HiltViewModel
class HomeDiscoveryViewModel @Inject constructor(
    private val holder: HomeDiscoveryStateHolder,
    private val planHolder: HomePlanStateHolder,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val syncManager: SyncManager
) : ViewModel() {
    val state = holder.state

    /** The brain's Home plan (docs/brain-spec.md, 8.1, stage 2); `planState.owned` false means the legacy Home above is the page. */
    val planState = planHolder.state
    fun onHomeShown() = planHolder.onHomeShown()
    fun onArtistsPicked() = planHolder.onArtistsPicked()
    fun clearPlanMessage() = planHolder.clearMessage()
    fun playPlanShelf(section: com.theveloper.pixelplay.data.recommendation.HomeMusicSection, start: Song, onReady: (List<Song>, Song) -> Unit) {
        viewModelScope.launch { planHolder.prepare(section, start, onReady) }
    }
    fun playHeroRelease(song: Song, onReady: (List<Song>, Song) -> Unit) {
        viewModelScope.launch { planHolder.prepareSingle(song, onReady) }
    }
    fun playTile(tile: TileUi, onReady: (List<Song>, Song) -> Unit) {
        viewModelScope.launch {
            val songs = planHolder.songsOf(tile)
            if (songs.isNotEmpty()) onReady(songs, songs.first())
        }
    }
    val pendingHistoryImport = userPreferencesRepository.pendingHistoryImportFlow
    fun clearPendingHistoryImport() {
        viewModelScope.launch { userPreferencesRepository.setPendingHistoryImport(false) }
    }

    // Home used to build a whole SettingsViewModel (dozens of collectors, an AudioTrack probe)
    // just for this flag and the library refresh below.
    /** `null` until read, so the clean-install disclaimer never flashes. */
    val beta05CleanInstallDisclaimerDismissed: StateFlow<Boolean?> =
        userPreferencesRepository.beta05CleanInstallDisclaimerDismissedFlow
            .map<Boolean, Boolean?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setBeta05CleanInstallDisclaimerDismissed(dismissed: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setBeta05CleanInstallDisclaimerDismissed(dismissed) }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            if (syncManager.isSyncing.first()) return@launch
            syncManager.forceRefresh()
        }
    }
    fun refresh(force: Boolean = false) = holder.refresh(force)
    fun play(section: HomeMusicSection, start: Song, onReady: (List<Song>, Song) -> Unit) {
        viewModelScope.launch { holder.prepare(section, start, onReady) }
    }
    fun clearMessage() = holder.clearMessage()
}

package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.HomeCatalogRepository
import com.theveloper.pixelplay.data.recommendation.HomeCatalogSnapshot
import com.theveloper.pixelplay.data.recommendation.HomeMusicSection
import com.theveloper.pixelplay.data.recommendation.HomeRecommendationPlanner
import com.theveloper.pixelplay.data.recommendation.MusicRecommendationEngine
import com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
import com.theveloper.pixelplay.data.recommendation.MuselleVariant
import com.theveloper.pixelplay.data.premium.PlusLicenseManager
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
    private val catalog: HomeCatalogRepository
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
            mutableState.update { it.copy(isRefreshing = true) }
            try {
                val library = music.getAllSongsOnce()
                lastRefreshHadLibrary = library.isNotEmpty()
                val favorites = music.getFavoriteSongIdsOnce()
                val storedSignals = taste.signals()
                val engagements = engagement.getAllEngagements()
                val (signals, history) = withContext(planningDispatcher) {
                    val storedHistory = engagements.associate { it.songId to MusicRecommendationEngine.History(
                        it.playCount, it.totalPlayDurationMs, it.lastPlayedTimestamp
                    ) }
                    val signals = library.associate { it.id to (storedSignals[it.id] ?: storedSignals["spotify_${it.spotifyId}"] ?: MusicRecommendationEngine.Signal()) }
                    val history = library.associate { it.id to (storedHistory[it.id] ?: storedHistory["spotify_${it.spotifyId}"] ?: MusicRecommendationEngine.History()) }
                    signals to history
                }
                val enabled = taste.state.first().discoveryEnabled
                val cache = if (enabled) catalog.cached() else HomeCatalogSnapshot()
                suspend fun publish(snapshot: HomeCatalogSnapshot) {
                    val variant = if (PlusLicenseManager(context).activeEntitlement().isActive(now)) {
                        MuselleVariant.PLUS
                    } else MuselleVariant.BASIC
                    val planned = withContext(planningDispatcher) {
                        HomeRecommendationPlanner.plan(
                            library, favorites, signals, history,
                            discoveries = snapshot.discoveries.map { it.song },
                            releases = snapshot.releases.filter { HomeRecommendationPlanner.isRecentRelease(it.releaseDate, LocalDate.now()) }.map { it.song },
                            nowMs = now, seed = LocalDate.now().toEpochDay(), variant = variant
                        )
                    }
                    mutableState.update { it.copy(mixes = planned.mixes, shelves = planned.shelves, discoveryEnabled = enabled) }
                }
                // Local shelves and cached metadata render before any network request begins.
                publish(cache)
                if (enabled && library.isNotEmpty()) {
                    val seeds = withContext(planningDispatcher) {
                        MusicRecommendationEngine.select(
                            MusicRecommendationEngine.rank(library, favorites, signals, history, now, LocalDate.now().toEpochDay()), 20
                        ).map { it.song }
                    }
                    publish(catalog.refresh(seeds, library, force))
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

    private companion object {
        const val AUTO_REFRESH_MIN_INTERVAL_MS = 60_000L
    }
}

@HiltViewModel
class HomeDiscoveryViewModel @Inject constructor(
    private val holder: HomeDiscoveryStateHolder,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val syncManager: SyncManager
) : ViewModel() {
    val state = holder.state

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

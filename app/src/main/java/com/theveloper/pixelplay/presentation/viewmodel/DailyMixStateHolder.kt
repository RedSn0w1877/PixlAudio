package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.worker.AiWorkerManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Daily Mix and Your Mix state.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Generate and update daily/your mixes
 * - Persist and restore mix state
 * - Check if mix needs updating based on day change
 */
@Singleton
class DailyMixStateHolder @Inject constructor(
    private val dailyMixManager: DailyMixManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val musicRepository: MusicRepository,
    private val aiWorkerManager: AiWorkerManager,
    private val musicDiscoveryRepository: com.theveloper.pixelplay.data.recommendation.MusicDiscoveryRepository
) {
    private val persistenceScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var scope: CoroutineScope? = null
    private var updateJob: Job? = null

    private val _dailyMixSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val dailyMixSongs: StateFlow<ImmutableList<Song>> = _dailyMixSongs.asStateFlow()

    private val _yourMixSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val yourMixSongs: StateFlow<ImmutableList<Song>> = _yourMixSongs.asStateFlow()

    /**
     * Initialize with coroutine scope from ViewModel.
     */
    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        aiWorkerManager.ensurePeriodicDiscovery()
    }

    /**
     * Remove a song from the daily mix.
     */
    fun removeFromDailyMix(songId: String) {
        _dailyMixSongs.update { currentList ->
            currentList.filterNot { it.id == songId }.toImmutableList()
        }
        persistenceScope.launch { userPreferencesRepository.saveDailyMixSongIds(_dailyMixSongs.value.map { it.id }) }
    }

    /**
     * Update the daily mix with new songs.
     * Uses getAllSongsOnce() to load songs on-demand instead of keeping a permanent subscription.
     */
    fun updateDailyMix(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>, force: Boolean = false): Job? {
        updateJob?.cancel()
        updateJob = scope?.launch(Dispatchers.IO) {
            // Startup and library sync both call here. Keep today's saved discoveries and
            // explicit edits instead of regenerating from the local library on every launch.
            val lastUpdate = userPreferencesRepository.lastDailyMixUpdateFlow.first()
            val zone = java.time.ZoneId.systemDefault()
            val savedToday = lastUpdate > 0 && java.time.Instant.ofEpochMilli(lastUpdate)
                .atZone(zone).toLocalDate() == java.time.LocalDate.now(zone)
            if (!force && savedToday) {
                restoreSavedMixes()
                if (_dailyMixSongs.value.isNotEmpty()) return@launch
            }
            val allSongs = musicRepository.getAllSongsOnce()
            if (allSongs.isNotEmpty()) {
                val favoriteIds = favoriteSongIdsFlow.first()

                // Generate daily mix
                val mix = dailyMixManager.generateDailyMix(allSongs, favoriteIds)
                _dailyMixSongs.value = mix.toImmutableList()
                userPreferencesRepository.saveDailyMixSongIds(mix.map { it.id })

                // Generate your mix
                val yourMix = dailyMixManager.generateYourMix(allSongs, favoriteIds)
                _yourMixSongs.value = yourMix.toImmutableList()
                userPreferencesRepository.saveYourMixSongIds(yourMix.map { it.id })
                userPreferencesRepository.saveLastDailyMixUpdateTimestamp(System.currentTimeMillis())

                // A local mix is available immediately. Bounded catalog discovery quietly
                // adds new music when a connection is available.
                aiWorkerManager.enqueueDailyMixRefresh()
            }
            // An empty transient library during startup must not erase a saved mix.
        }
        return updateJob
    }

    /**
     * Load persisted daily mix from storage using direct DB queries by IDs
     * instead of combining with the full allSongs flow.
     */
    fun loadPersistedDailyMix(): Job? = scope?.launch(Dispatchers.IO) { restoreSavedMixes() }

    private suspend fun restoreSavedMixes() {
        suspend fun restore(ids: List<String>, state: MutableStateFlow<ImmutableList<Song>>) {
            if (ids.isEmpty() || state.value.isNotEmpty()) return
            val songs = musicRepository.getSongsByIds(ids).first()
            val songMap = songs.associateBy { it.id }
            val ordered = ids.mapNotNull { songMap[it] }.toImmutableList()
            // Database hydration suspends: a newer refresh may have published meanwhile.
            state.update { current -> if (current.isEmpty()) ordered else current }
        }
        restore(userPreferencesRepository.dailyMixSongIdsFlow.first(), _dailyMixSongs)
        restore(userPreferencesRepository.yourMixSongIdsFlow.first(), _yourMixSongs)
    }

    private var regenerateJob: Job? = null

    private val _isRegeneratingYourMix = MutableStateFlow(false)
    val isRegeneratingYourMix: StateFlow<Boolean> = _isRegeneratingYourMix.asStateFlow()

    /**
     * Reshuffles Your Mix on demand with a fresh random draw instead of the day-seeded one, so
     * repeated taps give a genuinely different mix instead of reproducing today's fixed result.
     */
    fun regenerateYourMix(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        regenerateJob?.cancel()
        regenerateJob = scope?.launch(Dispatchers.IO) {
            _isRegeneratingYourMix.value = true
            try {
                val allSongs = musicRepository.getAllSongsOnce()
                if (allSongs.isNotEmpty()) {
                    val favoriteIds = favoriteSongIdsFlow.first()
                    val yourMix = dailyMixManager.regenerateYourMix(allSongs, favoriteIds)
                    val yourMixWithOnline = augmentWithOnlineDiscovery(yourMix)
                    _yourMixSongs.value = yourMixWithOnline.toImmutableList()
                    userPreferencesRepository.saveYourMixSongIds(yourMixWithOnline.map { it.id })
                }
            } finally {
                _isRegeneratingYourMix.value = false
            }
        }
    }

    /** Add catalog discoveries with a bounded request; offline keeps the local result. */
    private suspend fun augmentWithOnlineDiscovery(
        baseMix: List<Song>,
        maxOnlineSongs: Int = 6
    ): List<Song> {
        return musicDiscoveryRepository.augment(baseMix, musicRepository.getAllSongsOnce(), maxOnlineSongs)
    }

    /**
     * Force update the daily mix regardless of day.
     */
    fun forceUpdate(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        updateDailyMix(favoriteSongIdsFlow, force = true)
    }

    /**
     * Check if daily mix needs updating (new day) and update if so.
     */
    fun checkAndUpdateIfNeeded(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        scope?.launch {
            val lastUpdate = userPreferencesRepository.lastDailyMixUpdateFlow.first()
            val zone = java.time.ZoneId.systemDefault()
            val lastDay = java.time.Instant.ofEpochMilli(lastUpdate).atZone(zone).toLocalDate()
            if (lastUpdate <= 0 || java.time.LocalDate.now(zone) != lastDay || _dailyMixSongs.value.isEmpty()) {
                updateDailyMix(favoriteSongIdsFlow)
            }
        }
    }

    /**
     * Set the daily mix songs directly (used for AI-generated mixes).
     */
    fun setDailyMixSongs(songs: List<Song>) {
        _dailyMixSongs.value = songs.toImmutableList()
        persistenceScope.launch {
            userPreferencesRepository.saveDailyMixSongIds(songs.map { it.id })
            userPreferencesRepository.saveLastDailyMixUpdateTimestamp(System.currentTimeMillis())
        }
    }

    suspend fun publishBackgroundMix(daily: List<Song>, yourMix: List<Song>) {
        if (daily.isNotEmpty()) {
            userPreferencesRepository.saveDailyMixSongIds(daily.map { it.id })
            _dailyMixSongs.value = daily.toImmutableList()
            userPreferencesRepository.saveLastDailyMixUpdateTimestamp(System.currentTimeMillis())
        }
        if (yourMix.isNotEmpty()) {
            userPreferencesRepository.saveYourMixSongIds(yourMix.map { it.id })
            _yourMixSongs.value = yourMix.toImmutableList()
        }
    }

    /**
     * Get a candidate pool for AI playlist generation.
     */
    suspend fun getCandidatePool(
        allSongs: List<Song>,
        favoriteIds: Set<String>,
        maxSize: Int = 100
    ): List<Song> {
        return dailyMixManager.generateDailyMix(allSongs, favoriteIds, maxSize)
    }

    fun onCleared() {
        updateJob?.cancel()
        regenerateJob?.cancel()
        scope = null
    }
}

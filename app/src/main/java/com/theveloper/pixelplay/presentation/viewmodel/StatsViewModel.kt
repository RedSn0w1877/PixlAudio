package com.theveloper.pixelplay.presentation.viewmodel

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository.PlaybackStatsSummary
import com.theveloper.pixelplay.data.stats.StatsTimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val musicRepository: MusicRepository
) : ViewModel() {

    data class StatsUiState(
        val selectedRange: StatsTimeRange = StatsTimeRange.WEEK,
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val summary: PlaybackStatsSummary? = null,
        val availableRanges: List<StatsTimeRange> = StatsTimeRange.entries
    )

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    private val _weeklyOverview = MutableStateFlow<PlaybackStatsSummary?>(null)
    val weeklyOverview: StateFlow<PlaybackStatsSummary?> = _weeklyOverview.asStateFlow()

    private val _homeOverview = MutableStateFlow<PlaybackStatsSummary?>(null)
    val homeOverview: StateFlow<PlaybackStatsSummary?> = _homeOverview.asStateFlow()

    @Volatile
    private var cachedSongs: List<Song>? = null

    // init starts the WEEK summary and the Home overview at the same time; without this both
    // raced past the empty cache and loaded the whole library twice.
    private val songsLoadMutex = Mutex()

    init {
        observeStatsRefreshFlow()
        // The Home overview starts from the WEEK summary, so it reuses the one computed here
        // instead of computing the same week a second time in parallel.
        refreshRange(
            range = StatsTimeRange.WEEK,
            showLoading = true,
            updateWeeklyOverview = true,
            refreshHomeOverviewAfter = true
        )
    }

    fun onRangeSelected(range: StatsTimeRange) {
        if (range == _uiState.value.selectedRange && !_uiState.value.isLoading) {
            return
        }
        refreshRange(
            range = range,
            showLoading = true,
            updateWeeklyOverview = range == StatsTimeRange.WEEK
        )
    }

    fun refreshWeeklyOverview(refreshHomeOverviewAfter: Boolean = false) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val songs = loadSongs()
                    playbackStatsRepository.loadSummary(StatsTimeRange.WEEK, songs)
                }
            }.onSuccess { summary ->
                _weeklyOverview.value = summary
            }.onFailure { throwable ->
                Timber.e(throwable, "Failed to load weekly stats overview")
                _weeklyOverview.value = null
            }
            if (refreshHomeOverviewAfter) {
                refreshHomeOverview(weekSummary = result.getOrNull())
            }
        }
    }

    /**
     * The first range with listening activity, WEEK first. [weekSummary], when given, is a WEEK
     * summary just computed from the same data, reused instead of computing it again.
     */
    fun refreshHomeOverview(weekSummary: PlaybackStatsSummary? = null) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val songs = loadSongs()
                    for (range in HomeOverviewRanges) {
                        val summary = weekSummary?.takeIf { range == StatsTimeRange.WEEK }
                            ?: playbackStatsRepository.loadSummary(range, songs)
                        if (summary.hasListeningActivity()) {
                            return@withContext summary
                        }
                    }
                    null
                }
            }.onSuccess { summary ->
                _homeOverview.value = summary
            }.onFailure { throwable ->
                Timber.e(throwable, "Failed to load home stats overview")
                _homeOverview.value = null
            }
        }
    }

    private fun refreshRange(
        range: StatsTimeRange,
        showLoading: Boolean = true,
        updateWeeklyOverview: Boolean = false,
        refreshHomeOverviewAfter: Boolean = false
    ) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true, isRefreshing = false, selectedRange = range) }
            } else {
                _uiState.update { it.copy(isRefreshing = true, selectedRange = range) }
            }
            val summary = runCatching {
                withContext(Dispatchers.IO) {
                    val songs = loadSongs()
                    playbackStatsRepository.loadSummary(range, songs)
                }
            }
            summary.getOrNull()?.let { loaded ->
                if (updateWeeklyOverview) {
                    _weeklyOverview.value = loaded
                }
            }
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    isRefreshing = false,
                    summary = summary.getOrNull(),
                    selectedRange = range
                )
            }
            summary.exceptionOrNull()?.let { Timber.e(it, "Failed to load stats for range %s", range) }
            if (refreshHomeOverviewAfter) {
                refreshHomeOverview(
                    weekSummary = summary.getOrNull()?.takeIf { range == StatsTimeRange.WEEK }
                )
            }
        }
    }

    private fun observeStatsRefreshFlow() {
        viewModelScope.launch {
            playbackStatsRepository.refreshFlow
                .drop(1)
                .collectLatest {
                    val selectedRange = _uiState.value.selectedRange
                    // One WEEK summary per refresh, shared with the Home overview.
                    refreshRange(
                        range = selectedRange,
                        showLoading = false,
                        updateWeeklyOverview = selectedRange == StatsTimeRange.WEEK,
                        refreshHomeOverviewAfter = selectedRange == StatsTimeRange.WEEK
                    )
                    if (selectedRange != StatsTimeRange.WEEK) {
                        refreshWeeklyOverview(refreshHomeOverviewAfter = true)
                    }
                }
        }
    }

    fun requestStatsRefresh() {
        playbackStatsRepository.requestRefresh()
    }

    fun forceRegenerateStats() {
        cachedSongs = null
        playbackStatsRepository.requestRefresh()
    }

    private suspend fun loadSongs(): List<Song> {
        cachedSongs?.let { existing ->
            if (existing.isNotEmpty()) return existing
        }
        return songsLoadMutex.withLock {
            cachedSongs?.takeIf { it.isNotEmpty() }
                ?: musicRepository.getAllSongsOnce().also { cachedSongs = it }
        }
    }

    private fun PlaybackStatsSummary.hasListeningActivity(): Boolean {
        return totalDurationMs > 0L ||
            totalPlayCount > 0 ||
            uniqueSongs > 0 ||
            activeDays > 0 ||
            totalSessions > 0
    }

    private companion object {
        val HomeOverviewRanges = listOf(
            StatsTimeRange.WEEK,
            StatsTimeRange.MONTH,
            StatsTimeRange.YEAR,
            StatsTimeRange.ALL
        )
    }
}

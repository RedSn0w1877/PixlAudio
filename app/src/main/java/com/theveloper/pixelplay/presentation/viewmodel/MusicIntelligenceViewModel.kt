package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.recommendation.MusicIntelligenceState
import com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.worker.AiWorkerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MusicIntelligenceViewModel @Inject constructor(
    private val taste: MusicTasteRepository,
    private val mixes: DailyMixManager,
    private val music: MusicRepository,
    private val workerManager: AiWorkerManager,
    private val taisAiEngine: com.theveloper.pixelplay.data.tais.TaisAiEngine
) : ViewModel() {
    val state = taste.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicIntelligenceState())
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    val modelStatus = taisAiEngine.runtimeStatus

    fun benchmarkInstrumentalModel() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { taisAiEngine.benchmarkStemModel() }
            finally { _busy.value = false }
        }
    }

    fun setLearning(enabled: Boolean) = viewModelScope.launch { taste.setLearning(enabled) }
    fun setDiscovery(enabled: Boolean) = viewModelScope.launch { taste.setDiscovery(enabled) }
    fun setExploration(value: Float) = viewModelScope.launch { taste.setExploration(value) }
    fun resetLearning() = viewModelScope.launch { taste.resetLearning() }
    fun refreshDiscovery() {
        workerManager.enqueueDailyMixRefresh()
        _message.value = "Refresh queued. It runs when network and battery allow."
    }

    /** Uses real library/history inputs; never changes playback or queues a song. */
    fun preview() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val songs = music.getAllSongsOnce()
                val start = android.os.SystemClock.elapsedRealtime()
                val picks = mixes.personalizedPicks(songs, music.getFavoriteSongIdsFlow().first(), 30)
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                taste.saveReport(
                    "Local preview · $elapsed ms · ${songs.size} candidates\n" +
                        "${picks.size} picks · ${picks.map { it.song.artist }.distinct().size} artists · ${picks.count { it.unheard }} unheard\n\n" +
                        picks.take(15).joinToString("\n") { "${it.song.title} — ${it.reason}" }
                )
                _message.value = "Preview complete. Playback is unchanged."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _message.value = "Couldn't load recommendations. Try again after your library finishes loading." }
            finally { _busy.value = false }
        }
    }
}

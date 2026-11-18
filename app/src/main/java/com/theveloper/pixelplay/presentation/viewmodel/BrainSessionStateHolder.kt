package com.theveloper.pixelplay.presentation.viewmodel

import androidx.media3.common.MediaItem
import com.theveloper.pixelplay.data.brain.BrainExtras
import com.theveloper.pixelplay.data.brain.BrainSwitch
import com.theveloper.pixelplay.data.brain.EngineTrackSaver
import com.theveloper.pixelplay.data.brain.PlayableStore
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.service.player.AutoplayController
import com.theveloper.pixelplay.data.service.player.AutoplayUiState
import com.theveloper.pixelplay.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * What the UI needs to know about the music brain's part of the current session
 * (docs/brain-spec.md, 8.4): which queue items the engine owns (they sit under the Autoplay line),
 * the Autoplay switch, and the songs of tracks that are not in the library.
 *
 * Renders state only. Nothing here ranks, picks or fetches music.
 */
@Singleton
class BrainSessionStateHolder @Inject constructor(
    autoplayController: AutoplayController,
    private val brainSwitch: BrainSwitch,
    private val playableStore: PlayableStore,
    private val engineTrackSaver: EngineTrackSaver,
    @AppScope private val appScope: CoroutineScope,
) {
    /** The Autoplay line of the queue. The switch comes from the setting, so it is right before the service runs. */
    val autoplayUiState: StateFlow<AutoplayUiState> =
        combine(brainSwitch.autoplayEnabledFlow, autoplayController.uiState) { enabled, state ->
            state.copy(enabled = enabled)
        }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000L), AutoplayUiState())

    private val _engineQueueIds = MutableStateFlow<Set<String>>(emptySet())

    /** Media ids of the queue items the engine put there. */
    val engineQueueIds: StateFlow<Set<String>> = _engineQueueIds.asStateFlow()

    /** Called when the queue's timeline changed, with its items; safe off the main thread. */
    fun onQueueItems(mediaItems: List<MediaItem>) {
        var ids: HashSet<String>? = null
        for (item in mediaItems) {
            if (BrainExtras.isEngineOwned(item.mediaMetadata.extras)) {
                (ids ?: HashSet<String>().also { ids = it }).add(item.mediaId)
            }
        }
        val next: Set<String> = ids ?: emptySet()
        if (next != _engineQueueIds.value) _engineQueueIds.value = next
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        appScope.launch { brainSwitch.setAutoplayEnabled(enabled) }
    }

    /**
     * [found] plus the songs of the ids in [requestedIds] that the library does not have but the brain
     * played: Recently Played resolves a track from outside the library through `playable`.
     */
    suspend fun withEngineSongs(requestedIds: List<String>, found: List<Song>): List<Song> {
        if (requestedIds.size <= found.size) return found
        val known = found.mapTo(HashSet(found.size * 2)) { it.id }
        val missing = requestedIds.filter { it !in known }
        if (missing.isEmpty()) return found
        return try {
            val extra = playableStore.songsFor(missing)
            if (extra.isEmpty()) found else found + extra
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: resolving engine songs failed")
            found
        }
    }

    /** Like, "Add to playlist" and Save make a track from outside the library a library track for good. */
    suspend fun ensureSaved(songIds: Collection<String>): Int {
        engineTrackSaver.onAddedToPlaylist(songIds)
        return engineTrackSaver.saveAll(songIds)
    }
}

package com.theveloper.pixelplay.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import com.theveloper.pixelplay.data.brain.BrainExtras
import com.theveloper.pixelplay.data.brain.BrainSwitch
import com.theveloper.pixelplay.data.brain.EngineTrackSaver
import com.theveloper.pixelplay.data.brain.PlayableStore
import com.theveloper.pixelplay.data.brain.StationStore
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.service.player.AutoplayController
import com.theveloper.pixelplay.data.service.player.AutoplayUiState
import com.theveloper.pixelplay.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
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

/** The reason an engine pick is in the queue (8.8): a `WhyLine` key name and the name it is about. */
@Immutable
data class QueueWhy(val key: String, val arg: String)

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
    private val stationStore: StationStore,
    /** "Start station": the seed and the hand-over to the player. */
    val stations: StationStateHolder,
    /** "Not for me", its bar, and the two sheets that explain picks. */
    val actions: BrainActionsStateHolder,
    @AppScope private val appScope: CoroutineScope,
) {
    /** The Autoplay line of the queue. The switch comes from the setting, so it is right before the service runs. */
    val autoplayUiState: StateFlow<AutoplayUiState> =
        combine(brainSwitch.autoplayEnabledFlow, autoplayController.uiState, stationStore.novelty) { enabled, state, novelty ->
            state.copy(enabled = enabled, novelty = novelty)
        }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000L), AutoplayUiState())

    private val _engineQueueIds = MutableStateFlow<Set<String>>(emptySet())

    /** Media ids of the queue items the engine put there. */
    val engineQueueIds: StateFlow<Set<String>> = _engineQueueIds.asStateFlow()

    private val _queueWhy = MutableStateFlow<ImmutableMap<String, QueueWhy>>(persistentMapOf())

    /** The reason line of every queue item that has one, by media id (8.8). */
    val queueWhy: StateFlow<ImmutableMap<String, QueueWhy>> = _queueWhy.asStateFlow()

    /** Called when the queue's timeline changed, with its items; safe off the main thread. */
    fun onQueueItems(mediaItems: List<MediaItem>) {
        var ids: HashSet<String>? = null
        var why: HashMap<String, QueueWhy>? = null
        for (item in mediaItems) {
            val extras = item.mediaMetadata.extras ?: continue
            if (BrainExtras.isEngineOwned(extras)) {
                (ids ?: HashSet<String>().also { ids = it }).add(item.mediaId)
            }
            val key = extras.getString(BrainExtras.WHY_KEY)
            if (!key.isNullOrEmpty()) {
                (why ?: HashMap<String, QueueWhy>().also { why = it })[item.mediaId] =
                    QueueWhy(key, extras.getString(BrainExtras.WHY_ARG).orEmpty())
            }
        }
        val next: Set<String> = ids ?: emptySet()
        if (next != _engineQueueIds.value) _engineQueueIds.value = next
        val nextWhy: Map<String, QueueWhy> = why ?: emptyMap()
        if (nextWhy != _queueWhy.value) _queueWhy.value = nextWhy.toImmutableMap()
    }

    /** The Familiar / Blend / Discover switch (`Novelty.*`). */
    fun setNovelty(level: Int) = stationStore.setNovelty(level)

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

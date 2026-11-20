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
import com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics
import timber.log.Timber

/** The reason an engine pick is in the queue (8.8): a `WhyLine` key name and the name it is about. */
@Immutable
data class QueueWhy(
    val key: String,
    val arg: String,
    /** The served feature vector of the pick (hex), "" when it has none: "Why this song" reads its top factors from it. */
    val servedX: String = "",
    /** What ranked the pick (`BrainEventRecorder.MODEL_*`), -1 when not recorded. */
    val model: Int = -1,
)

/**
 * What the UI needs to know about the music brain's part of the current session
 * (docs/brain-spec.md, 8.4): which queue items the engine owns (they sit under the Autoplay line),
 * the Autoplay switch, and the songs of tracks that are not in the library.
 *
 * Renders state only. Nothing here ranks, picks or fetches music.
 */
@Singleton
class BrainSessionStateHolder @Inject constructor(
    private val autoplayController: AutoplayController,
    private val privateSession: com.theveloper.pixelplay.data.brain.PrivateSession,
    private val brainSwitch: BrainSwitch,
    private val playableStore: PlayableStore,
    private val engineTrackSaver: EngineTrackSaver,
    /** Which queue songs have a recorded play, for the set notes. */
    val setNoteHistory: com.theveloper.pixelplay.data.brain.SetNoteHistory,
    private val eventExport: com.theveloper.pixelplay.data.brain.BrainEventExport,
    private val stationStore: StationStore,
    /** "Start station": the seed and the hand-over to the player. */
    val stations: StationStateHolder,
    /** Genre and mood stations and the Search tiles that start them (8.3, 8.10). */
    val genreStations: com.theveloper.pixelplay.data.brain.GenreStations,
    /** "Not for me", its bar, and the two sheets that explain picks. */
    val actions: BrainActionsStateHolder,
    castStateHolder: CastStateHolder,
    @AppScope private val appScope: CoroutineScope,
) {
    /**
     * The Autoplay line of the queue. The switch comes from the setting, so it is right before the
     * service runs. While a Cast session is up the line says picks are off there (14.6).
     */
    /** True when the brain engine (not the legacy one) is switched on: a switch read, no database. */
    suspend fun brainEngineOn(): Boolean = brainSwitch.engine() == com.theveloper.pixelplay.data.brain.BrainEngineChoice.BRAIN

    val autoplayUiState: StateFlow<AutoplayUiState> =
        combine(
            brainSwitch.autoplayEnabledFlow,
            autoplayController.uiState,
            stationStore.novelty,
            castStateHolder.castSession,
        ) { enabled, state, novelty, cast ->
            state.copy(enabled = enabled, novelty = novelty, offWhileCasting = cast != null)
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
                    QueueWhy(
                        key, extras.getString(BrainExtras.WHY_ARG).orEmpty(), extras.getString(BrainExtras.SERVED_X).orEmpty(),
                        extras.getInt(BrainExtras.MODEL, -1),
                    )
            }
        }
        val next: Set<String> = ids ?: emptySet()
        if (next != _engineQueueIds.value) _engineQueueIds.value = next
        val nextWhy: Map<String, QueueWhy> = why ?: emptyMap()
        if (nextWhy != _queueWhy.value) _queueWhy.value = nextWhy.toImmutableMap()
    }

    /** "Refresh picks" on the Autoplay line (8.4). Main thread. */
    fun refreshPicks() = autoplayController.refreshPicks()

    /** Private session (8.5): while it is on, plays are logged as excluded from the picks. Off at every app start. */
    val privateSessionActive: StateFlow<Boolean> get() = privateSession.active

    fun setPrivateSession(on: Boolean) = privateSession.set(on)

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
            val startedNanos = System.nanoTime()
            val extra = playableStore.songsFor(missing)
            PerformanceMetrics.recordTiming(TIMING_RECENT_LOOKUP, (System.nanoTime() - startedNanos) / 1_000_000)
            if (extra.isEmpty()) found else found + extra
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: resolving engine songs failed")
            found
        }
    }

    /**
     * How long a lookup in `brain.db` still has to wait: Home asks for Recently Played as the app
     * starts, and the brain's database is not opened in the app's first moments (principle 6, "nothing
     * at app start"). 0 once the app has been up for [STARTUP_QUIET_MS].
     */
    fun engineLookupWaitMs(): Long =
        (STARTUP_QUIET_MS - (System.nanoTime() - createdAtNanos) / 1_000_000).coerceAtLeast(0L)

    private val createdAtNanos = System.nanoTime()

    /** Like, "Add to playlist" and Save make a track from outside the library a library track for good. */
    suspend fun ensureSaved(songIds: Collection<String>): Int {
        engineTrackSaver.onAddedToPlaylist(songIds)
        return engineTrackSaver.saveAll(songIds)
    }

    /** The Save button of a mix row: imports the tracks for good, through the same importer Like uses. */
    fun saveEngineSongs(songIds: Collection<String>) {
        appScope.launch { engineTrackSaver.saveAll(songIds) }
    }

    /** Developer mode: writes the play log to a file for the evaluation harness; [onDone] gets its path, or null. */
    fun exportPlayEvents(onDone: (String?) -> Unit) {
        appScope.launch {
            val file = eventExport.export()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(file?.absolutePath) }
        }
    }

    private companion object {
        /** The first moments of the app, in which `brain.db` stays closed. */
        const val STARTUP_QUIET_MS = 4_000L
        const val TIMING_RECENT_LOOKUP = "brain_recent_lookup"
    }
}

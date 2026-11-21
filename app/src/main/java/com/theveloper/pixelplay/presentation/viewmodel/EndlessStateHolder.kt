package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.brain.HostNoteFacts
import com.theveloper.pixelplay.data.brain.StationStore
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.SetNoteLines
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * What the screens need to know about Endless Listening besides the station itself ([StationStateHolder] starts it): whether its
 * introduction was shown, the host's switches, and whether it is the queue's station right now. Reads the preferences the app already has open;
 * nothing here starts at app start.
 */
@Singleton
class EndlessStateHolder @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val stations: StationStore,
    private val hostNoteFacts: HostNoteFacts,
    @AppScope private val scope: CoroutineScope,
) {
    /** True while the queue is the Endless Listening station. */
    val active: StateFlow<Boolean> = stations.current
        .map { it?.isEndless == true }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** The host's short notes about the songs coming up (on by default). */
    val hostNotesEnabled: StateFlow<Boolean> = preferences.endlessHostNotesFlow
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    /** True when the introduction was shown before. One small preference read; a failed read counts as not seen. */
    suspend fun isIntroSeen(): Boolean = try {
        preferences.endlessIntroSeenFlow.first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "endless: reading the intro flag failed")
        false
    }

    private val _hostSheetOpen = MutableStateFlow(false)

    /** "Ask the host" was tapped (queue header, player): the host sheet is open. One sheet for the whole app, drawn with the other brain sheets. */
    val hostSheetOpen: StateFlow<Boolean> = _hostSheetOpen.asStateFlow()

    fun openHostSheet() {
        _hostSheetOpen.value = true
    }

    fun closeHostSheet() {
        _hostSheetOpen.value = false
    }

    /**
     * What the host may say about [window] (the current song and the next few): the island name of each library song, the engine's reason key of
     * each pick, and the atlas' description of a genre. Null while Endless Listening is not the queue's station: the other queues get the set
     * notes only. Everything is read from what the engine already stored; a fact that is not there is simply absent.
     */
    suspend fun hostFacts(window: List<Song>, why: Map<String, QueueWhy>): SetNoteLines.HostFacts? {
        if (stations.current.value?.isEndless != true || window.isEmpty()) return null
        val keys = HashMap<String, String>(window.size * 2)
        for (song in window) why[song.id]?.let { keys[song.id] = it.key }
        return SetNoteLines.HostFacts(
            islandOf = hostNoteFacts.islandNames(window),
            whyKeyOf = keys,
            genreFact = hostNoteFacts::genreFact,
        )
    }

    fun markIntroSeen() = write { preferences.setEndlessIntroSeen(true) }

    fun setHostNotes(enabled: Boolean) = write { preferences.setEndlessHostNotes(enabled) }

    private fun write(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "endless: saving a setting failed")
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

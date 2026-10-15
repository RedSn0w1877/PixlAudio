package com.theveloper.pixelplay.data.spotify

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Why a playlist was not imported. Shown verbatim on the Spotify dashboard. */
enum class SkipReason {
    /** Spotify's API only returns the songs of playlists the user owns or collaborates on. */
    NOT_OWNED,

    /** Spotify answered 403 for a playlist that should have been readable. */
    FORBIDDEN,

    /** Deleted or made unavailable on Spotify. */
    NOT_FOUND,

    /** Transient trouble (network, 5xx) that outlasted the retries; tried again next sync. */
    FAILED
}

data class SkippedPlaylist(
    val id: String,
    val name: String,
    val reason: SkipReason
) {
    /** Plain-language explanation for the user. */
    val explanation: String
        get() = when (reason) {
            SkipReason.NOT_OWNED -> "Spotify only lets this app read playlists you own or collaborate on."
            SkipReason.FORBIDDEN -> "Spotify refused to share this playlist's songs (HTTP 403)."
            SkipReason.NOT_FOUND -> "Spotify no longer has this playlist."
            SkipReason.FAILED -> "Couldn't be loaded after several tries. It will be retried on the next sync."
        }
}

/**
 * What the importer remembers between passes, kept out of Room on purpose (no schema change):
 *
 *  - the `snapshot_id` each playlist had when it was last imported completely, so an
 *    unchanged playlist costs no requests on later syncs and an interrupted pass resumes by
 *    simply skipping what already finished;
 *  - a cheap signature of Liked Songs (total + newest track) with the same purpose;
 *  - the playlists that were skipped, and why.
 *
 * Open so tests can replace the two storage hooks with memory.
 */
@Singleton
open class SpotifyImportStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private data class State(
        val snapshots: Map<String, String> = emptyMap(),
        val likedSignature: String? = null,
        val skipped: List<SkippedPlaylist> = emptyList()
    )

    private val gson = Gson()
    private val lock = Any()
    private var state: State = load()
    private val _skipped = MutableStateFlow(state.skipped)

    /** Playlists left out of the last pass(es), with the reason. */
    val skipped: StateFlow<List<SkippedPlaylist>> = _skipped.asStateFlow()

    protected open fun readRaw(): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null)

    protected open fun writeRaw(raw: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (raw == null) editor.remove(KEY_STATE) else editor.putString(KEY_STATE, raw)
        editor.apply()
    }

    private fun load(): State = runCatching {
        readRaw()?.let { gson.fromJson(it, State::class.java) }?.let { loaded ->
            // Gson bypasses Kotlin defaults: a field missing from older JSON arrives as null.
            @Suppress("USELESS_CAST")
            State(
                snapshots = (loaded.snapshots as Map<String, String>?) ?: emptyMap(),
                likedSignature = loaded.likedSignature,
                skipped = (loaded.skipped as List<SkippedPlaylist>?) ?: emptyList()
            )
        }
    }.onFailure { Timber.w(it, "Spotify import state unreadable; starting fresh") }
        .getOrNull() ?: State()

    private fun commit(next: State) {
        state = next
        _skipped.value = next.skipped
        runCatching { writeRaw(gson.toJson(next)) }
            .onFailure { Timber.w(it, "Could not persist Spotify import state") }
    }

    fun snapshotOf(playlistId: String): String? = synchronized(lock) { state.snapshots[playlistId] }

    fun markSynced(playlistId: String, snapshotId: String?) = synchronized(lock) {
        if (snapshotId.isNullOrBlank()) return@synchronized
        commit(state.copy(snapshots = state.snapshots + (playlistId to snapshotId)))
    }

    fun forget(playlistId: String) = synchronized(lock) {
        if (playlistId in state.snapshots) commit(state.copy(snapshots = state.snapshots - playlistId))
    }

    var likedSignature: String?
        get() = synchronized(lock) { state.likedSignature }
        set(value) = synchronized(lock) { commit(state.copy(likedSignature = value)) }

    /**
     * Replace the skipped entries of the playlists that were looked at in this pass
     * ([processedIds]) with [newlySkipped]; entries of playlists not reached (an interrupted
     * pass) are kept, and entries of playlists no longer in the library are dropped.
     */
    fun updateSkipped(
        processedIds: Set<String>,
        newlySkipped: List<SkippedPlaylist>,
        libraryIds: Set<String>
    ) = synchronized(lock) {
        val kept = state.skipped.filter { it.id !in processedIds && it.id in libraryIds }
        commit(state.copy(skipped = (kept + newlySkipped).distinctBy { it.id }))
    }

    fun clear() = synchronized(lock) { commit(State()) }

    private companion object {
        const val PREFS = "spotify_import_state"
        const val KEY_STATE = "state_v1"
    }
}

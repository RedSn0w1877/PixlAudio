package com.theveloper.pixelplay.data.stream

import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.utils.splitArtistsByDelimiters
import org.json.JSONObject

/**
 * Shared data class for bulk sync operations across cloud music repositories.
 */
data class BulkSyncResult(
    val playlistCount: Int,
    val syncedSongCount: Int,
    val failedPlaylistCount: Int,
    /**
     * False cuando la pasada se detuvo por presupuesto de tiempo con playlists todavía
     * pendientes. Quien la lanzó debe reencolarla: lo ya importado está guardado y la
     * siguiente ejecución sigue donde esta lo dejó.
     */
    val isComplete: Boolean = true,
    /** Set when Spotify rate-limited the pass for longer than is worth waiting inline: reschedule after this many seconds. */
    val retryAfterSeconds: Long? = null,
    /** The login is dead or lacks a scope: no point rescheduling until the user signs in again. */
    val needsReconnect: Boolean = false,
    /** Playlists left out (not owned / refused / gone); the dashboard lists them with reasons. */
    val skippedPlaylistCount: Int = 0
)

/**
 * Shared utility functions for cloud music repositories.
 */
object CloudMusicUtils {

    /** Parse a JSON string of key-value pairs into a Map (used for cookie persistence). */
    fun jsonToMap(json: String): Map<String, String> {
        val obj = JSONObject(json)
        val result = mutableMapOf<String, String>()
        for (key in obj.keys()) {
            result[key] = obj.optString(key, "")
        }
        return result
    }

    /** Split a raw artist string using the same conservative defaults as local library sync. */
    fun parseArtistNames(rawArtist: String): List<String> {
        if (rawArtist.isBlank()) return listOf("Unknown Artist")
        val parsed = rawArtist.splitArtistsByDelimiters(
            delimiters = UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
            wordDelimiters = UserPreferencesRepository.DEFAULT_ARTIST_WORD_DELIMITERS
        )
        return if (parsed.isEmpty()) listOf("Unknown Artist") else parsed
    }
}

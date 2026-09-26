package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsSourcePreference
import com.theveloper.pixelplay.data.model.Song

data class OnlineSyncedLyrics(val lyrics: Lyrics, val source: String)

interface LyricsRepository {
    /** Explicit resync searches verified catalogs before spending time on acoustic generation. */
    suspend fun findOnlineSyncedLyrics(song: Song): OnlineSyncedLyrics? = null
    /**
     * Persists catalog lyrics. Does nothing when the user synced this song themselves
     * ([isUserSynced]) unless [overrideUser] is set (the user chose "Replace").
     */
    suspend fun saveOnlineSyncedLyrics(song: Song, result: OnlineSyncedLyrics, overrideUser: Boolean = false) {
        error("Online lyric persistence is unavailable")
    }

    /**
     * Returns already-persisted lyrics without performing any network request.
     */
    suspend fun getStoredLyrics(song: Song): Pair<Lyrics, String>?

    /**
     * Get lyrics for a song with source preference support.
     * 
     * @param song The song to get lyrics for
     * @param sourcePreference The preferred order of sources to try (API, Embedded, Local)
     * @param forceRefresh If true, bypasses in-memory cache
     * @return Lyrics object or null if not found
     */
    suspend fun getLyrics(
        song: Song,
        sourcePreference: LyricsSourcePreference = LyricsSourcePreference.EMBEDDED_FIRST,
        forceRefresh: Boolean = false
    ): Lyrics?
    
    /**
     * Fetch lyrics from remote API and save to database.
     */
    suspend fun fetchFromRemote(song: Song): Result<Pair<Lyrics, String>>
    
    /**
     * Search for lyrics on remote API and return multiple results.
     */
    suspend fun searchRemote(song: Song): Result<Pair<String, List<LyricsSearchResult>>>
  
    /**
     * Search for lyrics on remote API using query title and artist, and return multiple results.
     */
    suspend fun searchRemoteByQuery(title: String, artist: String? = null): Result<Pair<String, List<LyricsSearchResult>>>
    
    /**
     * Update lyrics for a song in the database.
     */
    suspend fun updateLyrics(songId: Long, lyricsContent: String)

    /**
     * Persists lyrics for local and streaming songs, whose IDs need not be numeric.
     * [source] is stored with the Room row; the "sync it yourself" editor passes `"user"`.
     */
    suspend fun updateLyrics(song: Song, lyricsContent: String, source: String = "manual") {
        updateLyrics(requireNotNull(song.id.toLongOrNull()), lyricsContent)
    }

    /**
     * Removes every stored copy of a song's lyrics (Room row for numeric ids, the
     * `lyrics/{id}.json` store for any id, and the in-memory cache), so the next load refetches.
     */
    suspend fun resetLyrics(song: Song) {
        song.id.toLongOrNull()?.let { resetLyrics(it) }
    }

    /** True when the stored lyrics are a sync the user made themselves (source `"user"`). */
    suspend fun isUserSynced(song: Song): Boolean = false
    
    /**
     * Reset lyrics for a song (remove from database and cache).
     */
    suspend fun resetLyrics(songId: Long)
    
    /**
     * Reset all lyrics (clear database and cache).
     */
    suspend fun resetAllLyrics()
    
    /**
     * Clear in-memory cache only.
     */
    fun clearCache()

    /**
     * Scans local .lrc files for the provided songs and updates the database if found.
     * 
     * @param songs List of songs to scan for
     * @param onProgress Callback for progress updates (current, total)
     * @return Number of songs updated
     */
    suspend fun scanAndAssignLocalLrcFiles(
        songs: List<Song>,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): Int
}

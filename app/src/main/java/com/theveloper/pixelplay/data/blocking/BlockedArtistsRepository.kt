package com.theveloper.pixelplay.data.blocking

import com.theveloper.pixelplay.data.diagnostics.DiagnosticsLog
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The one place that knows which artists are blocked. A process-wide singleton so the playback
 * service, the recommendation code and the UI all read the same index. Persisted in the settings
 * DataStore (no database migration), which also makes it part of the settings backup.
 */
@Singleton
class BlockedArtistsRepository @Inject constructor(
    private val preferences: UserPreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val index: StateFlow<BlockedArtistIndex> = preferences.blockedArtistsFlow
        .map { BlockedArtistIndex(it) }
        .stateIn(scope, SharingStarted.Eagerly, BlockedArtistIndex.EMPTY)

    /** Current snapshot; safe from any thread. */
    val current: BlockedArtistIndex get() = index.value

    /** The song the owner explicitly chose to play although its artist is blocked ("Play anyway"). */
    @Volatile var allowedMediaId: String? = null
        private set

    fun allowOnce(songId: String) { allowedMediaId = songId }
    fun clearAllowance() { allowedMediaId = null }

    fun isBlocked(song: Song): Boolean = current.isBlocked(song)

    suspend fun block(artistId: Long, name: String) {
        if (name.isBlank() && artistId <= 0L) return
        preferences.blockArtist(BlockedArtist(artistId, name.trim()))
        DiagnosticsLog.event("BlockedArtists", "blocked an artist")
    }

    suspend fun unblock(artistId: Long, name: String? = null) {
        preferences.unblockArtist(artistId, name)
        DiagnosticsLog.event("BlockedArtists", "unblocked an artist")
    }

    suspend fun unblockAll() {
        preferences.unblockAllArtists()
        DiagnosticsLog.event("BlockedArtists", "unblocked all artists")
    }
}

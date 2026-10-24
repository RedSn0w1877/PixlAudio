package com.theveloper.pixelplay.data.cloudstudio.auto

import com.theveloper.pixelplay.data.blocking.BlockedArtistsRepository
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.database.LocalPlaylistDao
import com.theveloper.pixelplay.data.database.PlaylistSongEntity
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What the runner needs to know about the library (a seam, so the runner is tested without a database). */
interface CloudAutoFacts {
    /** Every library song's facts. [pendingCanonicalIds] are the songs that already have a cloud job. Never on the main thread. */
    suspend fun gather(pendingCanonicalIds: Set<String>, nowMs: Long): List<AutoCandidateFacts>

    /** The song's audio is already on this phone, so the cloud can be given it without any download. */
    suspend fun isAudioCached(facts: AutoCandidateFacts): Boolean
}

/**
 * Gathers the facts [CloudAutoPrepare] ranks, all from this phone: the library, play counts and last plays
 * ([EngagementDao]), finished plays and skips ([MusicTasteRepository], when learning is on), favorites, the date a song was
 * added, the playlists it sits in, its album, whether it already has an instrumental, and whether its artist is blocked.
 * Nothing here calls Spotify: album siblings are library songs only, and artist top-tracks are never used.
 *
 * Streamed songs appear under two ids (a `spotify_` alias and the unified library row); every join goes through
 * [TaisInstrumentalIndex.canonicalSongId], which is what Sing's instrumental lookup uses too.
 */
@Singleton
class CloudAutoFactsGatherer @Inject constructor(
    private val musicRepository: MusicRepository,
    private val engagementDao: EngagementDao,
    private val musicTaste: MusicTasteRepository,
    private val localPlaylistDao: LocalPlaylistDao,
    private val instrumentalIndex: TaisInstrumentalIndex,
    private val blockedArtists: BlockedArtistsRepository,
    private val audioCacheManager: AudioCacheManager,
) : CloudAutoFacts {

    override suspend fun gather(pendingCanonicalIds: Set<String>, nowMs: Long): List<AutoCandidateFacts> =
        withContext(Dispatchers.IO) {
            val library = musicRepository.getAllSongsOnce().distinctBy { TaisInstrumentalIndex.canonicalSongId(it.id) }
            if (library.isEmpty()) return@withContext emptyList()
            val engagement = mergedEngagement()
            val learning = musicTaste.state.first().learningEnabled
            val learned = if (learning) mergedSignals() else emptyMap()
            instrumentalIndex.refresh() // a fresh background process starts with an empty index
            val instrumentals = instrumentalIndex.instrumentalizedSongIds.value
            val blocked = blockedArtists.current
            val dateAdded = library.associate { TaisInstrumentalIndex.canonicalSongId(it.id) to normalizeEpochMs(it.dateAdded) }
            // The five songs played last, newest first: whose neighbours in a playlist are the likely next ones.
            val recent = engagement.entries.filter { it.value.lastPlayedMs > 0 }
                .sortedByDescending { it.value.lastPlayedMs }.take(CloudAutoPrepare.NEIGHBOUR_SEEDS).map { it.key }
            val boosts = CloudAutoPrepare.playlistNeighbourBoosts(recent, playlists(dateAdded))
            library.map { song ->
                val canonical = TaisInstrumentalIndex.canonicalSongId(song.id)
                val plays = engagement[canonical]
                val signal = learned[canonical]
                AutoCandidateFacts(
                    songId = song.id,
                    spotifyId = song.spotifyId,
                    canonicalId = canonical,
                    title = song.title,
                    artist = song.displayArtist,
                    albumKey = albumKey(song),
                    durationMs = song.duration,
                    isStreamed = song.spotifyId != null,
                    favorite = song.isFavorite,
                    playCount = plays?.plays ?: 0,
                    lastPlayedMs = maxOf(plays?.lastPlayedMs ?: 0L, signal?.lastPlayedMs ?: 0L),
                    dateAddedMs = dateAdded[canonical] ?: 0,
                    // No learned signals (learning is off, or nothing was ever recorded for the song): the completion-rate
                    // and skip-rate terms are 0, the same on the iOS ranker.
                    signal = signal?.signal,
                    neighbourBoost = boosts[canonical] ?: 0.0,
                    hasInstrumental = canonical in instrumentals || song.id in instrumentals,
                    hasPendingJob = canonical in pendingCanonicalIds,
                    blockedArtist = blocked.isBlocked(song),
                )
            }
        }

    override suspend fun isAudioCached(facts: AutoCandidateFacts): Boolean {
        val spotifyId = facts.spotifyId ?: return true // a local file is on the phone by definition
        return audioCacheManager.getPlayableFile(spotifyId) != null
    }

    private data class Plays(val plays: Int, val lastPlayedMs: Long)

    private suspend fun mergedEngagement(): Map<String, Plays> {
        val merged = HashMap<String, Plays>()
        for (row in engagementDao.getAllEngagements()) {
            val id = TaisInstrumentalIndex.canonicalSongId(row.songId)
            val before = merged[id]
            merged[id] = Plays(
                (before?.plays ?: 0) + row.playCount.coerceAtLeast(0),
                maxOf(before?.lastPlayedMs ?: 0L, normalizeEpochMs(row.lastPlayedTimestamp)),
            )
        }
        // Plays imported from Spotify / YouTube Music (weighted, time-decayed) rank songs ahead the same way.
        for ((songId, imported) in musicTaste.importedHistory()) {
            val id = TaisInstrumentalIndex.canonicalSongId(songId)
            val before = merged[id]
            merged[id] = Plays(
                (before?.plays ?: 0) + imported.plays.coerceAtLeast(0),
                maxOf(before?.lastPlayedMs ?: 0L, normalizeEpochMs(imported.lastPlayedMs)),
            )
        }
        return merged
    }

    /** What learning knows about a song: its signal, and when the listening history last saw it. */
    private data class Learned(val signal: AutoSignal, val lastPlayedMs: Long)

    private suspend fun mergedSignals(): Map<String, Learned> {
        val merged = HashMap<String, Learned>()
        for ((songId, signal) in musicTaste.signals()) {
            val id = TaisInstrumentalIndex.canonicalSongId(songId)
            val before = merged[id]?.signal ?: AutoSignal()
            merged[id] = Learned(
                AutoSignal(
                    before.sessions + signal.sessions.coerceAtLeast(0),
                    before.completions + signal.completions.coerceAtLeast(0),
                    before.earlySkips + signal.earlySkips.coerceAtLeast(0),
                ),
                maxOf(merged[id]?.lastPlayedMs ?: 0L, normalizeEpochMs(signal.lastPlayedMs)),
            )
        }
        return merged
    }

    /**
     * Every playlist as song ids in play order. Local playlists have a position; a Spotify playlist's mirrored copy
     * doesn't carry Spotify's order, so it is ordered by the date each song was added (oldest first), the nearest
     * thing to it.
     */
    private suspend fun playlists(dateAdded: Map<String, Long>): List<List<String>> =
        localPlaylistDao.observePlaylistsWithSongs().first().map { withSongs ->
            val byDate = withSongs.playlist.source == SpotifyRepository.SPOTIFY_PLAYLIST_SOURCE
            val order: Comparator<PlaylistSongEntity> =
                if (byDate) {
                    compareBy<PlaylistSongEntity>(
                        { dateAdded[TaisInstrumentalIndex.canonicalSongId(it.songId)] ?: Long.MAX_VALUE },
                        { it.sortOrder },
                    )
                } else {
                    compareBy<PlaylistSongEntity> { it.sortOrder }
                }
            withSongs.songs.sortedWith(order).map { TaisInstrumentalIndex.canonicalSongId(it.songId) }
        }

    companion object {
        /** MediaStore dates are seconds, Spotify's and Room's are milliseconds: anything below this is seconds. */
        private const val MILLIS_THRESHOLD = 100_000_000_000L

        fun normalizeEpochMs(value: Long): Long = when {
            value <= 0 -> 0
            value < MILLIS_THRESHOLD -> value * 1000
            else -> value
        }

        /** Songs on the same album share a key: the album id when there is a real one, else the album's name and artist. */
        fun albumKey(song: Song): String? {
            if (song.albumId != 0L && song.albumId != -1L) return "id:${song.albumId}"
            val name = song.album.trim().lowercase().takeIf { it.isNotEmpty() && it != "unknown album" } ?: return null
            return "name:$name|${(song.albumArtist ?: song.artist).trim().lowercase()}"
        }
    }
}

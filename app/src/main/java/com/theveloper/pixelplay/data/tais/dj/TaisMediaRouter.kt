package com.theveloper.pixelplay.data.tais.dj

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyAuthManager
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.presentation.viewmodel.ConnectivityStateHolder
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

sealed interface DjRouteResult {
    data class Offline(val songs: List<Song>) : DjRouteResult
    data class Online(val tracks: List<SpotifyTrack>) : DjRouteResult
    data object NoResults : DjRouteResult
}

/** Resolves music requests against the local library, Spotify, and YouTube Music/video shelves. */
@Singleton
class TaisMediaRouter @Inject constructor(
    private val musicRepository: MusicRepository,
    private val spotifyRepository: SpotifyRepository,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val innerTubeClient: InnerTubeClient,
    private val audioCacheManager: com.theveloper.pixelplay.data.cache.AudioCacheManager,
    private val connectivityStateHolder: ConnectivityStateHolder
) {
    suspend fun route(intent: DjIntent): DjRouteResult {
        val local = routeOffline(intent)
        // An exact title/artist already in the library starts without a network round trip.
        if (intent.searchQuery.isNotBlank() && intent.genres.isEmpty() && intent.moods.isEmpty() && local is DjRouteResult.Offline) return local
        if (!connectivityStateHolder.isOnline.value) return local
        if (spotifyAuthManager.hasClientId() && spotifyAuthManager.isLoggedIn.value) {
            safely { withTimeoutOrNull(10_000L) { routeOnline(intent) } }?.let { return it }
        }
        safely {
            withTimeoutOrNull(15_000L) {
                val candidates = innerTubeClient.searchMusic(buildQuery(intent), limit = 12)
                if (candidates.isEmpty()) return@withTimeoutOrNull null
                spotifyRepository.importYouTubeMusicTracks(candidates)
                val ids = candidates.map { SpotifyRepository.unifiedSongId(spotifyRepository.youTubeMusicSyntheticId(it.videoId)).toString() }
                musicRepository.getSongsByIds(ids).first().takeIf { it.isNotEmpty() }
                    ?.let { DjRouteResult.Offline(it) }
            }
        }?.let { return it }
        return local
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Timber.w(e, "DJ catalog unavailable; keeping local results")
        null
    }

    private suspend fun routeOffline(intent: DjIntent): DjRouteResult {
        val query = intent.searchQuery
        val titleMatches = if (query.isBlank()) emptyList() else musicRepository.searchSongs(query).first()
        val genreMatches = intent.genres.flatMap { musicRepository.getMusicByGenre(it).first() }.distinctBy { it.id }
        val songs = when {
            query.isNotBlank() && intent.genres.isNotEmpty() -> genreMatches.filter { song ->
                query.split(' ').filter { it.isNotBlank() }.all { token ->
                    song.artist.contains(token, true) || song.title.contains(token, true)
                }
            }
            query.isNotBlank() -> titleMatches
            genreMatches.isNotEmpty() -> genreMatches
            else -> emptyList()
        }
        val playable = if (connectivityStateHolder.isOnline.value) songs else songs.filter { song ->
            song.spotifyId?.let { audioCacheManager.getPlayableFile(it) != null } ?: true
        }
        return playable.takeIf { it.isNotEmpty() }?.let { DjRouteResult.Offline(it.take(50)) }
            ?: DjRouteResult.NoResults
    }

    private fun buildQuery(intent: DjIntent): String =
        (intent.queryTerms + intent.searchQuery).filter { it.isNotBlank() }.distinct().joinToString(" ")

    private suspend fun routeOnline(intent: DjIntent): DjRouteResult? {
        val query = buildQuery(intent)
        if (query.isBlank()) return null
        val results = spotifyRepository.searchCatalog(query = query, types = "track", limit = 12)
        return results.tracks.takeIf { it.isNotEmpty() }?.let { DjRouteResult.Online(it) }
    }
}

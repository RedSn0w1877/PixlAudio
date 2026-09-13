package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.YouTubeSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/** Bounded catalog discovery, shared by both mixes; audio URLs are resolved only on playback. */
@Singleton
class MusicDiscoveryRepository @Inject constructor(
    private val innerTubeClient: InnerTubeClient,
    private val spotifyRepository: SpotifyRepository,
    private val musicRepository: MusicRepository,
    private val tasteRepository: MusicTasteRepository
) {
    private val lock = Mutex()
    private var cachedIds: List<String> = emptyList()
    private var cachedAt = 0L

    suspend fun augment(baseMix: List<Song>, library: List<Song>, maxOnlineSongs: Int = 6): List<Song> = lock.withLock {
        if (baseMix.isEmpty() || maxOnlineSongs <= 0 || !tasteRepository.state.first().discoveryEnabled) return@withLock baseMix
        val now = System.currentTimeMillis()
        val cached = if (now - cachedAt < 6 * 60 * 60_000L && cachedIds.isNotEmpty()) {
            musicRepository.getSongsByIds(cachedIds).first()
        } else emptyList()
        val discovered = cached.ifEmpty {
            try {
                withTimeoutOrNull(25_000) {
                    val knownRecordings = library.mapTo(hashSetOf(), MusicRecommendationEngine::recordingKey)
                    val knownVideos = library.mapNotNullTo(hashSetOf()) { it.spotifyId }
                    val artists = baseMix.map { it.artist.trim() }
                        .filter { it.isNotBlank() && !it.equals("Unknown Artist", true) }.distinct().take(2)
                    val genre = baseMix.mapNotNull { it.genre?.takeUnless { g -> g.isBlank() || g.equals("unknown", true) } }
                        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    val queries = (artists.map { "$it songs" } + listOfNotNull(genre?.let { "$it new music" }))
                        .distinct().take(3)
                    if (queries.isEmpty()) return@withTimeoutOrNull emptyList<Song>()
                    val results = coroutineScope {
                        queries.map { query -> async {
                            try { withTimeoutOrNull(9_000) { innerTubeClient.searchMusic(query, 12, includeVideos = true) }.orEmpty() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { Timber.d(e, "Discovery query unavailable"); emptyList() }
                        } }.awaitAll().flatten()
                    }.distinctBy { it.videoId }.filter { result ->
                        val seconds = result.durationSeconds ?: 0
                        seconds in 60..900 && result.artist.isNotBlank() &&
                            spotifyRepository.youTubeMusicSyntheticId(result.videoId) !in knownVideos &&
                            recordingKey(result) !in knownRecordings && !UNSUITABLE.containsMatchIn(result.title)
                    }.distinctBy(::recordingKey)
                    // Round-robin query results instead of letting one artist consume all slots.
                    val selected = results.groupBy { it.artist.lowercase(java.util.Locale.ROOT) }.values
                        .flatMap { it.take(2) }.take(maxOnlineSongs)
                    if (selected.isEmpty()) return@withTimeoutOrNull emptyList<Song>()
                    spotifyRepository.importYouTubeMusicTracks(selected)
                    val ids = selected.map { SpotifyRepository.unifiedSongId(spotifyRepository.youTubeMusicSyntheticId(it.videoId)).toString() }
                    musicRepository.getSongsByIds(ids).first().also {
                        cachedIds = it.map(Song::id)
                        cachedAt = now
                    }
                }.orEmpty()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Timber.w(e, "Catalog discovery unavailable; retaining the saved mix"); emptyList() }
        }
        if (discovered.isEmpty()) return@withLock baseMix
        val used = baseMix.mapTo(hashSetOf(), MusicRecommendationEngine::recordingKey)
        val additions = discovered.filter { used.add(MusicRecommendationEngine.recordingKey(it)) }.take(maxOnlineSongs)
        if (additions.isEmpty()) return@withLock baseMix
        // Small libraries can grow to 30; larger mixes keep their established size. Reserve
        // discovery positions before filling with familiar songs so truncation cannot remove
        // the very songs we just imported (especially with a one- or two-song seed library).
        val targetSize = maxOf(baseMix.size, minOf(30, baseMix.size + additions.size))
        val includedAdditions = additions.take(targetSize)
        val discoverySlots = includedAdditions.mapIndexed { index, song ->
            ((index + 1) * targetSize / (includedAdditions.size + 1)) to song
        }.toMap()
        var baseIndex = 0
        List(targetSize) { slot -> discoverySlots[slot] ?: baseMix[baseIndex++] }
    }

    private fun recordingKey(result: YouTubeSearchResult): String = MusicRecommendationEngine.recordingKey(
        Song.emptySong().copy(id = result.videoId, title = result.title, artist = result.artist)
    )

    private companion object {
        val UNSUITABLE = Regex("\\b(podcast|interview|reaction|tutorial|full album|full concert|karaoke)\\b", RegexOption.IGNORE_CASE)
    }
}

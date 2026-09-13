package com.theveloper.pixelplay.data.recommendation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.AtomicFile
import com.google.gson.Gson
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.YouTubeSearchResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

@androidx.annotation.Keep
data class HomeCatalogEntry(
    val song: Song,
    val youtube: YouTubeSearchResult? = null,
    val spotify: SpotifyTrack? = null,
    val releaseDate: String? = null
)

@androidx.annotation.Keep
data class HomeCatalogSnapshot(
    val updatedAt: Long = 0,
    val discoveries: List<HomeCatalogEntry> = emptyList(),
    val releases: List<HomeCatalogEntry> = emptyList()
)

/** Metadata only until a user presses play. A six-hour cache survives restarts and offline use. */
@Singleton
class HomeCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val youtube: InnerTubeClient,
    private val spotify: SpotifyRepository,
    private val music: MusicRepository
) {
    private val lock = Mutex()
    private val gson = Gson()
    private val file = AtomicFile(File(context.filesDir, "home_catalog_v1.json"))
    @Volatile private var memory: HomeCatalogSnapshot? = null
    private var lastAttemptMs = 0L

    // Playback must not queue behind an in-flight network refresh of the same catalog.
    suspend fun cached(): HomeCatalogSnapshot = memory ?: lock.withLock { readCache() }

    suspend fun refresh(seeds: List<Song>, library: List<Song>, force: Boolean): HomeCatalogSnapshot = lock.withLock {
        val old = readCache()
        val now = System.currentTimeMillis()
        if (!isOnline() || seeds.isEmpty() || (!force && now - old.updatedAt in 0 until CACHE_MS) ||
            now - lastAttemptMs in 0 until RETRY_MS) return@withLock old
        lastAttemptMs = now
        val artists = seeds.map { it.artist.trim() }.filter {
            it.isNotBlank() && !it.equals("Unknown Artist", true)
        }.distinctBy { it.lowercase(Locale.ROOT) }.take(3)
        val known = library.mapTo(hashSetOf(), MusicRecommendationEngine::recordingKey)
        val genre = seeds.mapNotNull { it.genre?.takeUnless { value -> value.isBlank() || value.equals("unknown", true) } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val queries = (artists.take(2).map { "$it songs" } + listOfNotNull(genre?.let { "$it new music" }))
            .distinct().take(3)

        val result = withTimeoutOrNull(35_000) {
            coroutineScope {
                val discoveries = async {
                    coroutineScope {
                        queries.map { query -> async {
                            request { youtube.searchMusic(query, 14, includeVideos = true) }.orEmpty()
                        } }.awaitAll().flatten()
                    }.filter { (it.durationSeconds ?: 0) in 60..900 && it.artist.isNotBlank() &&
                        !UNSUITABLE.containsMatchIn(it.title) }
                        .map(::youtubeEntry)
                        .filter { MusicRecommendationEngine.recordingKey(it.song) !in known }
                        .distinctBy { MusicRecommendationEngine.recordingKey(it.song) }
                        .groupBy { MusicRecommendationEngine.artistKey(it.song) }.values
                        .flatMap { it.take(4) }.take(24)
                }
                val releases = async { withTimeoutOrNull(25_000) { recentReleases(artists) }.orEmpty() }
                HomeCatalogSnapshot(now, discoveries.await(), releases.await())
            }
        } ?: return@withLock old

        // A provider failure must never blank a previously useful shelf.
        val merged = result.copy(
            discoveries = result.discoveries.ifEmpty { old.discoveries },
            releases = result.releases.ifEmpty { old.releases }
        )
        // Empty responses are retried after a short backoff instead of cached for six hours.
        if (result.discoveries.isNotEmpty() || result.releases.isNotEmpty()) {
            memory = merged
            writeCache(merged)
        }
        memory ?: merged
    }

    suspend fun playableSongs(requested: List<Song>): List<Song> {
        val existing = music.getSongsByIds(requested.map { it.id }).first().associateBy { it.id }
        if (requested.all { it.id in existing }) return requested.mapNotNull { existing[it.id] }
        val catalog = cached().let { it.discoveries + it.releases }.associateBy { it.song.id }
        val missing = requested.filter { it.id !in existing }.mapNotNull { catalog[it.id] }
        // Import only the tapped shelf, through the same durable matcher/proxy path as Search.
        missing.mapNotNull { it.youtube }.takeIf { it.isNotEmpty() }?.let { spotify.importYouTubeMusicTracks(it) }
        missing.mapNotNull { it.spotify }.takeIf { it.isNotEmpty() }?.let { spotify.importTracks(it) }
        val playable = music.getSongsByIds(requested.map { it.id }).first().associateBy { it.id }
        return requested.mapNotNull { playable[it.id] }
    }

    private suspend fun recentReleases(preferredArtists: List<String>): List<HomeCatalogEntry> {
        if (!spotify.isLoggedIn.value) return emptyList()
        val preferred = preferredArtists.map { it.lowercase(Locale.ROOT) }.toSet()
        val artists = request { spotify.getMyTopArtists("short_term") }.orEmpty()
            .sortedByDescending { it.name?.lowercase(Locale.ROOT) in preferred }.take(2)
        return coroutineScope {
            artists.map { artist -> async {
                val id = artist.id ?: return@async emptyList<HomeCatalogEntry>()
                val album = request { spotify.getArtistAlbums(id) }.orEmpty().firstOrNull {
                    HomeRecommendationPlanner.isRecentRelease(it.releaseDate, LocalDate.now()) &&
                        it.albumGroup != "appears_on"
                } ?: return@async emptyList<HomeCatalogEntry>()
                request { spotify.getAlbumTracks(album.id!!) }.orEmpty().take(6).mapNotNull { track ->
                    spotifyEntry(track, album.releaseDate)
                }
            } }.awaitAll().flatten().distinctBy { MusicRecommendationEngine.recordingKey(it.song) }
        }
    }

    private fun youtubeEntry(track: YouTubeSearchResult): HomeCatalogEntry {
        val id = spotify.youTubeMusicSyntheticId(track.videoId)
        return HomeCatalogEntry(Song.emptySong().copy(
            id = SpotifyRepository.unifiedSongId(id).toString(), title = track.title,
            artist = track.artist, album = track.album.orEmpty(), duration = (track.durationSeconds ?: 0) * 1_000L,
            albumArtUriString = track.thumbnailUrl, spotifyId = id, contentUriString = "spotify://$id"
        ), youtube = track)
    }

    private fun spotifyEntry(track: SpotifyTrack, date: String?): HomeCatalogEntry? {
        val id = track.id?.takeIf { it.isNotBlank() } ?: return null
        val title = track.name?.takeIf { it.isNotBlank() } ?: return null
        val artist = track.artists?.firstOrNull()?.name ?: return null
        return HomeCatalogEntry(Song.emptySong().copy(
            id = SpotifyRepository.unifiedSongId(id).toString(), title = title, artist = artist,
            album = track.album?.name.orEmpty(), duration = track.durationMs ?: 0,
            albumArtUriString = track.album?.images?.firstOrNull()?.url,
            spotifyId = id, contentUriString = "spotify://$id"
        ), spotify = track, releaseDate = date)
    }

    private fun readCache(): HomeCatalogSnapshot {
        memory?.let { return it }
        return runCatching {
            if (file.baseFile.length() !in 1..MAX_CACHE_BYTES) return@runCatching HomeCatalogSnapshot()
            file.openRead().bufferedReader().use { gson.fromJson(it, HomeCatalogSnapshot::class.java) }
                ?.takeIf { it.discoveries.size <= 24 && it.releases.size <= 12 &&
                    (it.discoveries + it.releases).all { entry ->
                        entry.song.id.isNotBlank() && entry.song.title.isNotBlank() &&
                            (entry.youtube != null || entry.spotify != null)
                    }
                }
                ?: HomeCatalogSnapshot()
        }.getOrDefault(HomeCatalogSnapshot()).also { memory = it }
    }

    private fun writeCache(snapshot: HomeCatalogSnapshot) {
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(gson.toJson(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            Timber.d(e, "Home catalog cache unavailable")
        }
    }

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private suspend fun <T> request(block: suspend () -> T): T? = try {
        withTimeoutOrNull(12_000) { block() }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Timber.d(e, "Home catalog request unavailable"); null }

    private companion object {
        const val CACHE_MS = 6 * 60 * 60_000L
        const val RETRY_MS = 5 * 60_000L
        const val MAX_CACHE_BYTES = 2 * 1024 * 1024L
        val UNSUITABLE = Regex("\\b(podcast|interview|reaction|tutorial|full album|full concert|karaoke)\\b", RegexOption.IGNORE_CASE)
    }
}

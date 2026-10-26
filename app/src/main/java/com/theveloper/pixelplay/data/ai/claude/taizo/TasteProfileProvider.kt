package com.theveloper.pixelplay.data.ai.claude.taizo

import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.history.HistorySource
import com.theveloper.pixelplay.data.history.HistoryText
import com.theveloper.pixelplay.data.history.ImportedTasteMerge
import com.theveloper.pixelplay.data.history.ListeningHistoryDao
import com.theveloper.pixelplay.data.history.ListeningInsights
import com.theveloper.pixelplay.data.history.SeedKind
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.MusicRecommendationEngine
import com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Reads the stores behind `get_taste_profile` and the chat's taste digest into a [TasteProfileInput]
 * ([TasteProfileBuilder] turns that into the numbers Claude sees). Built once per period and reused
 * until a new play, an imported-history rebuild or a taste change makes it stale (or ten minutes pass),
 * so asking twice in a chat costs nothing and a fresh play shows up on the next question.
 */
@Singleton
class TasteProfileProvider @Inject constructor(
    private val insights: ListeningInsights,
    private val historyDao: ListeningHistoryDao,
    private val taste: MusicTasteRepository,
    private val stats: PlaybackStatsRepository,
    private val music: MusicRepository,
    private val engagementDao: EngagementDao,
    private val adjustments: TasteAdjustmentRepository,
) {
    private class Cached(val fingerprint: String, val builtAtMs: Long, val input: TasteProfileInput)

    private val lock = Mutex()
    private val cache = HashMap<TastePeriod, Cached>()

    /** The JSON `get_taste_profile` returns. */
    suspend fun profileJson(period: TastePeriod): JsonObject = TasteProfileBuilder.build(input(period))

    /** The short digest for the cached chat context, or null when nothing is known yet. */
    suspend fun digest(): String? {
        val input = runCatching { input(TastePeriod.SIX_MONTHS) }.getOrNull() ?: return null
        val recent = try {
            insights.recentPlays(TasteProfileBuilder.DIGEST_RECENT).map { "${it.title} - ${it.artist}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (input.appPlays + input.importedPlays == 0 && input.adjustments.isEmpty()) return null
        return TasteProfileBuilder.digest(input, recent)
    }

    private suspend fun input(period: TastePeriod): TasteProfileInput = lock.withLock {
        val now = System.currentTimeMillis()
        val fingerprint = "${stats.refreshFlow.value}|${runCatching { taste.importedTaste().builtAtMs }.getOrDefault(0L)}|${adjustments.version.value}"
        cache[period]?.let { if (it.fingerprint == fingerprint && now - it.builtAtMs < MAX_AGE_MS) return@withLock it.input }
        val built = gather(period, now)
        cache[period] = Cached(fingerprint, now, built)
        built
    }

    private suspend fun gather(period: TastePeriod, nowMs: Long): TasteProfileInput = withContext(Dispatchers.IO) {
        coroutineScope {
            val songsD = async { safely { music.getAllSongsOnce() }.orEmpty() }
            val artistsD = async { safely { insights.topArtists(period.insight, ARTIST_POOL) }.orEmpty() }
            val tracksD = async { safely { insights.topTracks(period.insight, TRACK_POOL) }.orEmpty() }
            val summaryD = async { safely { insights.listeningSummary() } }
            val seedsD = async { safely { historyDao.allSeeds() }.orEmpty() }
            val importedOutcomesD = async { safely { historyDao.artistOutcomes(OUTCOME_ARTISTS) }.orEmpty() }
            val importedTimesD = async {
                safely {
                    historyDao.playTimes(
                        listOf(HistorySource.SPOTIFY_API.key, HistorySource.SPOTIFY_EXPORT.key),
                        period.insight.sinceMs(nowMs),
                        MAX_TIMES,
                    )
                }.orEmpty()
            }
            val localEventsD = async { safely { stats.exportEventsForBackup() }.orEmpty() }
            val liveSignalsD = async { safely { taste.liveSignals() }.orEmpty() }
            val importedHistoryD = async { safely { taste.importedHistory() }.orEmpty() }
            val engagementD = async { safely { engagementDao.getAllEngagements() }.orEmpty() }
            val adjustmentsD = async { safely { adjustments.list(nowMs) }.orEmpty() }

            val songs = songsD.await()
            val summary = summaryD.await()
            val seeds = seedsD.await()
            val since = period.insight.sinceMs(nowMs)

            // Outcomes: live feedback by artist (through the library) plus imported rows by artist.
            val byId = HashMap<String, Song>(songs.size * 2)
            for (song in songs) {
                byId[song.id] = song
                song.spotifyId?.let { byId["spotify_$it"] = song }
            }
            val outcomes = HashMap<String, MutableOutcome>()
            for ((songId, signal) in liveSignalsD.await()) {
                val song = byId[songId] ?: byId[TaisInstrumentalIndex.canonicalSongId(songId)] ?: continue
                val name = song.primaryArtist.name.ifBlank { song.artist }
                val key = HistoryText.artistKey(name)
                if (key.isEmpty()) continue
                val entry = outcomes.getOrPut(key) { MutableOutcome(name) }
                entry.plays += signal.sessions
                entry.completions += signal.completions
                entry.skips += signal.earlySkips
            }
            for (row in importedOutcomesD.await()) {
                val entry = outcomes.getOrPut(row.artistKey) { MutableOutcome(row.name) }
                entry.plays += row.plays
                entry.completions += row.completed
                entry.skips += row.skipped
            }

            // Genre and era by weighted plays of library songs (this app's plays plus imported ones).
            val engagement = engagementD.await().associateBy { it.songId }
            val imported = importedHistoryD.await()
            val genreWeights = HashMap<String, Double>()
            val genreNames = HashMap<String, String>()
            val decadeWeights = HashMap<String, Double>()
            for (song in songs) {
                val own = engagement[song.id] ?: song.spotifyId?.let { engagement["spotify_$it"] }
                val local = own?.let { MusicRecommendationEngine.History(it.playCount, it.totalPlayDurationMs, it.lastPlayedTimestamp) }
                val plays = ImportedTasteMerge.history(local, imported[song.id]).plays.toDouble()
                if (plays <= 0.0) continue
                val genres = song.genre.orEmpty().split(',', ';', '/').map { it.trim() }
                    .filter { it.isNotEmpty() && !it.equals("unknown", true) && !it.equals("unknown genre", true) }
                    .distinctBy { it.lowercase() }
                for (genre in genres) {
                    val key = genre.lowercase()
                    genreWeights.merge(key, plays / genres.size, Double::plus)
                    genreNames.putIfAbsent(key, genre)
                }
                if (song.year > 0) decadeWeights.merge("${song.year / 10 * 10}s", plays, Double::plus)
            }

            // Spotify's own lists.
            fun topArtists(range: String) = seeds
                .filter { it.kind == SeedKind.TOP_ARTIST.key && it.timeRange == range }
                .sortedBy { it.position }.map { it.artistName }.take(SPOTIFY_LIST)
            val followed = seeds.filter { it.kind == SeedKind.FOLLOWED_ARTIST.key }.sortedBy { it.position }
            val spotifyGenres = HashMap<String, Double>()
            for (seed in seeds) {
                val weight = 1.0 - 0.7 * (seed.position.toDouble() / seed.listSize.coerceAtLeast(1))
                seed.genres?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.forEach { spotifyGenres.merge(it, weight, Double::plus) }
            }

            // Plays with a real time: this app's (heard 30 s or more) and Spotify's.
            val localTimes = localEventsD.await()
                .filter { it.timestamp >= since && it.durationMs >= 30_000L }.map { it.timestamp }

            TasteProfileInput(
                period = period,
                nowMs = nowMs,
                zone = ZoneId.systemDefault(),
                librarySongs = songs.size,
                appPlays = summary?.appPlays ?: 0,
                importedPlays = summary?.importedPlays ?: 0,
                spotifyPlays = summary?.spotifyPlays ?: 0,
                youTubePlays = summary?.youTubeMusicPlays ?: 0,
                artists = artistsD.await(),
                tracks = tracksD.await(),
                outcomes = outcomes.entries
                    .map { (key, value) -> ArtistOutcome(key, value.name, value.plays, value.completions, value.skips) }
                    .sortedByDescending { it.plays },
                spotifyTopArtistsRecent = topArtists("short_term"),
                spotifyTopArtistsLongTerm = topArtists("long_term"),
                followedArtists = followed.take(SPOTIFY_LIST * 2).map { it.artistName },
                followedCount = followed.size,
                spotifyGenres = spotifyGenres.entries.map { it.key to it.value },
                libraryGenres = genreWeights.entries.map { (key, weight) -> (genreNames[key] ?: key) to weight },
                decades = decadeWeights.entries.map { it.key to it.value },
                playTimes = importedTimesD.await() + localTimes,
                adjustments = adjustmentsD.await(),
            )
        }
    }

    private class MutableOutcome(val name: String, var plays: Int = 0, var completions: Int = 0, var skips: Int = 0)

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Wide enough that the top 15 artists' shares are shares of (nearly) all plays. */
        const val ARTIST_POOL = 400
        const val TRACK_POOL = 100
        const val OUTCOME_ARTISTS = 2_000
        const val MAX_TIMES = 60_000
        const val SPOTIFY_LIST = 10
        const val MAX_AGE_MS = 10 * 60_000L

    }
}

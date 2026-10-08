package com.theveloper.pixelplay.data.ai.curator

import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.ai.local.LocalAi
import com.theveloper.pixelplay.data.ai.local.LocalGenerationRequest
import com.theveloper.pixelplay.data.ai.local.OnDeviceAiException
import com.theveloper.pixelplay.data.ai.local.OnDeviceFailure
import com.theveloper.pixelplay.data.ai.local.OnDevicePrompts
import com.theveloper.pixelplay.data.ai.local.TokenBudget
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.stats.StatsTimeRange
import com.theveloper.pixelplay.data.tais.dj.TaisIntentParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every AI playlist on the on-device route — the Daily Mix sparkle sheet, the AI Playlist Lab
 * (5-150 songs) and Daily Mix refine — in three steps sized for a 4k-token model:
 *
 * 1. PLAN: the model reads the request plus the library's top genres and artists (no song ids)
 *    and answers one small JSON object ([PlaylistPlan]). An unusable answer, or a request the
 *    model blocks or finds too long, falls back to the keywords [TaisIntentParser] finds.
 * 2. FILL: the app scores the library against the plan ([PlaylistPlanFiller]): genre, artist,
 *    mood/energy through genre families, era, play counts for "discovery", favourites, then
 *    tops up from the seed pool. At most 3 songs per artist unless the request names them.
 * 3. ORDER: the model orders the first 40 as numbered lines ("N. Title — Artist · genre") and
 *    answers with numbers only; anything missing keeps its fill position. A failed order step
 *    keeps the fill order — the playlist is already right.
 *
 * Cloud requests never come here (AiPlaylistGenerator keeps its JSON-of-ids prompt for them).
 */
@Singleton
class OnDevicePlaylistCurator @Inject constructor(
    private val localAi: LocalAi,
    private val dailyMixManager: DailyMixManager,
    private val statsRepository: PlaybackStatsRepository,
    private val intentParser: TaisIntentParser,
) {
    suspend fun curate(
        userPrompt: String,
        allSongs: List<Song>,
        minLength: Int,
        maxLength: Int,
        seedPool: List<Song>?,
    ): Result<List<Song>> {
        return try {
            if (allSongs.isEmpty()) return Result.success(emptyList())
            val target = PlaylistPlanFiller.targetSize(minLength, maxLength)
            val vocabulary = buildVocabulary(allSongs)
            val plan = plan(userPrompt, vocabulary)
            Timber.tag(TAG).d("On-device plan: %s", plan)

            val engagements = dailyMixManager.getAllEngagementStats()
            val favorites = allSongs.filter { it.isFavorite }.map { it.id }.toSet()
            val picks = seedPool?.takeIf { it.isNotEmpty() }
                ?: dailyMixManager.generateDailyMix(allSongs, favorites, limit = SEED_POOL_SIZE)
            val rank = dailyMixManager.getTopCandidatesForAi(allSongs, favorites, limit = RANK_POOL_SIZE)
                .withIndex().associate { (index, song) -> song.id to index }

            val filled = withContext(Dispatchers.Default) {
                PlaylistPlanFiller.fill(
                    plan = plan,
                    inputs = PlaylistPlanFiller.Inputs(
                        library = allSongs,
                        playCounts = engagements.mapValues { it.value.playCount },
                        favoriteIds = favorites,
                        personalRank = rank,
                        seedPool = picks,
                    ),
                    target = target,
                )
            }
            Result.success(order(userPrompt, filled))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnDeviceAiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "On-device curation failed")
            Result.failure(OnDeviceAiException(OnDeviceFailure.OTHER, cause = e))
        }
    }

    private suspend fun plan(userPrompt: String, vocabulary: LibraryVocabulary): PlaylistPlan {
        val fallback = { PlaylistPlanParser.fromIntent(intentParser.parse(userPrompt), vocabulary) }
        val prompt = buildPlanPrompt(userPrompt, vocabulary)
        val raw = try {
            localAi.generate(
                LocalGenerationRequest(
                    instruction = OnDevicePrompts.PLAYLIST_PLAN,
                    prompt = prompt,
                    temperature = 0.2f,
                    topK = 20,
                    maxOutputTokens = OnDevicePrompts.PLAN_MAX_OUTPUT,
                )
            ).text
        } catch (e: OnDeviceAiException) {
            // The model said no to this request (or it didn't fit): the keywords still make a playlist.
            if (e.failure == OnDeviceFailure.BLOCKED || e.failure == OnDeviceFailure.TOO_LONG) return fallback()
            throw e
        }
        val parsed = PlaylistPlanParser.parse(raw, vocabulary)
        return if (parsed == null || parsed.isEmpty) fallback() else parsed
    }

    private suspend fun order(userPrompt: String, songs: List<Song>): List<Song> {
        if (songs.size < 3) return songs
        val head = songs.take(OnDevicePrompts.ORDER_MAX_SONGS)
        val lines = head.mapIndexed { index, song ->
            val genre = song.genre?.takeIf { it.isNotBlank() }?.let { " · ${it.take(24)}" }.orEmpty()
            "${index + 1}. ${song.title.take(48)} — ${song.displayArtist.take(32)}$genre"
        }
        val request = TokenBudget.clamp(userPrompt.trim(), 80)
        val prompt = "Playlist request: $request\nSongs:\n" + lines.joinToString("\n")
        val regex = "\\d{1,2}(, ?\\d{1,2}){0,${head.size - 1}}"
        val raw = try {
            localAi.generate(
                LocalGenerationRequest(
                    instruction = OnDevicePrompts.PLAYLIST_ORDER,
                    prompt = prompt,
                    temperature = 0.3f,
                    topK = 20,
                    maxOutputTokens = OnDevicePrompts.ORDER_MAX_OUTPUT,
                    regex = regex,
                )
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnDeviceAiException) {
            if (e.failure == OnDeviceFailure.NEEDS_FOREGROUND) throw e
            Timber.tag(TAG).w(e, "Ordering failed; keeping the fill order")
            return songs
        }
        val permutation = OrderResponseParser.parse(raw, head.size)
        return permutation.map { head[it] } + songs.drop(head.size)
    }

    private fun buildPlanPrompt(userPrompt: String, vocabulary: LibraryVocabulary): String {
        val request = TokenBudget.clamp(userPrompt.trim(), OnDevicePrompts.PLAN_REQUEST_TOKENS)
        val half = OnDevicePrompts.PLAN_VOCABULARY_TOKENS / 2
        val genres = TokenBudget.shrinkToFit(vocabulary.genres, half, ", ") { it }
        val artists = TokenBudget.shrinkToFit(vocabulary.artists, half, ", ") { it }
        return buildString {
            append("Request: ").append(request).append('\n')
            append("Genres in the library: ").append(genres.joinToString(", ")).append('\n')
            append("Artists in the library: ").append(artists.joinToString(", "))
        }
    }

    /** The top 25 genres and top 30 artists, by plays then by song count. */
    private suspend fun buildVocabulary(allSongs: List<Song>): LibraryVocabulary {
        val summary = runCatching { statsRepository.loadSummary(StatsTimeRange.ALL, allSongs) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        return withContext(Dispatchers.Default) {
            val genrePlays = summary?.topGenres.orEmpty()
                .filter { it.genre != PlaybackStatsRepository.UNKNOWN_GENRE_LABEL }
                .associate { it.genre to it.playCount }
            val artistPlays = summary?.topArtists.orEmpty().associate { it.artist to it.playCount }
            val genreCounts = allSongs.mapNotNull { song -> song.genre?.trim()?.takeIf { it.isNotEmpty() } }
                .groupingBy { it }.eachCount()
            val artistCounts = allSongs.map { it.primaryArtist.name.ifBlank { it.artist } }
                .filter { it.isNotBlank() }
                .groupingBy { it }.eachCount()
            LibraryVocabulary(
                genres = genreCounts.keys
                    .sortedWith(compareByDescending<String> { genrePlays[it] ?: 0 }.thenByDescending { genreCounts[it] ?: 0 })
                    .take(MAX_GENRES),
                artists = artistCounts.keys
                    .sortedWith(compareByDescending<String> { artistPlays[it] ?: 0 }.thenByDescending { artistCounts[it] ?: 0 })
                    .take(MAX_ARTISTS),
            )
        }
    }

    /** Loads the model when an AI playlist sheet opens, so the first request doesn't wait for it. */
    suspend fun prewarm() = localAi.prewarm()

    private companion object {
        const val TAG = "OnDeviceCurator"
        const val MAX_GENRES = 25
        const val MAX_ARTISTS = 30
        const val SEED_POOL_SIZE = 120
        const val RANK_POOL_SIZE = 200
    }
}

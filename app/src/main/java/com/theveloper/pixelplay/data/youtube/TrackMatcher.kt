package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.database.SpotifySongEntity
import timber.log.Timber
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TrackMatch(
    val videoId: String,
    val score: Float,
    val candidateTitle: String
)

/**
 * Empareja una pista de Spotify con un vídeo de YouTube Music.
 *
 * El riesgo real aquí no es no encontrar nada, sino encontrar lo *equivocado*: buscar
 * "Hurt" devuelve la versión en directo, la de otro artista y tres remezclas antes que la
 * original. Por eso la duración pesa tanto (una versión distinta casi nunca dura lo mismo)
 * y las palabras como "live" o "remix" restan salvo que el título de Spotify también las
 * lleve.
 */
@Singleton
class TrackMatcher @Inject constructor(
    private val innerTubeClient: InnerTubeClient
) {

    suspend fun findMatch(song: SpotifySongEntity): TrackMatch? {
        val queries = buildQueries(song)

        var best: TrackMatch? = null
        var lastNetworkFailure: IOException? = null
        suspend fun consider(query: String, videos: Boolean) {
            val candidates = try {
                if (videos) innerTubeClient.searchVideos(query, CANDIDATES_PER_QUERY)
                else innerTubeClient.searchSongs(query, CANDIDATES_PER_QUERY)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastNetworkFailure = IOException("Music search temporarily unavailable", e)
                return
            }
            candidates.forEach { candidate ->
                val score = score(song, candidate)
                if (best == null || score > best!!.score) {
                    best = TrackMatch(candidate.videoId, score, candidate.title)
                }
            }
        }
        for (query in queries) {
            consider(query, videos = false)
            best?.let { if (it.score >= EARLY_ACCEPT_SCORE) return it }
        }
        // Some releases only have a video upload. Prefer the official audio when it
        // exists, then score the video shelf with the same artist/version safeguards.
        if (best == null || best!!.score < EARLY_ACCEPT_SCORE) {
            consider(queries.first(), videos = true)
        }
        if ((best == null || best!!.score < MIN_ACCEPT_SCORE) && lastNetworkFailure != null) {
            throw lastNetworkFailure!!
        }

        val result = best
        if (result == null || result.score < MIN_ACCEPT_SCORE) {
            Timber.d("Sin coincidencia aceptable para '${song.title}' (mejor: ${result?.score})")
            return null
        }
        return result
    }

    private fun buildQueries(song: SpotifySongEntity): List<String> = buildList {
        val primaryArtist = song.artist.substringBefore(",").trim()
        add("${song.title} $primaryArtist")
        if (song.album.isNotBlank() && !song.album.equals("Unknown Album", true)) {
            add("${song.title} $primaryArtist ${song.album}")
        }
        add(song.title)
    }.distinct()

    // ─── Puntuación ────────────────────────────────────────────────────

    internal fun score(song: SpotifySongEntity, candidate: YouTubeSearchResult): Float {
        var score = 0f

        val songTitle = normalize(song.title)
        val normalizedArtist = normalize(song.artist.substringBefore(","))
        val videoTitleParts = candidate.title.split(Regex("""\s*[-–—|:]\s*"""), limit = 2)
        val hasExactVideoArtist = candidate.isMusicVideo && videoTitleParts.size == 2 &&
            normalize(videoTitleParts.first()) == normalizedArtist
        val candidateTitle = if (hasExactVideoArtist) normalize(videoTitleParts[1]) else normalize(candidate.title)
        val titleSimilarity = similarity(songTitle, candidateTitle)
        score += titleSimilarity * TITLE_WEIGHT

        val songArtist = normalize(song.artist.substringBefore(","))
        val candidateArtist = normalize(candidate.artist)
            .removeSuffix(" topic").removeSuffix("vevo").trim()
        val artistScore = maxOf(
            artistSimilarity(songArtist, candidateArtist),
            if (hasExactVideoArtist) 0.9f else 0f
        )
        if (titleSimilarity < 0.55f || artistScore < 0.65f) return 0f
        score += artistScore * ARTIST_WEIGHT

        // Duración: la señal más fiable de que es la *misma* grabación.
        val expectedSeconds = (song.durationMs / 1000L).toInt()
        val actualSeconds = candidate.durationSeconds
        score += when {
            actualSeconds == null || expectedSeconds <= 0 -> 0f
            abs(actualSeconds - expectedSeconds) <= EXACT_DURATION_TOLERANCE_S -> DURATION_WEIGHT
            abs(actualSeconds - expectedSeconds) <= LOOSE_DURATION_TOLERANCE_S -> DURATION_WEIGHT * 0.5f
            else -> -DURATION_WEIGHT
        }

        if (!candidate.album.isNullOrBlank() &&
            similarity(normalize(song.album), normalize(candidate.album)) > 0.85f
        ) {
            score += ALBUM_BONUS
        }

        // Variantes no deseadas, salvo que la pista de Spotify ya las anuncie.
        VARIANT_PENALTY_WORDS.forEach { word ->
            val inCandidate = containsPhrase(candidateTitle, word)
            val inSource = containsPhrase(songTitle, word)
            if (inCandidate && !inSource) score -= VARIANT_PENALTY
        }

        return score.coerceIn(0f, 1f)
    }

    /**
     * Los artistas se comparan por tokens: YouTube Music suele mostrar solo el principal
     * mientras que Spotify lista a todos los colaboradores.
     */
    private fun artistSimilarity(source: String, candidate: String): Float {
        if (source.isBlank() || candidate.isBlank()) return 0f
        if (source == candidate) return 1f
        if ((" $candidate ").contains(" $source ")) return 0.9f

        val sourceTokens = source.split(' ').filter { it.length > 2 }.toSet()
        val candidateTokens = candidate.split(' ').filter { it.length > 2 }.toSet()
        if (sourceTokens.isEmpty() || candidateTokens.isEmpty()) return similarity(source, candidate)

        val shared = sourceTokens.intersect(candidateTokens).size
        return shared.toFloat() / max(sourceTokens.size, candidateTokens.size)
    }

    companion object {
        private const val CANDIDATES_PER_QUERY = 8
        const val MIN_ACCEPT_SCORE = 0.55f
        private const val EARLY_ACCEPT_SCORE = 0.88f

        private const val TITLE_WEIGHT = 0.40f
        private const val ARTIST_WEIGHT = 0.25f
        private const val DURATION_WEIGHT = 0.30f
        private const val ALBUM_BONUS = 0.05f
        private const val VARIANT_PENALTY = 0.25f

        private const val EXACT_DURATION_TOLERANCE_S = 3
        private const val LOOSE_DURATION_TOLERANCE_S = 8

        private val VARIANT_PENALTY_WORDS = listOf(
            "live", "cover", "remix", "sped up", "slowed", "nightcore",
            "karaoke", "instrumental", "8d audio", "reverb"
        )

        private fun containsPhrase(text: String, phrase: String) =
            (" $text ").contains(" $phrase ")

        private val NOISE_REGEX = Regex("""[\[(](?:official|lyric|audio|video|hd|mv)[^\])]*[\])]""")
        private val TRAILING_NOISE = Regex("""\b(?:official music video|official video|official audio|lyrics video|lyric video|lyrics|hd|4k)\b""")
        private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}\s]""")
        private val WHITESPACE = Regex("""\s+""")

        /** Preserve non-Latin titles; the former ASCII filter erased entire song names. */
        internal fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .replace(Regex("""\p{M}+"""), "")
            .replace(NOISE_REGEX, " ")
            .replace(TRAILING_NOISE, " ")
            .replace(NON_ALPHANUMERIC, " ")
            .replace(WHITESPACE, " ")
            .trim()

        /** Similitud 0..1 basada en distancia de edición. */
        internal fun similarity(a: String, b: String): Float {
            if (a == b) return 1f
            if (a.isEmpty() || b.isEmpty()) return 0f
            val distance = levenshtein(a, b)
            val longest = max(a.length, b.length)
            return 1f - distance.toFloat() / longest
        }

        private fun levenshtein(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)

            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}

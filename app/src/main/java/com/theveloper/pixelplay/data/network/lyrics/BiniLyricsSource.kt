package com.theveloper.pixelplay.data.network.lyrics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDocCodec
import com.theveloper.pixelplay.data.model.LyricsMetadata
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.LyricsMatching
import com.theveloper.pixelplay.utils.AppleTtmlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * BiniLyrics (https://lyrics.binimum.org): a free, keyless catalog of Apple Music-quality
 * TTML, most of it word-timed, many files with background vocals and duet agents.
 *
 * Lookup: by ISRC when the song has one (exact recording), otherwise (or on an ISRC miss) one
 * track+artist search. A search hit is used only when title, artist and duration all match
 * the song, version descriptors (remix, live, sped up...) agree, and exactly one best
 * candidate remains; anything ambiguous is a miss, never a guess.
 *
 * Network manners: one lookup per song (results and misses are memoised, concurrent callers
 * for the same song share one lookup), no retries, a growing back-off after HTTP 429/5xx,
 * HTTPS to an allowlist of BiniLyrics hosts only (redirects included), capped bodies and
 * tight timeouts. Never throws except for cancellation.
 */
internal class BiniLyricsSource(
    baseClient: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val entryUrl: HttpUrl = ENTRY_URL.toHttpUrl(),
) {
    /** A catalog row, as the search endpoint returns it. */
    data class Candidate(
        val trackName: String,
        val artistName: String,
        val albumName: String,
        /** Seconds, or `null` when the catalog does not say. */
        val durationSec: Double?,
        val isrc: String?,
        val lyricsUrl: String,
        /** `word`, `line`, `none`, or whatever else the catalog starts sending. */
        val timing: String,
    )

    data class Match(val lyrics: Lyrics, val candidate: Candidate, val songwriters: List<String>) {
        val isWordTimed: Boolean get() = lyrics.synced.orEmpty().any { !it.words.isNullOrEmpty() }
    }

    private sealed interface Outcome {
        data class Found(val match: Match) : Outcome
        /** A definitive answer (no match, or the file failed validation): memoised. */
        data object Miss : Outcome
        /** Network trouble or back-off: not memoised, so a later play may try again. */
        data object Transient : Outcome
    }

    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    private class MemoEntry(val match: Match?, val expiresAt: Long)
    private val memo = object : LinkedHashMap<String, MemoEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemoEntry>?) = size > MEMO_SIZE
    }
    private val locks = Array(LOCK_STRIPES) { Mutex() }

    @Volatile private var blockedUntil = 0L
    @Volatile private var backoffMs = 0L

    suspend fun find(song: Song, isrc: String? = null): Match? = withContext(Dispatchers.IO) {
        try {
            if (song.title.isBlank()) return@withContext null
            val normalizedIsrc = normalizeIsrc(isrc)
            val key = listOf(song.id, song.title, song.artist, song.album, song.duration, normalizedIsrc)
                .joinToString("\u0000")
            locks[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES].withLock {
                synchronized(memo) { memo[key] }?.let { entry ->
                    if (entry.expiresAt > now()) return@withContext entry.match
                    synchronized(memo) { memo.remove(key) }
                }
                if (now() < blockedUntil) return@withContext null
                when (val outcome = lookup(song, normalizedIsrc)) {
                    is Outcome.Found -> remember(key, outcome.match, FOUND_TTL_MS)
                    Outcome.Miss -> remember(key, null, MISS_TTL_MS)
                    Outcome.Transient -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun remember(key: String, match: Match?, ttlMs: Long): Match? {
        synchronized(memo) { memo[key] = MemoEntry(match, now() + ttlMs) }
        return match
    }

    private suspend fun lookup(song: Song, isrc: String?): Outcome {
        if (isrc != null) {
            val rows = search(mapOf("isrc" to isrc)) ?: return Outcome.Transient
            val hits = rows.filter { it.isrc.equals(isrc, ignoreCase = true) && durationMatches(song, it) }
            // An ISRC names the exact recording: prefer its word-timed file, then line, then plain.
            hits.maxByOrNull { timingRank(it.timing) }?.let { return fetchLyrics(it, song) }
        }
        if (LyricsMatching.isUnknownArtist(song.artist) && LyricsMatching.isUnknownArtist(song.displayArtist)) {
            return Outcome.Miss
        }
        val artist = song.artist.takeUnless(LyricsMatching::isUnknownArtist) ?: song.displayArtist
        val rows = search(mapOf("track" to searchTitle(song.title), "artist" to artist)) ?: return Outcome.Transient
        val candidate = selectCandidate(song, rows) ?: return Outcome.Miss
        return fetchLyrics(candidate, song)
    }

    private suspend fun fetchLyrics(candidate: Candidate, song: Song): Outcome {
        val url = candidate.lyricsUrl.toHttpUrlOrNull()?.takeIf(::isAllowed) ?: return Outcome.Miss
        val response = get(url, MAX_TTML_BYTES, "application/ttml+xml, application/xml;q=0.9") ?: return Outcome.Transient
        if (response.code == 404 || response.code == 410) return Outcome.Miss
        val body = response.body ?: return Outcome.Transient
        val parsed = AppleTtmlParser.parse(body, LyricsMetadata(song.title, song.artist, song.album, source = SOURCE_NAME))
            ?: return Outcome.Miss
        val lyrics = parsed.toLyrics().copy(areFromRemote = true)
        if (!passesSanityChecks(lyrics, song)) return Outcome.Miss
        return Outcome.Found(Match(lyrics, candidate, parsed.songwriters))
    }

    /** Fetches the catalog search; `null` for network trouble, 429/5xx (with back-off) or a bad body. */
    private suspend fun search(query: Map<String, String>): List<Candidate>? {
        val url = entryUrl.newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val response = get(url, MAX_JSON_BYTES, "application/json") ?: return null
        if (response.code == 404) return emptyList()
        val body = response.body ?: return null
        if (!LyricsDocCodec.isBoundedJson(body)) return null
        val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val results = root.get("results")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return results.asSequence().take(MAX_RESULTS)
            .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(::candidate) }
            .toList()
    }

    /**
     * GET with manual redirects: each hop must stay on HTTPS and on an allowlisted host, at
     * most [MAX_REDIRECTS] hops. Returns `null` on network failure, a refused redirect, or
     * HTTP 403/429/5xx (which also starts or extends the back-off).
     */
    private suspend fun get(start: HttpUrl, maxBytes: Int, accept: String): BoundedResponse? {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            currentCoroutineContext().ensureActive()
            if (!isAllowed(url)) return null
            val response = try {
                client.boundedGet(url, maxBytes, accept)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
            when {
                // 403 is Cloudflare refusing us: back off exactly as for rate limiting.
                response.code == 403 || response.code == 429 || response.code in 500..599 -> {
                    backOff(response.retryAfter)
                    return null
                }
                response.code in 300..399 -> {
                    url = response.location?.let(url::resolve) ?: return null
                }
                else -> {
                    if (response.code in 200..299) backoffMs = 0L
                    return response
                }
            }
        }
        return null
    }

    private fun backOff(retryAfter: String?) {
        val requested = retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.times(1_000L)
        backoffMs = if (backoffMs == 0L) MIN_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        blockedUntil = now() + maxOf(backoffMs, requested ?: 0L).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun candidate(row: JsonObject): Candidate? {
        fun string(name: String) = row.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        val lyricsUrl = string("lyricsUrl")?.takeIf { it.isNotEmpty() } ?: return null
        return Candidate(
            trackName = string("track_name").orEmpty(),
            artistName = string("artist_name").orEmpty(),
            albumName = string("album_name").orEmpty(),
            durationSec = row.get("duration")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asDouble?.takeIf { it.isFinite() && it > 0 },
            isrc = string("isrc")?.takeIf { it.isNotEmpty() },
            lyricsUrl = lyricsUrl,
            timing = string("timing_type")?.lowercase(Locale.ROOT).orEmpty(),
        )
    }

    companion object {
        const val SOURCE_NAME = "BiniLyrics"
        const val WEBSITE = "https://lyrics.binimum.org"
        private const val ENTRY_URL = "https://lyrics-api.binimum.org/"

        /** Every host a lookup may touch; redirects and lyrics URLs elsewhere are refused. */
        internal val ALLOWED_HOSTS = setOf("lyrics-api.binimum.org", "lrc.red", "lyrics-storage.binimum.org")

        private const val MAX_JSON_BYTES = 1_048_576
        private const val MAX_TTML_BYTES = AppleTtmlParser.MAX_TTML_CHARS
        private const val MAX_RESULTS = 50
        private const val MAX_REDIRECTS = 3
        private const val DURATION_TOLERANCE_MS = 3_000L
        /** Starts beyond the song's end by more than this mean the file is for another cut. */
        private const val PAST_END_TOLERANCE_MS = 1_500L
        private const val MIN_BACKOFF_MS = 60_000L
        private const val MAX_BACKOFF_MS = 15 * 60_000L
        private const val FOUND_TTL_MS = 30 * 60_000L
        private const val MISS_TTL_MS = 30 * 60_000L
        private const val MEMO_SIZE = 64
        private const val LOCK_STRIPES = 16

        private val ISRC_PATTERN = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")
        private val FEATURED_CREDIT = Regex("""(?i)\s*[\(\[](?:feat\.?|ft\.?|featuring|with)\s+[^\)\]]+[\)\]]""")
        /** Remasters keep the original's timing (the duration check still applies). */
        private val REMASTER_QUALIFIER = Regex(
            """(?i)\s*(?:[\(\[][^\)\]]*\bremaster(?:ed)?\b[^\)\]]*[\)\]]|[-–—]\s*(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?(?:\s+version)?\s*$)""",
        )

        internal fun isAllowed(url: HttpUrl): Boolean = url.isHttps && url.host.lowercase(Locale.ROOT) in ALLOWED_HOSTS

        internal fun normalizeIsrc(raw: String?): String? =
            raw?.uppercase(Locale.ROOT)?.filter { it.isLetterOrDigit() }?.takeIf { ISRC_PATTERN.matches(it) }

        private fun searchTitle(title: String): String = title.replace(FEATURED_CREDIT, "").trim().ifEmpty { title }

        /** Higher is better: word timing, then line timing, then unknown kinds, then unsynced. */
        internal fun timingRank(timing: String): Int = when (timing) {
            "word", "syllable" -> 3
            "line" -> 2
            "none", "unsynced", "plain", "" -> 0
            else -> 1
        }

        private fun durationMatches(song: Song, candidate: Candidate): Boolean {
            val seconds = candidate.durationSec ?: return true
            if (song.duration <= 0) return true
            return abs(seconds * 1000 - song.duration) <= DURATION_TOLERANCE_MS
        }

        private fun titleKey(title: String): String =
            LyricsMatching.baseTitleForMatching(title.replace(REMASTER_QUALIFIER, " "))

        internal fun titleMatches(song: Song, candidate: Candidate): Boolean {
            if (!LyricsMatching.variantsCompatible(song, candidate.trackName)) return false
            val songKey = titleKey(song.title)
            val candidateKey = titleKey(candidate.trackName)
            if (songKey.isBlank() || candidateKey.isBlank()) return false
            if (songKey == candidateKey) return true
            // Same title written in another script (e.g. kana vs romaji).
            return LyricsMatching.normalizeForMatch(LyricsMatching.romanizeForMatch(songKey))
                .let { it.isNotBlank() && it == LyricsMatching.normalizeForMatch(LyricsMatching.romanizeForMatch(candidateKey)) }
        }

        /**
         * The song's artist must be the catalog artist, or be contained in its credit list
         * ("The Weeknd" in "The Weeknd, ROSALÍA"), or every name-token of the shorter credit
         * must appear in the longer one. A single shared word ("Lady Gaga" / "Lady A") is not enough.
         */
        internal fun artistMatches(song: Song, candidate: Candidate): Boolean =
            listOf(song.artist, song.displayArtist).distinct()
                .filterNot(LyricsMatching::isUnknownArtist)
                .any { artist -> artistScore(artist, candidate.artistName) != null }

        private fun artistScore(songArtist: String, catalogArtist: String): Int? {
            val score = LyricsMatching.artistMatchScore(songArtist, catalogArtist) ?: return null
            if (score >= 22) return score
            val a = LyricsMatching.artistTokens(LyricsMatching.normalizeForMatch(songArtist))
            val b = LyricsMatching.artistTokens(LyricsMatching.normalizeForMatch(catalogArtist))
            if (a.isEmpty() || b.isEmpty()) return null
            val (shorter, longer) = if (a.size <= b.size) a to b else b to a
            return if (longer.containsAll(shorter)) score else null
        }

        private fun albumMatches(song: Song, candidate: Candidate): Boolean =
            song.album.isNotBlank() && candidate.albumName.isNotBlank() &&
                titleKey(song.album) == titleKey(candidate.albumName)

        /**
         * All of title, artist and duration (when the song's is known) must match. Among the
         * survivors: word timing beats line timing beats unsynced; then the song's album; then
         * an exact artist credit; then the closer duration. A tie on all of those is ambiguous.
         * With no known duration the album must match too, as the only check left on the cut.
         */
        internal fun selectCandidate(song: Song, rows: List<Candidate>): Candidate? {
            data class Scored(val c: Candidate, val timing: Int, val album: Boolean, val exactArtist: Boolean, val diffMs: Long)
            val scored = rows
                .distinctBy { it.isrc ?: it.lyricsUrl }
                .filter { titleMatches(song, it) && artistMatches(song, it) && durationMatches(song, it) }
                .filter { song.duration > 0 || albumMatches(song, it) }
                .map { c ->
                    Scored(
                        c = c,
                        timing = timingRank(c.timing),
                        album = albumMatches(song, c),
                        exactArtist = listOf(song.artist, song.displayArtist).any {
                            LyricsMatching.normalizeForMatch(it) == LyricsMatching.normalizeForMatch(c.artistName)
                        },
                        diffMs = c.durationSec?.let { abs((it * 1000).toLong() - song.duration) } ?: DURATION_TOLERANCE_MS,
                    )
                }
                .sortedWith(
                    compareByDescending<Scored> { it.timing }
                        .thenByDescending { it.album }
                        .thenByDescending { it.exactArtist }
                        .thenBy { it.diffMs },
                )
            val best = scored.firstOrNull() ?: return null
            val runnerUp = scored.getOrNull(1)
            if (runnerUp != null && runnerUp.timing == best.timing && runnerUp.album == best.album &&
                runnerUp.exactArtist == best.exactArtist && abs(runnerUp.diffMs - best.diffMs) < 1_000L
            ) return null
            return best.c
        }

        /** Like AMLL's checks: real timing exists, and nothing starts well past the song's end. */
        internal fun passesSanityChecks(lyrics: Lyrics, song: Song): Boolean {
            val synced = lyrics.synced.orEmpty()
            if (synced.isEmpty()) return lyrics.plain.orEmpty().any { it.isNotBlank() }
            if (synced.none { it.time > 0 || it.words.orEmpty().any { w -> w.time > 0 } }) return false
            if (song.duration > 0) {
                val limit = song.duration + PAST_END_TOLERANCE_MS
                if (synced.any { it.time > limit || it.words.orEmpty().any { w -> w.time > limit } }) return false
            }
            return true
        }
    }
}

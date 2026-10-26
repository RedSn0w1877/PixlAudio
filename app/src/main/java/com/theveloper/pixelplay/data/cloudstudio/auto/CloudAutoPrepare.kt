package com.theveloper.pixelplay.data.cloudstudio.auto

import com.theveloper.pixelplay.data.cloudstudio.CloudBudget
import com.theveloper.pixelplay.data.cloudstudio.CloudCost
import com.theveloper.pixelplay.data.cloudstudio.CloudSeparationQuality
import com.theveloper.pixelplay.data.cloudstudio.CloudSongPlan
import com.theveloper.pixelplay.data.cloudstudio.CloudTask
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Smart auto-prepare's pure rules (Settings › Sing › "Prepare songs ahead in the cloud"): which songs the person is
// likely to sing, in what order, how many fit the day and the month, and what the settings screen says about them.
// The same constants and formula are on the iOS app (`SingPrepareRanker`, `CloudAutoBudget`), so both apps pick the
// same songs from the same facts. Nothing here touches Android or the network; the facts are gathered by
// [CloudAutoFactsGatherer] and the work is sequenced by [CloudAutoPrepareRunner].

/**
 * A song's listening signals ([MusicTasteRepository]'s `Signal`): sessions, finished plays and early skips. A song
 * nothing was learned about has none ([AutoCandidateFacts.signal] is null), which is not the same as zero sessions:
 * its completion-rate and skip-rate terms are 0 rather than the prior's 0.5.
 */
data class AutoSignal(val sessions: Int = 0, val completions: Int = 0, val earlySkips: Int = 0)

/** One library song's facts for the ranker. */
data class AutoCandidateFacts(
    /** The library id the cloud job is made for. */
    val songId: String,
    /** The Spotify track a streamed song plays (where its cached audio is looked up). */
    val spotifyId: String? = null,
    /** The id every join uses: a Spotify alias and its unified library row are one song (`TaisInstrumentalIndex.canonicalSongId`). */
    val canonicalId: String,
    val title: String = "",
    val artist: String = "",
    /** Songs with the same non-null key are on the same album (library songs only; no Spotify call is ever made for this). */
    val albumKey: String? = null,
    val durationMs: Long,
    val isStreamed: Boolean = false,
    val favorite: Boolean = false,
    val playCount: Int = 0,
    /** Unix ms of the last play; 0 = never played. */
    val lastPlayedMs: Long = 0,
    /** Unix ms the song was added; 0 = unknown. */
    val dateAddedMs: Long = 0,
    /** Completions and skips; null when learning is off (those terms are then 0). */
    val signal: AutoSignal? = null,
    /** The playlist-neighbour boost this song earned ([CloudAutoPrepare.playlistNeighbourBoosts]). */
    val neighbourBoost: Double = 0.0,
    val hasInstrumental: Boolean = false,
    val hasPendingJob: Boolean = false,
    val blockedArtist: Boolean = false,
    /** The song's audio is already on this phone: the first version only prepares such songs. */
    val audioCached: Boolean = false,
    /** What the listener asked Ask Taizo to favour (>1) or avoid (<1) for this song, artist, genre or album; 1.0 = nothing. */
    val tasteMultiplier: Double = 1.0,
)

/** A ranked candidate: its score, and the album boost inside it (for the "Why these songs" list). */
data class AutoRanked(val facts: AutoCandidateFacts, val score: Double, val albumBoost: Double = 0.0)

/** What the day and the month leave room for. */
data class AutoSelectionState(
    /** Songs the daily cap still allows. */
    val dailyRemaining: Int,
    /** The automatic share of the month ([CloudSettingsSnapshot.effectiveAutoBudgetMicroUsd]) and what it has committed. */
    val autoBudgetMicroUsd: Long,
    val autoCommittedMicroUsd: Long,
    /** What is left of the shared monthly cap. */
    val capRemainingMicroUsd: Long,
    val pricePerSecondMicroUsd: Long,
    val quality: CloudSeparationQuality = CloudSeparationQuality.STANDARD,
    /** Songs that failed or dropped lately and rest ([AutomaticStudioCooldowns]). */
    val isCoolingDown: (String) -> Boolean = { false },
    val batchLimit: Int = CloudAutoPrepare.BATCH_SIZE,
    /** How many jobs RunPod runs at once ([CloudSettingsSnapshot.parallelWorkers]): the batch pays a cold start for each. */
    val parallelWorkers: Int = 1,
)

/** The songs picked for one run, with what the batch is estimated to cost. */
data class AutoSelection(val picks: List<AutoRanked>, val estimateMicroUsd: Long)

object CloudAutoPrepare {
    // ─── The ranker (identical constants on both apps) ──────────────────────────────────────
    const val FAVORITE_WEIGHT = 3.0
    const val PLAYS_WEIGHT = 2.5
    const val PLAY_RECENCY_WEIGHT = 2.0
    const val PLAY_HALF_LIFE_DAYS = 14.0
    const val ADD_RECENCY_WEIGHT = 1.0
    const val ADD_HALF_LIFE_DAYS = 30.0
    const val SKIP_WEIGHT = 3.0
    /** The most recently played distinct songs whose playlist neighbours are boosted… */
    const val NEIGHBOUR_SEEDS = 5
    /** …this many songs after each, boosted by [NEIGHBOUR_WEIGHT] / (1 + distance). */
    const val NEIGHBOUR_REACH = 5
    const val NEIGHBOUR_WEIGHT = 1.0
    /** Library songs on the same album as one of this many top-scored songs get [ALBUM_BOOST]. */
    const val ALBUM_SEEDS = 8
    const val ALBUM_BOOST = 0.8

    // Hard excludes.
    /** Songs this short or shorter, or longer than [MAX_DURATION_MS], are never prepared. */
    const val MIN_DURATION_MS = 60_000L
    const val MAX_DURATION_MS = 8L * 60_000
    /** More often skipped than this: not a song to prepare. */
    const val MAX_SKIP_RATE = 0.4

    // ─── Pace ───────────────────────────────────────────────────────────────────────────────
    /** Songs per run: one cold start is shared by a batch this big. */
    const val BATCH_SIZE = 3
    /** Audio availability is checked on disk for this many of the best-ranked candidates, no more. */
    const val CACHE_CHECK_LIMIT = 60
    /** A song that was picked rests this long before it can be picked again (a failure, a dropped job). */
    const val COOLDOWN_MS = 12L * 3_600_000

    private const val DAY_MS = 86_400_000.0

    /** Songs the ranker would consider for the cloud at all by length. */
    fun isPreparable(durationMs: Long): Boolean = durationMs > MIN_DURATION_MS && durationMs <= MAX_DURATION_MS

    fun skipRate(facts: AutoCandidateFacts): Double =
        facts.signal?.let { it.earlySkips.coerceAtLeast(0).toDouble() / (it.sessions.coerceAtLeast(0) + 2.0) } ?: 0.0

    fun completionRate(facts: AutoCandidateFacts): Double =
        facts.signal?.let { (it.completions.coerceAtLeast(0) + 2.0) / (it.sessions.coerceAtLeast(0) + 4.0) } ?: 0.0

    /**
     * score = 3.0·fav + 2.5·ln(1+plays)·completionRate + 2.0·2^(−daysSincePlay/14) + 1.0·2^(−daysSinceAdd/30)
     *         + neighbour − 3.0·skipRate
     *
     * completionRate = (completions+2)/(sessions+4) and skipRate = earlySkips/(sessions+2). With learning off there
     * are no signals, and the terms that need them are 0. A song never played has no play-recency term, and one with
     * no known date added has no new-song term. [AutoCandidateFacts.neighbourBoost] is the playlist-neighbour part;
     * the album part is added by [rank] once the seeds are known.
     */
    fun score(facts: AutoCandidateFacts, nowMs: Long): Double {
        val favorite = if (facts.favorite) FAVORITE_WEIGHT else 0.0
        val plays = PLAYS_WEIGHT * ln(1.0 + facts.playCount.coerceAtLeast(0)) * completionRate(facts)
        val playRecency = if (facts.lastPlayedMs > 0) {
            PLAY_RECENCY_WEIGHT * 2.0.pow(-daysSince(facts.lastPlayedMs, nowMs) / PLAY_HALF_LIFE_DAYS)
        } else 0.0
        val addRecency = if (facts.dateAddedMs > 0) {
            ADD_RECENCY_WEIGHT * 2.0.pow(-daysSince(facts.dateAddedMs, nowMs) / ADD_HALF_LIFE_DAYS)
        } else 0.0
        val base = favorite + plays + playRecency + addRecency + facts.neighbourBoost - SKIP_WEIGHT * skipRate(facts)
        // An explicit taste change scales the score either way (a negative score gets worse under a reduce).
        val taste = facts.tasteMultiplier
        return if (taste == 1.0) base else if (base >= 0.0) base * taste else base / taste
    }

    private fun daysSince(thenMs: Long, nowMs: Long): Double = max(nowMs - thenMs, 0L) / DAY_MS

    /** Why a song is never a candidate, or null. */
    fun excludedBecause(facts: AutoCandidateFacts): String? = when {
        facts.hasInstrumental -> "It already has an instrumental."
        facts.hasPendingJob -> "It already has a job in the cloud."
        !isPreparable(facts.durationMs) -> "Its length doesn't suit the cloud."
        skipRate(facts) > MAX_SKIP_RATE -> "You often skip it."
        facts.blockedArtist -> "Its artist is blocked."
        else -> null
    }

    /**
     * The playlist-neighbour boost: for each of the [NEIGHBOUR_SEEDS] most recently played distinct songs ([recentFirst],
     * newest first), in every playlist that contains it, the next [NEIGHBOUR_REACH] songs get
     * [NEIGHBOUR_WEIGHT] / (1 + distance), distance 1 for the next song. Playlists are lists of song ids in play order
     * (Spotify playlists have no position column here, so they arrive ordered by the date each song was added).
     */
    fun playlistNeighbourBoosts(recentFirst: List<String>, playlists: List<List<String>>): Map<String, Double> {
        val seeds = recentFirst.distinct().take(NEIGHBOUR_SEEDS)
        val boosts = HashMap<String, Double>()
        for (seed in seeds) {
            for (playlist in playlists) {
                val at = playlist.indexOf(seed)
                if (at < 0) continue
                for (distance in 1..NEIGHBOUR_REACH) {
                    val next = playlist.getOrNull(at + distance) ?: break
                    boosts[next] = (boosts[next] ?: 0.0) + NEIGHBOUR_WEIGHT / (1.0 + distance)
                }
            }
        }
        return boosts
    }

    /**
     * Every candidate, best first: [score] plus [ALBUM_BOOST] for songs on the same album as one of the [ALBUM_SEEDS]
     * best-scoring songs (a seed counts for its own album too). The seeds are taken from the whole library, so a
     * favourite that already has an instrumental, or is too long to prepare, still shows which album you like; only
     * songs by a blocked artist are left out of everything. Hard excludes are then dropped, and ties go to the lower
     * id so the order is the same on every run. (The iOS ranker gives the same scores for the same inputs.)
     */
    fun rank(library: List<AutoCandidateFacts>, nowMs: Long): List<AutoRanked> {
        val scored = library.filter { !it.blockedArtist }.map { it to score(it, nowMs) }
        val seeds = scored.sortedWith(compareByDescending<Pair<AutoCandidateFacts, Double>> { it.second }.thenBy { it.first.canonicalId })
            .take(ALBUM_SEEDS)
        val seedAlbums = seeds.mapNotNullTo(HashSet<String>()) { it.first.albumKey?.takeIf(String::isNotBlank) }
        return scored.asSequence()
            .filter { excludedBecause(it.first) == null }
            .map { (facts, base) ->
                val album = facts.albumKey
                val boost = if (album != null && album in seedAlbums) ALBUM_BOOST else 0.0
                AutoRanked(facts, base + boost, boost)
            }
            .sortedWith(compareByDescending<AutoRanked> { it.score }.thenBy { it.facts.canonicalId })
            .toList()
    }

    /** The plan of one picked song (its instrumental, the only thing the cloud makes), for the estimate. */
    fun planFor(facts: AutoCandidateFacts): CloudSongPlan = CloudSongPlan(
        facts.songId, listOf(CloudTask.INSTRUMENTAL), facts.isStreamed, facts.durationMs,
    )

    /**
     * The songs for one run, best first: only ones whose audio is already on the phone and that aren't resting,
     * as many as the day's cap, the batch size and both budgets (the automatic share and the month's shared cap) allow.
     * A song that doesn't fit is skipped, not the end of the list: a shorter one further down may.
     */
    fun select(ranked: List<AutoRanked>, state: AutoSelectionState): AutoSelection {
        val limit = min(state.batchLimit, state.dailyRemaining)
        val picks = mutableListOf<AutoRanked>()
        val plans = mutableListOf<CloudSongPlan>()
        var estimate = 0L
        for (candidate in ranked) {
            if (picks.size >= limit) break
            val facts = candidate.facts
            if (!facts.audioCached || state.isCoolingDown(facts.songId)) continue
            val withThis = plans + planFor(facts)
            val total = CloudCost.estimateMicroUsd(withThis, state.quality, state.pricePerSecondMicroUsd, state.parallelWorkers)
            if (!CloudBudget.allowsAuto(total, state.autoBudgetMicroUsd, state.autoCommittedMicroUsd, state.capRemainingMicroUsd)) continue
            plans += planFor(facts)
            picks += candidate
            estimate = total
        }
        return AutoSelection(picks, estimate)
    }

    // ─── What the settings screen says ──────────────────────────────────────────────────────

    /**
     * "About N songs a month" for a monthly budget: a typical 4-minute song's warm GPU seconds plus its share of the
     * cold starts of a batch of [BATCH_SIZE] (one for each of the [parallelWorkers] it wakes, [CloudCost.coldStarts]),
     * at the default GPU price. An estimate from the bench numbers.
     */
    fun songsPerMonth(budgetMicroUsd: Long, parallelWorkers: Int): Int {
        val coldSeconds = CloudCost.COLD_START_SECONDS * CloudCost.coldStarts(BATCH_SIZE, parallelWorkers)
        val seconds = CloudCost.WARM_SECONDS_STANDARD + coldSeconds / BATCH_SIZE
        val perSong = seconds * CloudCost.DEFAULT_PRICE_PER_SECOND_MICRO_USD
        if (perSong <= 0 || budgetMicroUsd <= 0) return 0
        return (budgetMicroUsd / perSong).toInt()
    }

    /** Under the switch when Prepare songs ahead is on but Cloud processing, which it runs on, is off: nothing would happen. */
    const val CLOUD_OFF_LINE = "Cloud processing is off"

    /**
     * The line under the switch: "3 songs today · $0.12 of $1.00 this month", or what it is waiting for ("Waiting for
     * Wi-Fi"). Empty while the feature is off.
     */
    fun statusLine(enabled: Boolean, todayCount: Int, monthSpentMicroUsd: Long, budgetMicroUsd: Long, waitingFor: String?): String {
        if (!enabled) return ""
        if (waitingFor != null) return waitingFor
        val songs = when (todayCount) {
            0 -> "No songs yet today"
            1 -> "1 song today"
            else -> "$todayCount songs today"
        }
        return "$songs · ${CloudCost.format(monthSpentMicroUsd)} of ${CloudCost.format(budgetMicroUsd)} this month"
    }

    /** One line of the developer's "Why these songs" list. */
    fun whyLine(ranked: AutoRanked, nowMs: Long): String {
        val facts = ranked.facts
        val reasons = mutableListOf<String>()
        if (facts.favorite) reasons += "a favorite"
        if (facts.playCount > 0) reasons += "played ${facts.playCount} time${if (facts.playCount == 1) "" else "s"}"
        if (facts.lastPlayedMs > 0 && daysSince(facts.lastPlayedMs, nowMs) <= PLAY_HALF_LIFE_DAYS) reasons += "played lately"
        if (facts.dateAddedMs > 0 && daysSince(facts.dateAddedMs, nowMs) <= ADD_HALF_LIFE_DAYS) reasons += "added lately"
        if (facts.neighbourBoost > 0) reasons += "next to songs you play"
        if (ranked.albumBoost > 0) reasons += "on an album you play"
        val name = listOf(facts.title, facts.artist).filter { it.isNotBlank() }.joinToString(" – ").ifBlank { facts.songId }
        val score = "%.1f".format(java.util.Locale.US, ranked.score)
        return "$name: ${reasons.joinToString(", ").ifBlank { "worth a try" }} (score $score)"
    }
}

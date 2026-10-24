package com.theveloper.pixelplay.data.ai.claude.taizo

import com.theveloper.pixelplay.data.history.ArtistInsight
import com.theveloper.pixelplay.data.history.HistoryText
import com.theveloper.pixelplay.data.history.InsightPeriod
import com.theveloper.pixelplay.data.history.ListeningHistoryWeights
import com.theveloper.pixelplay.data.history.TrackInsight
import com.theveloper.pixelplay.data.recommendation.TasteAdjustment
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentMath
import com.theveloper.pixelplay.data.recommendation.TasteKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The period of `get_taste_profile`. */
enum class TastePeriod(val key: String, val insight: InsightPeriod) {
    MONTH("month", InsightPeriod.LAST_4_WEEKS),
    SIX_MONTHS("six_months", InsightPeriod.LAST_6_MONTHS),
    YEAR("year", InsightPeriod.LAST_YEAR),
    ALL_TIME("all_time", InsightPeriod.ALL_TIME);

    companion object {
        fun fromKey(key: String?): TastePeriod = entries.firstOrNull { it.key == key } ?: SIX_MONTHS
    }
}

/** How a listener's plays of one artist ended (all time): this app's own feedback plus imported history. */
data class ArtistOutcome(val key: String, val name: String, val plays: Int, val completions: Int, val earlySkips: Int)

/** Everything the profile is made of, already read from the stores. */
data class TasteProfileInput(
    val period: TastePeriod,
    val nowMs: Long,
    val zone: ZoneId,
    val librarySongs: Int,
    val appPlays: Int,
    val importedPlays: Int,
    val spotifyPlays: Int,
    val youTubePlays: Int,
    val artists: List<ArtistInsight>,
    val tracks: List<TrackInsight>,
    val outcomes: List<ArtistOutcome>,
    val spotifyTopArtistsRecent: List<String> = emptyList(),
    val spotifyTopArtistsLongTerm: List<String> = emptyList(),
    val followedArtists: List<String> = emptyList(),
    val followedCount: Int = 0,
    val spotifyGenres: List<Pair<String, Double>> = emptyList(),
    val libraryGenres: List<Pair<String, Double>> = emptyList(),
    val decades: List<Pair<String, Double>> = emptyList(),
    /** Times plays really happened (this app's, and Spotify's); YouTube Music's are approximate and left out. */
    val playTimes: List<Long> = emptyList(),
    val adjustments: List<TasteAdjustment> = emptyList(),
)

/**
 * Builds `get_taste_profile` and the short digest in the chat context, from numbers alone (TasteProfileBuilderTest):
 * nothing here is written by a model, and a dimension the data does not cover is left out, never guessed.
 */
object TasteProfileBuilder {
    const val TOP_ARTISTS = 15
    const val TOP_TRACKS = 20
    const val TOP_GENRES = 8
    const val DIGEST_RECENT = 10

    /** A part-of-day or weekday split needs at least this many plays with a real time. */
    const val MIN_TIMED_PLAYS = 30

    /** An artist needs this many plays before its skip rate says something. */
    const val MIN_PLAYS_FOR_SKIP_RATE = 5
    const val SKIPPED_ARTIST_RATE = 0.3

    private val NOT_KNOWN = listOf("language", "energy", "mood")

    fun confidenceLevel(totalPlays: Int): String = when {
        totalPlays <= 0 -> "none"
        totalPlays < 50 -> "low"
        totalPlays < 300 -> "medium"
        else -> "high"
    }

    fun build(input: TasteProfileInput): JsonObject {
        val totalPlays = input.appPlays + input.importedPlays
        val outcomeByArtist = input.outcomes.associateBy { it.key }
        val artistTotal = input.artists.sumOf { it.score }
        val trackTotal = input.tracks.sumOf { it.score }
        val completions = input.outcomes.sumOf { it.completions }
        val skips = input.outcomes.sumOf { it.earlySkips }
        val sessions = input.outcomes.sumOf { it.plays }

        return buildJsonObject {
            put("period", input.period.key)
            putJsonObject("confidence") {
                put("level", confidenceLevel(totalPlays))
                put("total_plays", totalPlays)
                put("plays_in_period_top_artists", input.artists.sumOf { it.plays })
                put("library_songs", input.librarySongs)
                put("note", confidenceNote(totalPlays))
            }
            putJsonObject("sources") {
                put("spotify_weight", ListeningHistoryWeights.SPOTIFY)
                put("youtube_music_weight", ListeningHistoryWeights.YOUTUBE_MUSIC)
                put("pixlaudio_plays", input.appPlays)
                put("spotify_plays", input.spotifyPlays)
                put("youtube_music_plays", input.youTubePlays)
                put("note", "Counts are weighted by source (a YouTube Music play counts about a third of a Spotify or PixlAudio play) and recent plays count more.")
            }
            putJsonArray("top_artists") {
                input.artists.take(TOP_ARTISTS).forEachIndexed { index, artist ->
                    add(buildJsonObject {
                        put("rank", index + 1)
                        put("name", artist.name)
                        put("plays", artist.plays)
                        put("share_percent", percent(artist.score, artistTotal))
                        outcomeByArtist[HistoryText.artistKey(artist.name)]?.let { outcome ->
                            if (outcome.plays > 0) {
                                put("finished", outcome.completions)
                                put("skipped_early", outcome.earlySkips)
                                put("skip_rate_percent", percent(outcome.earlySkips.toDouble(), outcome.plays.toDouble()))
                            }
                        }
                    })
                }
            }
            putJsonArray("top_tracks") {
                input.tracks.take(TOP_TRACKS).forEachIndexed { index, track ->
                    add(buildJsonObject {
                        put("rank", index + 1)
                        track.songId?.let { put("id", it) }
                        put("title", track.title)
                        put("artist", track.artist)
                        put("plays", track.plays)
                        put("share_percent", percent(track.score, trackTotal))
                    })
                }
            }
            putJsonObject("listening_outcomes") {
                put("scope", "all time: this app's feedback plus imported history")
                put("finished", completions)
                put("skipped_early", skips)
                put("plays_counted", sessions)
                put("skip_rate_percent", percent(skips.toDouble(), sessions.toDouble()))
            }
            putJsonArray("skipped_artists") {
                skippedArtists(input.outcomes).forEach { outcome ->
                    add(buildJsonObject {
                        put("name", outcome.name)
                        put("skipped_early", outcome.earlySkips)
                        put("plays", outcome.plays)
                        put("skip_rate_percent", percent(outcome.earlySkips.toDouble(), outcome.plays.toDouble()))
                    })
                }
            }
            putJsonObject("spotify") {
                putJsonArray("top_artists_recent") { input.spotifyTopArtistsRecent.forEach { add(it) } }
                putJsonArray("top_artists_long_term") { input.spotifyTopArtistsLongTerm.forEach { add(it) } }
                putJsonArray("followed_artists_sample") { input.followedArtists.forEach { add(it) } }
                put("followed_artists_total", input.followedCount)
            }
            shares(input.libraryGenres, TOP_GENRES, "genre").takeIf { it.isNotEmpty() }?.let { put("genres_from_library_plays", it) }
            shares(input.spotifyGenres, TOP_GENRES, "genre").takeIf { it.isNotEmpty() }?.let { put("genres_from_spotify", it) }
            shares(input.decades, 8, "decade").takeIf { it.isNotEmpty() }?.let { put("release_decades", it) }
            val times = listeningTimes(input)
            if (times != null) {
                put("listening_times", times)
            } else {
                put("listening_times_note", "Not enough plays with a real timestamp to say when you listen.")
            }
            putJsonArray("not_known") { NOT_KNOWN.forEach { add(it) } }
            putJsonArray("adjustments") { input.adjustments.forEach { add(adjustmentJson(it, input.nowMs)) } }
        }
    }

    /** A line or two for the cached chat context: enough to ground the first answers, the tools give the rest. */
    fun digest(input: TasteProfileInput, recentPlays: List<String>): String {
        val totalPlays = input.appPlays + input.importedPlays
        return buildString {
            append("<taste_digest period=\"").append(input.period.key).append("\" confidence=\"")
                .append(confidenceLevel(totalPlays)).append("\" total_plays=\"").append(totalPlays).append("\">\n")
            if (input.artists.isNotEmpty()) {
                append("Top artists: ").append(input.artists.take(TOP_ARTISTS).joinToString(", ") { "${it.name} (${it.plays})" }).append('\n')
            }
            val genres = (input.libraryGenres.take(5).map { it.first } + input.spotifyGenres.take(5).map { it.first })
                .distinct().take(TOP_GENRES)
            if (genres.isNotEmpty()) append("Top genres: ").append(genres.joinToString(", ")).append('\n')
            if (recentPlays.isNotEmpty()) {
                append("Last plays (newest first): ").append(recentPlays.take(DIGEST_RECENT).joinToString("; ")).append('\n')
            }
            val skipped = skippedArtists(input.outcomes).take(5)
            if (skipped.isNotEmpty()) {
                append("Often skipped: ").append(skipped.joinToString(", ") { "${it.name} (${it.earlySkips} of ${it.plays})" }).append('\n')
            }
            if (input.adjustments.isNotEmpty()) {
                append("Active taste changes: ").append(
                    input.adjustments.take(15).joinToString("; ") { a ->
                        "${TasteAdjustmentMath.describe(a.kind, a.strength)} ${a.label} [${a.target.key}, id ${a.id}]"
                    }
                ).append('\n')
            }
            append("Weights: Spotify ").append(ListeningHistoryWeights.SPOTIFY).append(", YouTube Music ")
                .append(ListeningHistoryWeights.YOUTUBE_MUSIC).append(".\n")
            append(confidenceNote(totalPlays)).append('\n')
            append("</taste_digest>")
        }
    }

    private fun confidenceNote(totalPlays: Int): String = when (confidenceLevel(totalPlays)) {
        "none" -> "No plays are known yet, so there is no taste to read: say so instead of guessing."
        "low" -> "Only $totalPlays plays are known: call anything drawn from them a first impression, not a pattern."
        "medium" -> "Based on $totalPlays plays: solid for the top artists, thinner for small genres."
        else -> "Based on $totalPlays plays: a reliable picture."
    }

    /** Artists with enough plays whose early-skip rate is high, most skips first. */
    fun skippedArtists(outcomes: List<ArtistOutcome>, limit: Int = 8): List<ArtistOutcome> =
        outcomes.filter {
            it.plays >= MIN_PLAYS_FOR_SKIP_RATE && it.earlySkips.toDouble() / it.plays >= SKIPPED_ARTIST_RATE
        }.sortedWith(compareByDescending<ArtistOutcome> { it.earlySkips }.thenBy { it.name }).take(limit)

    private fun percent(part: Double, whole: Double): Double =
        if (whole <= 0.0) 0.0 else (part / whole * 1000.0).roundToInt() / 10.0

    private fun shares(values: List<Pair<String, Double>>, limit: Int, label: String): JsonArray {
        val total = values.sumOf { it.second }
        if (total <= 0.0) return JsonArray(emptyList())
        return buildJsonArray {
            values.sortedByDescending { it.second }.take(limit).forEach { (name, weight) ->
                add(buildJsonObject {
                    put(label, name)
                    put("share_percent", percent(weight, total))
                })
            }
        }
    }

    /** Part of day, weekday and peak hour from plays with a real time; null below [MIN_TIMED_PLAYS]. */
    fun listeningTimes(input: TasteProfileInput): JsonObject? {
        val since = input.period.insight.sinceMs(input.nowMs)
        val times = input.playTimes.filter { it >= since }
        if (times.size < MIN_TIMED_PLAYS) return null
        val parts = linkedMapOf("morning (5-11)" to 0, "afternoon (12-16)" to 0, "evening (17-21)" to 0, "night (22-4)" to 0)
        val days = DayOfWeek.entries.associateWith { 0 }.toMutableMap()
        val hours = IntArray(24)
        for (time in times) {
            val at = Instant.ofEpochMilli(time).atZone(input.zone)
            val hour = at.hour
            hours[hour]++
            days[at.dayOfWeek] = (days[at.dayOfWeek] ?: 0) + 1
            val part = when (hour) {
                in 5..11 -> "morning (5-11)"
                in 12..16 -> "afternoon (12-16)"
                in 17..21 -> "evening (17-21)"
                else -> "night (22-4)"
            }
            parts[part] = (parts[part] ?: 0) + 1
        }
        val total = times.size.toDouble()
        val peakHour = hours.indices.maxByOrNull { hours[it] } ?: 0
        return buildJsonObject {
            put("plays_with_time", times.size)
            putJsonObject("part_of_day_percent") { parts.forEach { (name, count) -> put(name, percent(count.toDouble(), total)) } }
            putJsonObject("weekday_percent") {
                DayOfWeek.entries.forEach { day ->
                    put(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH), percent((days[day] ?: 0).toDouble(), total))
                }
            }
            put("peak_hour", peakHour)
            put("peak_hour_percent", percent(hours[peakHour].toDouble(), total))
            put("note", "Local time; YouTube Music plays are left out because their times are approximate.")
        }
    }

    private fun adjustmentJson(a: TasteAdjustment, nowMs: Long): JsonObject = buildJsonObject {
        put("id", a.id)
        put("action", a.kind.key)
        put("target_type", a.target.key)
        put("target", a.label)
        if (a.kind == TasteKind.BOOST || a.kind == TasteKind.REDUCE) put("strength", a.strength)
        if (a.reason.isNotBlank()) put("reason", a.reason)
        put("set_by", a.setBy.key)
        put("age_days", ((nowMs - a.createdAtMs).coerceAtLeast(0L) / 86_400_000L).toInt())
    }
}

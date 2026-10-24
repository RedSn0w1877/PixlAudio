package com.theveloper.pixelplay.data.history

import java.text.Normalizer
import java.util.Locale
import kotlin.math.pow

/**
 * Imported listening history: plays that happened outside PixlAudio (on Spotify or YouTube Music),
 * brought in automatically (Spotify Web API, YouTube Music history page) or from a data-export file.
 *
 * Everything here is pure Kotlin so the weighting, decay and keys are unit tested. The same constants
 * are used by the iOS app.
 */
object ListeningHistoryWeights {
    /** A play on Spotify counts fully: the algorithm leans on Spotify. */
    const val SPOTIFY = 1.0

    /** A play on YouTube Music counts about a third as much. */
    const val YOUTUBE_MUSIC = 0.35

    /** Imported plays lose half their weight every 180 days. */
    const val HALF_LIFE_DAYS = 180.0

    /** Shorter than this and ended by the listener: an early skip (also Spotify's own "stream" threshold). */
    const val SKIP_THRESHOLD_MS = 30_000L

    /** At least this share of the track heard: a completed play. */
    const val COMPLETION_FRACTION = 0.8

    /** Below this nothing was really heard (loading failures, accidental taps), like the live learner. */
    const val MIN_HEARD_MS = 5_000L

    private const val DAY_MS = 86_400_000.0

    /** 1.0 for a play right now, 0.5 at [HALF_LIFE_DAYS] old, 0.25 at twice that. Future timestamps count as now. */
    fun decay(playedAtMs: Long, nowMs: Long): Double {
        val ageDays = (nowMs - playedAtMs).coerceAtLeast(0L) / DAY_MS
        return 0.5.pow(ageDays / HALF_LIFE_DAYS)
    }

    /** `reason_end` values that mean the listener moved on by hand. */
    private val SKIP_REASONS = setOf("fwdbtn", "backbtn", "clickrow")

    /** `reason_start` values that mean the listener picked this song themselves. */
    private val VOLUNTARY_STARTS = setOf("clickrow", "playbtn", "remote")

    fun isVoluntary(reasonStart: String?): Boolean = reasonStart != null && reasonStart in VOLUNTARY_STARTS

    /**
     * How one play ended, from what Spotify's extended export records.
     *
     * - `trackdone`, or at least [COMPLETION_FRACTION] of a known duration: completed.
     * - under [SKIP_THRESHOLD_MS] and Spotify says skipped / the listener pressed next, back or picked another
     *   row: an early skip. A short play with no reason at all (the basic export) also counts as a skip.
     * - anything else that was heard: partial.
     * - no `ms_played` at all (the Web API's recently played list, YouTube Music): unknown, which still counts as a play.
     */
    fun outcome(msPlayed: Long?, durationMs: Long?, skipped: Boolean?, reasonEnd: String?): PlayOutcome {
        if (msPlayed == null) return PlayOutcome.UNKNOWN
        if (reasonEnd == "trackdone") return PlayOutcome.COMPLETED
        if (durationMs != null && durationMs > 0 && msPlayed >= durationMs * COMPLETION_FRACTION) return PlayOutcome.COMPLETED
        if (msPlayed < SKIP_THRESHOLD_MS) {
            val noInfo = skipped == null && reasonEnd == null
            if (skipped == true || (reasonEnd != null && reasonEnd in SKIP_REASONS) || noInfo) return PlayOutcome.SKIPPED
        }
        return PlayOutcome.PARTIAL
    }
}

/** Stable codes: they are stored in the history database. */
enum class PlayOutcome(val code: Int) {
    UNKNOWN(0), COMPLETED(1), SKIPPED(2), PARTIAL(3);

    companion object {
        fun fromCode(code: Int): PlayOutcome = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Where a play came from. [key] is stored; [weight] is the source weight from [ListeningHistoryWeights]. */
enum class HistorySource(val key: String, val weight: Double, val isSpotify: Boolean) {
    SPOTIFY_API("spotify_api", ListeningHistoryWeights.SPOTIFY, true),
    SPOTIFY_EXPORT("spotify_export", ListeningHistoryWeights.SPOTIFY, true),
    YTMUSIC("ytmusic", ListeningHistoryWeights.YOUTUBE_MUSIC, false),
    TAKEOUT("takeout", ListeningHistoryWeights.YOUTUBE_MUSIC, false);

    companion object {
        fun fromKey(key: String): HistorySource? = entries.firstOrNull { it.key == key }
    }
}

/** The file/endpoint shape a play was read from; used for cross-source de-duplication. Stored. */
object HistoryFormat {
    const val SPOTIFY_EXTENDED = "extended"
    const val SPOTIFY_BASIC = "basic"
    const val SPOTIFY_API = "api"
    const val TAKEOUT = "takeout"
    const val YTMUSIC_API = "ytm_api"
}

/** One play, as parsed, before it is stored. */
data class ImportedPlay(
    val source: HistorySource,
    val format: String,
    /** When the play ended (Spotify's `ts` / `played_at`), or when it was watched (Takeout), epoch ms. */
    val playedAtMs: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String? = null,
    val spotifyTrackId: String? = null,
    val videoId: String? = null,
    val msPlayed: Long? = null,
    val durationMs: Long? = null,
    val skipped: Boolean? = null,
    val reasonStart: String? = null,
    val reasonEnd: String? = null,
    /** Set by sources whose timestamps are approximate (YouTube Music's "Today"/"Yesterday" shelves). */
    val dedupeKeyOverride: String? = null,
) {
    val outcome: PlayOutcome get() = ListeningHistoryWeights.outcome(msPlayed, durationMs, skipped, reasonEnd)

    val artistKey: String get() = HistoryText.artistKey(artistName)

    /** What makes two plays "the same song": the Spotify id, else the video id, else artist + cleaned title. */
    val trackKey: String
        get() = when {
            !spotifyTrackId.isNullOrBlank() -> "sp:$spotifyTrackId"
            !videoId.isNullOrBlank() -> "yt:$videoId"
            else -> "t:${HistoryText.recordingKey(artistName, trackName)}"
        }

    /**
     * Re-importing the same file, or syncing the same recent plays twice, must not count twice: a play is
     * identified by its track and its timestamp (to the second). Spotify's export and its Web API share the
     * `sp|` prefix so the same play arriving both ways lands on one key when the seconds agree.
     */
    val dedupeKey: String
        get() = dedupeKeyOverride ?: when (source) {
            HistorySource.SPOTIFY_API, HistorySource.SPOTIFY_EXPORT -> "sp|$trackKey|${playedAtMs / 1000}"
            HistorySource.TAKEOUT, HistorySource.YTMUSIC -> "yt|$trackKey|${playedAtMs / 1000}"
        }

    /** Counts towards "top" lists and play counts: heard and not an early skip. */
    val qualified: Boolean
        get() {
            val heard = msPlayed == null || msPlayed >= ListeningHistoryWeights.MIN_HEARD_MS
            return heard && outcome != PlayOutcome.SKIPPED
        }

    val voluntary: Boolean get() = ListeningHistoryWeights.isVoluntary(reasonStart)
}

/** Text keys shared by the importer, the library matcher and the insights API. */
object HistoryText {
    private val WHITESPACE = Regex("\\s+")

    /** Bracketed decorations YouTube and Spotify add to titles that are not part of the song's name. */
    private val DECORATION = Regex(
        """\s*[(\[](?:[^)\]]*\b(?:official|video|audio|lyrics?|lyric video|visuali[sz]er|remaster(?:ed)?|feat\.?|ft\.?|featuring|hd|hq|4k|mv|m/v|explicit|clean|live at)\b[^)\]]*)[)\]]""",
        RegexOption.IGNORE_CASE
    )

    /** " - Remastered 2011", " - 2009 Remaster", " - Radio Edit" style suffixes. */
    private val DASH_SUFFIX = Regex("""\s+-\s+(?:\d{4}\s+)?(?:remaster(?:ed)?|radio edit|single version|mono|stereo)\b.*$""", RegexOption.IGNORE_CASE)

    /** NFKC, lower case, single spaces, trimmed: the same folding MusicRecommendationEngine uses for artists. */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    /** "Artist - Topic" (YouTube's auto-generated music channels) is just "Artist". */
    fun cleanArtist(raw: String): String = raw.trim().removeSuffix(" - Topic").trim()

    fun artistKey(artist: String): String = normalize(cleanArtist(artist))

    /**
     * Every credited artist of a library row, primary first. Library rows join Spotify's artist list with
     * ", ", so only commas split ("Simon & Garfunkel" stays one artist).
     */
    fun splitArtists(artist: String): List<String> =
        artist.split(',').map { artistKey(it) }.filter { it.isNotEmpty() }.distinct()
            .ifEmpty { listOfNotNull(artistKey(artist).takeIf { it.isNotEmpty() }) }

    fun cleanTitle(title: String, artist: String? = null): String {
        var value = title.trim()
        // A non-Topic YouTube upload is often titled "Artist - Song".
        if (artist != null) {
            val prefix = cleanArtist(artist)
            if (prefix.isNotEmpty() && value.startsWith("$prefix - ", ignoreCase = true)) {
                value = value.substring(prefix.length + 3)
            }
        }
        value = DECORATION.replace(value, "")
        value = DASH_SUFFIX.replace(value, "")
        return normalize(value)
    }

    /** "artist|title" with the primary artist and the cleaned title. */
    fun recordingKey(artist: String, title: String): String {
        val primary = splitArtists(artist).firstOrNull().orEmpty()
        return "$primary|${cleanTitle(title, artist)}"
    }
}

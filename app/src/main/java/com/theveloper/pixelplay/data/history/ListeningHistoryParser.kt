package com.theveloper.pixelplay.data.history

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Streams the listening-history files people download from Spotify and Google, without loading them whole
 * (an extended Spotify history is ten-plus files of ~12 MB each).
 *
 * Accepts the ZIP as downloaded, or any of the JSON files inside it. Every JSON entry is classified object by
 * object from its keys, so file names (which Google localises) do not matter:
 *
 *  - Spotify **extended** streaming history (`Streaming_History_Audio_*.json`, older `endsong_*.json`):
 *    `ts`, `ms_played`, `master_metadata_track_name`, `master_metadata_album_artist_name`,
 *    `master_metadata_album_album_name`, `spotify_track_uri`, `reason_start`, `reason_end`, `skipped`.
 *    Podcast and audiobook rows (no track name) are left out.
 *  - Spotify **basic** history (`StreamingHistory_music_*.json`): `endTime`, `artistName`, `trackName`, `msPlayed`.
 *  - Google Takeout `watch-history.json`: `header`, `title` ("Watched …"), `titleUrl`, `subtitles[0].name`, `time`.
 *    Only YouTube Music entries are kept, plus plain-YouTube entries from auto-generated music channels
 *    ("Artist - Topic") and VEVO channels, the only music that is trivially recognisable there.
 */
object ListeningHistoryParser {

    class Stats {
        /** JSON documents read (inside a ZIP, one per entry). */
        var files = 0
        /** Documents that held at least one recognised play. */
        var recognizedFiles = 0
        /** Plays handed to the sink. */
        var plays = 0
        /** Entries that were not music plays (podcasts, plain YouTube videos, searches). */
        var ignored = 0
        /** Documents that were not JSON arrays or were cut short. */
        var failedFiles = 0
        /** The Takeout was exported as HTML rather than JSON (only JSON can be read). */
        var sawHtmlHistory = false
    }

    /** Reads a ZIP or a JSON document from [input]. [onFile] is told the name of each file as it starts. */
    suspend fun parse(
        input: InputStream,
        displayName: String?,
        stats: Stats,
        onFile: (String) -> Unit = {},
        onPlay: suspend (ImportedPlay) -> Unit
    ) {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(8)
        val magic = ByteArray(4)
        val read = buffered.read(magic)
        buffered.reset()
        val isZip = read == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
            magic[2] == 3.toByte() && magic[3] == 4.toByte()
        if (isZip) {
            parseZip(buffered, stats, onFile, onPlay)
        } else {
            val name = displayName.orEmpty()
            if (name.lowercase(Locale.ROOT).endsWith(".html")) {
                stats.sawHtmlHistory = true
                return
            }
            onFile(name)
            parseJsonDocument(buffered, stats, onPlay)
        }
    }

    private suspend fun parseZip(
        input: InputStream,
        stats: Stats,
        onFile: (String) -> Unit,
        onPlay: suspend (ImportedPlay) -> Unit
    ) {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = entry.name
            val lower = name.lowercase(Locale.ROOT)
            if (lower.endsWith(".html") && lower.contains("history")) stats.sawHtmlHistory = true
            if (!lower.endsWith(".json") || isIgnoredEntry(lower)) continue
            onFile(name.substringAfterLast('/'))
            // The reader must not close the ZIP: the next entry is read from the same stream.
            parseJsonDocument(NonClosingInputStream(zip), stats, onPlay)
        }
    }

    /** ZIP entries that are never plays (Spotify's video history, podcasts, searches, account data). */
    private fun isIgnoredEntry(lowerName: String): Boolean {
        val file = lowerName.substringAfterLast('/')
        return IGNORED_ENTRY_WORDS.any { file.contains(it) }
    }

    private val IGNORED_ENTRY_WORDS = listOf(
        "video", "podcast", "search", "playlist", "library", "payment", "userdata", "identity",
        "inferences", "marquee", "wrapped", "follow", "comment", "subscription"
    )

    private suspend fun parseJsonDocument(input: InputStream, stats: Stats, onPlay: suspend (ImportedPlay) -> Unit) {
        stats.files++
        var recognized = false
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        try {
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                stats.failedFiles++
                return
            }
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue()
                    continue
                }
                val play = classify(readFlatObject(reader))
                if (play == null) {
                    stats.ignored++
                } else {
                    recognized = true
                    stats.plays++
                    onPlay(play)
                }
            }
            reader.endArray()
        } catch (e: Exception) {
            // A cancelled import stops; a damaged file only loses the rest of itself.
            if (e is CancellationException) throw e
            stats.failedFiles++
        } finally {
            if (recognized) stats.recognizedFiles++
        }
    }

    /** One entry's top-level primitives, plus the first `subtitles[].name` (Takeout's channel). */
    private fun readFlatObject(reader: JsonReader): Map<String, Any?> {
        val map = HashMap<String, Any?>(24)
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> {
                    map[name] = reader.nextString()
                }
                JsonToken.BOOLEAN -> {
                    map[name] = reader.nextBoolean()
                }
                JsonToken.NULL -> {
                    reader.nextNull()
                    map[name] = null
                }
                JsonToken.BEGIN_ARRAY -> {
                    if (name == "subtitles") map[name] = readFirstSubtitleName(reader) else reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return map
    }

    private fun readFirstSubtitleName(reader: JsonReader): String? {
        var first: String? = null
        reader.beginArray()
        while (reader.hasNext()) {
            if (first == null && reader.peek() == JsonToken.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (key == "name" && reader.peek() == JsonToken.STRING) first = reader.nextString() else reader.skipValue()
                }
                reader.endObject()
            } else {
                reader.skipValue()
            }
        }
        reader.endArray()
        return first
    }

    /** Recognises one entry, or returns null when it is not a music play. */
    internal fun classify(fields: Map<String, Any?>): ImportedPlay? = when {
        fields.containsKey("ts") && fields.containsKey("ms_played") -> spotifyExtended(fields)
        fields.containsKey("endTime") && fields.containsKey("msPlayed") -> spotifyBasic(fields)
        fields.containsKey("header") && fields.containsKey("time") -> takeout(fields)
        else -> null
    }

    private fun spotifyExtended(f: Map<String, Any?>): ImportedPlay? {
        val track = f.str("master_metadata_track_name") ?: return null // podcast / audiobook row
        val artist = f.str("master_metadata_album_artist_name") ?: return null
        val playedAt = parseIso(f.str("ts")) ?: return null
        val id = f.str("spotify_track_uri")
            ?.takeIf { it.startsWith(TRACK_URI_PREFIX) }
            ?.removePrefix(TRACK_URI_PREFIX)
            ?.takeIf { SPOTIFY_ID.matches(it) }
        return ImportedPlay(
            source = HistorySource.SPOTIFY_EXPORT,
            format = HistoryFormat.SPOTIFY_EXTENDED,
            playedAtMs = playedAt,
            trackName = track,
            artistName = artist,
            albumName = f.str("master_metadata_album_album_name"),
            spotifyTrackId = id,
            msPlayed = f.long("ms_played")?.coerceAtLeast(0L),
            skipped = f.bool("skipped"),
            reasonStart = f.str("reason_start"),
            reasonEnd = f.str("reason_end"),
        )
    }

    private fun spotifyBasic(f: Map<String, Any?>): ImportedPlay? {
        val track = f.str("trackName") ?: return null
        val artist = f.str("artistName") ?: return null
        if (track == "Unknown Track" && artist == "Unknown Artist") return null
        val endedAt = parseBasicTime(f.str("endTime")) ?: return null
        return ImportedPlay(
            source = HistorySource.SPOTIFY_EXPORT,
            format = HistoryFormat.SPOTIFY_BASIC,
            playedAtMs = endedAt,
            trackName = track,
            artistName = artist,
            msPlayed = f.long("msPlayed")?.coerceAtLeast(0L),
        )
    }

    private fun takeout(f: Map<String, Any?>): ImportedPlay? {
        val header = f.str("header") ?: return null
        val rawChannel = f.str("subtitles") ?: return null
        val topic = rawChannel.endsWith(TOPIC_SUFFIX)
        val vevo = rawChannel.contains("VEVO")
        val music = header == "YouTube Music" || (header == "YouTube" && (topic || vevo))
        if (!music) return null
        val videoId = f.str("titleUrl")?.let { VIDEO_ID.find(it)?.groupValues?.get(1) } ?: return null
        var title = f.str("title") ?: return null
        WATCHED_PREFIXES.firstOrNull { title.startsWith(it) }?.let { title = title.removePrefix(it) }
        title = title.trim()
        if (title.isEmpty() || title.startsWith("http")) return null // a removed video
        val playedAt = parseIso(f.str("time")) ?: return null
        var artist = HistoryText.cleanArtist(rawChannel)
        if (vevo && !topic && title.contains(" - ")) {
            // "TaylorSwiftVEVO" is not an artist name; the upload title is "Artist - Song (Official Video)".
            artist = title.substringBefore(" - ").trim()
            title = title.substringAfter(" - ").trim()
        }
        if (artist.isEmpty() || title.isEmpty()) return null
        return ImportedPlay(
            source = HistorySource.TAKEOUT,
            format = HistoryFormat.TAKEOUT,
            playedAtMs = playedAt,
            trackName = title,
            artistName = artist,
            videoId = videoId,
        )
    }

    // ─── Field helpers ──────────────────────────────────────────────────

    private fun Map<String, Any?>.str(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.long(key: String): Long? = when (val value = this[key]) {
        is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
        is Number -> value.toLong()
        else -> null
    }

    private fun Map<String, Any?>.bool(key: String): Boolean? = when (val value = this[key]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }

    internal fun parseIso(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    }

    /** The basic export's `endTime` is UTC to the minute ("2023-04-01 18:22"). */
    internal fun parseBasicTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        for (pattern in BASIC_TIME_PATTERNS) {
            val parsed = runCatching { LocalDateTime.parse(value, pattern).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            if (parsed != null) return parsed
        }
        return parseIso(value)
    }

    private val BASIC_TIME_PATTERNS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
    )

    private const val TRACK_URI_PREFIX = "spotify:track:"
    private const val TOPIC_SUFFIX = " - Topic"
    private val SPOTIFY_ID = Regex("^[A-Za-z0-9]{22}$")
    private val VIDEO_ID = Regex("""[?&]v=([A-Za-z0-9_-]{11})""")

    /** "Watched " in the languages Takeout is most often exported in; an unknown one leaves the title as is. */
    private val WATCHED_PREFIXES = listOf(
        "Watched ", "Has visto ", "Viste ", "Vous avez regardé ", "Hai guardato ", "Você assistiu a ", "Assistiu a ",
    )

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }
}

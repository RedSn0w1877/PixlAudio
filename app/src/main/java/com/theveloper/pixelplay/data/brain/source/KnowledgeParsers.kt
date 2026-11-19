package com.theveloper.pixelplay.data.brain.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Response text to plain rows, for the five open-data sources (docs/brain-spec.md, 3.3 B). They take `JsonElement`s so they run in
 * plain JVM tests, and they are fixture-tested against responses cut from the real services
 * (`app/src/test/resources/knowledge/`). A parser never throws: a response it cannot read is "no data" (null or empty), because
 * these services change their layouts and a parse error must not take the refresh down.
 */
object KnowledgeParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(text: String?): JsonElement? =
        if (text.isNullOrBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    // ── Deezer ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The `error` object of a Deezer answer, or 0. Deezer sends errors with HTTP 200. */
    private fun deezerError(root: JsonObject): Int = root["error"].obj()?.get("code").int() ?: 0

    fun deezerSearch(text: String?): DeezerReply<List<DeezerTrackRef>> {
        val root = parse(text).obj() ?: return DeezerReply(null, 0)
        val err = deezerError(root)
        if (err != 0) return DeezerReply(null, err)
        val rows = root["data"].arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val id = o["id"].long() ?: return@mapNotNull null
            DeezerTrackRef(
                id = id,
                title = o["title"].str().orEmpty(),
                artistName = o["artist"].obj()?.get("name").str().orEmpty(),
                artistId = o["artist"].obj()?.get("id").long() ?: 0L,
                durationSec = o["duration"].int() ?: 0,
                rank = o["rank"].long() ?: 0L,
                albumId = o["album"].obj()?.get("id").long() ?: 0L,
                isrc = o["isrc"].str()?.takeIf { it.isNotBlank() },
            )
        }
        return DeezerReply(rows, 0)
    }

    fun deezerTrack(text: String?): DeezerReply<DeezerTrack> {
        val o = parse(text).obj() ?: return DeezerReply(null, 0)
        val err = deezerError(o)
        if (err != 0) return DeezerReply(null, err)
        val id = o["id"].long() ?: return DeezerReply(null, 0)
        return DeezerReply(
            DeezerTrack(
                id = id,
                isrc = o["isrc"].str()?.takeIf { it.isNotBlank() },
                title = o["title"].str().orEmpty(),
                durationSec = o["duration"].int() ?: 0,
                rank = o["rank"].long() ?: 0L,
                releaseDate = o["release_date"].str()?.takeIf { it.isNotBlank() },
                bpm = o["bpm"].dbl() ?: 0.0,
                gain = o["gain"].dbl(),
                explicit = o["explicit_lyrics"].bool(),
                albumId = o["album"].obj()?.get("id").long() ?: 0L,
                artistId = o["artist"].obj()?.get("id").long() ?: 0L,
                artistName = o["artist"].obj()?.get("name").str().orEmpty(),
            ),
        )
    }

    fun deezerAlbum(text: String?): DeezerReply<DeezerAlbum> {
        val o = parse(text).obj() ?: return DeezerReply(null, 0)
        val err = deezerError(o)
        if (err != 0) return DeezerReply(null, err)
        val id = o["id"].long() ?: return DeezerReply(null, 0)
        val genres = o["genres"].obj()?.get("data").arr().orEmpty().mapNotNull { it.obj()?.get("name").str() }
        return DeezerReply(DeezerAlbum(id, genres, o["release_date"].str()?.takeIf { it.isNotBlank() }))
    }

    // ── MusicBrainz ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun votes(e: JsonElement?): List<MbGenreVote> = e.arr().orEmpty().mapNotNull {
        val o = it.obj() ?: return@mapNotNull null
        MbGenreVote(o["name"].str() ?: return@mapNotNull null, o["id"].str().orEmpty(), o["count"].int() ?: 0)
    }

    private fun mbHit(o: JsonObject): MbRecordingHit? {
        val id = o["id"].str() ?: return null
        val credits = o["artist-credit"].arr().orEmpty().mapNotNull { it.obj() }
        val names = ArrayList<String>()
        for (c in credits) {
            c["name"].str()?.let(names::add)
            c["artist"].obj()?.get("name").str()?.let(names::add)
        }
        return MbRecordingHit(
            mbid = id,
            score = o["score"].int() ?: 0,
            title = o["title"].str().orEmpty(),
            disambiguation = o["disambiguation"].str().orEmpty(),
            lengthMs = o["length"].int() ?: 0,
            artistNames = names.distinct(),
            artistMbid = credits.firstNotNullOfOrNull { it["artist"].obj()?.get("id").str() },
            firstReleaseDate = o["first-release-date"].str()?.takeIf { it.isNotBlank() },
            isrcs = o["isrcs"].arr().orEmpty().mapNotNull { it.str() },
        )
    }

    /** `GET /ws/2/recording?query=...` and `GET /ws/2/isrc/{ISRC}?inc=artists` (the latter lists `recordings`). */
    fun mbRecordings(text: String?): List<MbRecordingHit> =
        parse(text).obj()?.get("recordings").arr().orEmpty().mapNotNull { it.obj()?.let(::mbHit) }

    /** `GET /ws/2/recording/{mbid}?inc=genres+tags`. */
    fun mbRecording(text: String?): MbRecording? {
        val o = parse(text).obj() ?: return null
        val id = o["id"].str() ?: return null
        return MbRecording(id, o["title"].str().orEmpty(), o["first-release-date"].str()?.takeIf { it.isNotBlank() }, votes(o["genres"]), votes(o["tags"]))
    }

    /** `GET /ws/2/artist/{mbid}?inc=genres+tags`. */
    fun mbArtist(text: String?): MbArtist? {
        val o = parse(text).obj() ?: return null
        val id = o["id"].str() ?: return null
        val country = o["area"].obj()?.get("iso-3166-1-codes").arr()?.firstOrNull().str()
            ?: o["country"].str()
            ?: ""
        return MbArtist(id, o["name"].str().orEmpty(), country, votes(o["genres"]), votes(o["tags"]))
    }

    // ── ListenBrainz ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** `POST /1/metadata/recording/` with `{"recording_mbids": [...], "inc": "artist tag release"}`: a map by recording id. */
    fun lbRecordings(text: String?): Map<String, LbRecording> {
        val root = parse(text).obj() ?: return emptyMap()
        val out = LinkedHashMap<String, LbRecording>()
        for ((mbid, e) in root) {
            val o = e.obj() ?: continue
            val tags = ArrayList<LbTag>()
            val tagObj = o["tag"].obj()
            if (tagObj != null) {
                for ((scope, list) in tagObj) {
                    for (t in list.arr().orEmpty()) {
                        val to = t.obj() ?: continue
                        tags.add(LbTag(to["tag"].str() ?: continue, to["count"].int() ?: 0, to["genre_mbid"].str().orEmpty(), scope))
                    }
                }
            }
            val artist = o["artist"].obj()?.get("artists").arr()?.firstOrNull().obj()?.get("artist_mbid").str()
            val first = o["recording"].obj()?.get("first_release_date").str()?.takeIf { it.isNotBlank() }
            out[mbid] = LbRecording(mbid, tags, artist, first, o["release"].obj()?.get("year").int() ?: 0)
        }
        return out
    }

    /** `POST /1/popularity/recording`: a list; a recording nobody listened to has null counts. */
    fun lbPopularity(text: String?): Map<String, LbPopularity> {
        val out = LinkedHashMap<String, LbPopularity>()
        for (e in parse(text).arr().orEmpty()) {
            val o = e.obj() ?: continue
            val id = o["recording_mbid"].str() ?: continue
            out[id] = LbPopularity(id, o["total_listen_count"].long() ?: 0L, o["total_user_count"].long() ?: 0L)
        }
        return out
    }

    // ── Wikidata ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The SPARQL result of [WikidataSource]'s query: one binding per artist, genres joined by `|`. */
    fun wikidataArtists(text: String?): List<WdArtist> {
        val bindings = parse(text).obj()?.get("results").obj()?.get("bindings").arr().orEmpty()
        return bindings.mapNotNull { b ->
            val o = b.obj() ?: return@mapNotNull null
            fun v(k: String) = o[k].obj()?.get("value").str().orEmpty()
            val mbid = v("mbid").ifBlank { return@mapNotNull null }
            WdArtist(
                mbid = mbid,
                wikidataId = v("a").substringAfterLast('/'),
                genreMbids = v("gmbids").split('|').filter { it.isNotBlank() },
                genreLabels = v("glabels").split('|').filter { it.isNotBlank() },
            )
        }
    }

    // ── iTunes ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    fun itunes(text: String?): List<ITunesSong> =
        parse(text).obj()?.get("results").arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            if (o["kind"].str() != "song") return@mapNotNull null
            ITunesSong(
                trackName = o["trackName"].str() ?: return@mapNotNull null,
                artistName = o["artistName"].str().orEmpty(),
                durationMs = o["trackTimeMillis"].int() ?: 0,
                genre = o["primaryGenreName"].str().orEmpty(),
                releaseDate = o["releaseDate"].str(),
                explicit = when (o["trackExplicitness"].str()) { "explicit" -> true; "notExplicit" -> false; else -> null },
            )
        }
}

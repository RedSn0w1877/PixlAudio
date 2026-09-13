package com.theveloper.pixelplay.data.network.lyrics

import com.google.gson.JsonObject
import com.theveloper.pixelplay.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Public read-only endpoints. Search results must match recording metadata before fetching YRC. */
internal class NeteaseLyricsSource(baseClient: OkHttpClient,
    private val baseUrl: String = "https://music.163.com/api/") {
    private val client = baseClient.newBuilder().connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()

    suspend fun find(song: Song): Lyrics? = withTimeoutOrNull(10_000) {
        try {
            if (song.duration <= 0 || song.title.isBlank() || song.artist.isBlank()) return@withTimeoutOrNull null
            val search = get("search/get", mapOf("type" to "1", "offset" to "0", "limit" to "10",
                "s" to "${recordingTitle(song.title)} ${song.artist}")) ?: return@withTimeoutOrNull null
            // Some regional /web responses wrap an opaque result string. Treat that as unavailable.
            val result = search.get("result")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@withTimeoutOrNull null
            val matches = result.getAsJsonArray("songs")?.map { it.asJsonObject }.orEmpty()
                .filter { matchesRecording(song, it) }
                .sortedBy { abs(it.get("duration").asLong - song.duration) }
            for (track in matches.take(2)) {
                val data = get("song/lyric/v1", mapOf("id" to track.get("id").asString, "kv" to "0",
                    "yv" to "0", "rv" to "0", "tv" to "0")) ?: continue
                val raw = data.getAsJsonObject("yrc")?.get("lyric")?.asString ?: continue
                val doc = WordSyncTranspilers.yrc(raw, LyricsMetadata(song.title, song.artist, song.album,
                    source = "NetEase")) ?: continue
                if (doc.lines.none { it.syllables.isNotEmpty() } || doc.lines.any { it.endMs > song.duration + 1500 }) continue
                return@withTimeoutOrNull doc.toLyrics().copy(areFromRemote = true)
            }
            null
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    private suspend fun get(path: String, query: Map<String,String>): JsonObject? {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k,v) -> addQueryParameter(k,v) }
        }.build()
        return client.lyricsJson(url)?.takeIf { it.get("code")?.asInt == 200 }
    }

    companion object {
        private fun normalized(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        // Catalogs move featured credits between title and artist fields. Retain every
        // other qualifier (live, acoustic, remix) and still require album and duration.
        private val featuredCredit = Regex("""(?i)\s*[\(\[](?:(?:feat\.?|ft\.?|featuring|with)\s+)([^\)\]]+)[\)\]]""")
        private fun recordingTitle(value: String): String = value.replace(featuredCredit, "").trim()
        private fun matchesArtists(artist: String, candidates: List<String>): Boolean {
            val expected = normalized(artist)
            if (candidates.any { normalized(it) == expected }) return true
            if (normalized(candidates.joinToString(", ")) == expected) return true
            // Split only when every resulting credit is an exact catalog artist.
            val credits = artist.split(Regex("""\s*(?:,|;| feat\. | ft\. | featuring )\s*""", RegexOption.IGNORE_CASE))
            return credits.size > 1 && credits.all { credit -> candidates.any { normalized(it) == normalized(credit) } }
        }
        internal fun matchesRecording(song: Song, track: JsonObject): Boolean = try {
            normalized(recordingTitle(track.get("name").asString)) == normalized(recordingTitle(song.title)) &&
                matchesArtists(song.artist, track.getAsJsonArray("artists").map { it.asJsonObject.get("name").asString }) &&
                featuredCredit.findAll(song.title).all { credit -> matchesArtists(credit.groupValues[1],
                    track.getAsJsonArray("artists").map { it.asJsonObject.get("name").asString }) } &&
                abs(track.get("duration").asLong - song.duration) <= 1500 &&
                (song.album.isBlank() || normalized(track.getAsJsonObject("album").get("name").asString) == normalized(song.album))
        } catch (_: Exception) { false }
    }
}

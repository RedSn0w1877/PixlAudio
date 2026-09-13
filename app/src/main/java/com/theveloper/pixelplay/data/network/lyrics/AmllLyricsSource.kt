package com.theveloper.pixelplay.data.network.lyrics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.utils.LyricsUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Official keyless AMLL native API. Mirrors are not counted as independent catalogs. */
internal class AmllLyricsSource(private val baseClient: OkHttpClient) {
    private val client by lazy {
        baseClient.newBuilder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    }

    suspend fun find(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        try {
            song.spotifyId?.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }?.let { id ->
                val data = get("lyrics/get", mapOf("spotifyId" to id))
                if (data != null && strings(data, "spotifyIds").contains(id)) {
                    parse(data, song)?.let { return@withContext it }
                }
            }
            // Metadata endpoint has no recording duration: demand album as well as exact
            // title/artist to avoid silently choosing live/remix or unrelated recordings.
            if (song.album.isBlank()) return@withContext null
            val data = get("lyrics/search", mapOf("musicName" to song.title,
                "artistName" to song.artist, "pageSize" to "10")) ?: return@withContext null
            val matches = data.getAsJsonArray("items")?.mapNotNull { it as? JsonObject }
                ?.filter { matchesMetadata(song, strings(it, "musicNames"), strings(it, "artistNames"), strings(it, "albumNames")) }
                .orEmpty()
            if (matches.size != 1) return@withContext null
            val id = matches.single().get("id")?.asString ?: return@withContext null
            get("lyrics/get", mapOf("id" to id))?.let { parse(it, song) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    private suspend fun get(path: String, query: Map<String, String>): JsonObject? {
        currentCoroutineContext().ensureActive()
        val url = ("https://api.amll.dev/v1/" + path).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return client.lyricsJson(url)?.getAsJsonObject("data")
    }

    private fun parse(data: JsonObject, song: Song): Lyrics? {
        if (data.get("format")?.asString != "ttml") return null
        val text = data.get("lyrics")?.asString ?: return null
        val parsed = LyricsUtils.parseLyrics(text)
        val lines = parsed.synced.orEmpty()
        val words = lines.flatMap { it.words.orEmpty() }
        if (words.isEmpty() || words.none { it.time > 0 }) return null
        if (song.duration > 0 && words.any { it.time > song.duration + 1500L }) return null
        return parsed.copy(areFromRemote = true)
    }

    companion object {
        private fun strings(data: JsonObject, key: String): List<String> =
            data.getAsJsonArray(key)?.map { it.asString }.orEmpty()
        private fun normalized(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        internal fun matchesMetadata(song: Song, titles: List<String>, artists: List<String>, albums: List<String>): Boolean =
            titles.any { normalized(it) == normalized(song.title) } &&
            artists.any { normalized(it) == normalized(song.artist) } &&
            song.album.isNotBlank() && albums.any { normalized(it) == normalized(song.album) }
    }
}

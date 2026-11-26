package com.theveloper.pixelplay.eval

import com.theveloper.pixelplay.brain.BrainHash
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Polite ListenBrainz access (CC0 public data). A generic User-Agent with nothing about whoever runs it, at most one request a second,
 * the rate-limit headers honoured, and every raw response cached gzipped under `cache/` (git-ignored) so a second run makes no request.
 */
class Lb(private val cacheDir: File, private val offline: Boolean = false) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private var lastAt = 0L
    var requests = 0
        private set

    private fun cacheFile(key: String): File {
        val h = java.lang.Long.toHexString(BrainHash.fnv1a64(key))
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return File(cacheDir, "raw/${safe}_$h.json.gz")
    }

    private fun readCache(f: File): String? = if (f.isFile) GZIPInputStream(f.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) } else null

    private fun writeCache(f: File, text: String) {
        f.parentFile.mkdirs()
        val tmp = File(f.path + ".tmp")
        GZIPOutputStream(tmp.outputStream()).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        tmp.renameTo(f)
    }

    /** GET or POST [url]; cached under [key]. Null on a 404 (also cached, as "null"). */
    fun fetch(key: String, url: String, body: String? = null): String? {
        val f = cacheFile(key)
        readCache(f)?.let { return if (it == "null") null else it }
        check(!offline) { "offline and not cached: $key" }
        var attempt = 0
        while (true) {
            val wait = lastAt + 1100L - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(120)).header("User-Agent", USER_AGENT)
            if (body != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)) else b.GET()
            val resp = try {
                http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            } catch (e: java.io.IOException) {
                lastAt = System.currentTimeMillis()
                if (++attempt > 5) throw e
                Thread.sleep(5000L * attempt)
                continue
            }
            lastAt = System.currentTimeMillis()
            requests++
            val remaining = resp.headers().firstValue("X-RateLimit-Remaining").orElse("9").toIntOrNull() ?: 9
            val resetIn = resp.headers().firstValue("X-RateLimit-Reset-In").orElse("1").toLongOrNull() ?: 1
            if (remaining <= 1) Thread.sleep((resetIn + 1) * 1000L)
            when (resp.statusCode()) {
                200 -> { writeCache(f, resp.body()); return resp.body() }
                404, 400 -> { writeCache(f, "null"); return null }
                429, 500, 502, 503, 504 -> {
                    if (++attempt > 6) error("HTTP ${resp.statusCode()} for $key")
                    Thread.sleep(maxOf(resetIn + 1, 5L * attempt) * 1000L)
                }
                else -> error("HTTP ${resp.statusCode()} for $key")
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    fun sitewideArtists(count: Int): List<String> {
        val t = fetch("sitewide-artists-$count", "$API/stats/sitewide/artists?count=$count&range=all_time") ?: return emptyList()
        return Json.parse(t).obj()?.get("payload").obj()?.get("artists").arr().orEmpty().mapNotNull { it.obj()?.get("artist_mbid").str() }
    }

    fun artistListeners(mbid: String): List<String> {
        val t = fetch("listeners-$mbid", "$API/stats/artist/$mbid/listeners?range=all_time") ?: return emptyList()
        return Json.parse(t).obj()?.get("payload").obj()?.get("listeners").arr().orEmpty().mapNotNull { it.obj()?.get("user_name").str() }
    }

    fun listenCount(user: String): Long {
        val t = fetch("count-$user", "$API/user/${enc(user)}/listen-count") ?: return 0
        return (Json.parse(t).obj()?.get("payload").obj()?.get("count") as? Long) ?: 0
    }

    /** The newest [max] listens of [user], newest first, as raw listen objects. */
    fun listens(user: String, max: Int): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        var maxTs: Long? = null
        var page = 0
        while (out.size < max) {
            val url = "$API/user/${enc(user)}/listens?count=1000" + (maxTs?.let { "&max_ts=$it" } ?: "")
            val t = fetch("listens-$user-p$page", url) ?: break
            val ls = Json.parse(t).obj()?.get("payload").obj()?.get("listens").arr().orEmpty().mapNotNull { it.obj() }
            if (ls.isEmpty()) break
            out.addAll(ls)
            val oldest = ls.minOf { (it["listened_at"] as? Long) ?: Long.MAX_VALUE }
            if (maxTs != null && oldest >= maxTs) break
            maxTs = oldest
            page++
            if (ls.size < 1000) break
        }
        return out.take(max)
    }

    /** `POST /1/metadata/recording/` in batches of [batch] (1,000 works; the app uses 16). Map by recording mbid. */
    fun recordings(mbids: List<String>, batch: Int = 1000): Map<String, Map<String, Any?>> {
        val out = HashMap<String, Map<String, Any?>>()
        val sorted = mbids.distinct().sorted()
        for ((n, chunk) in sorted.chunked(batch).withIndex()) {
            val key = "meta-${chunk.first()}-${chunk.size}-${java.lang.Long.toHexString(BrainHash.fnv1a64(chunk.joinToString(",")))}"
            val body = "{\"recording_mbids\":[" + chunk.joinToString(",") { Json.quote(it) } + "],\"inc\":\"artist tag\"}"
            val t = fetch(key, "$API/metadata/recording/", body) ?: continue
            Json.parse(t).obj()?.forEach { (k, v) -> v.obj()?.let { out[k] = it } }
            if (n % 10 == 0) System.err.println("  metadata ${minOf((n + 1) * batch, sorted.size)}/${sorted.size}")
        }
        return out
    }

    companion object {
        const val API = "https://api.listenbrainz.org/1"
        const val USER_AGENT = "PixlAudio-eval/1.0"
    }
}

package com.theveloper.pixelplay.data.spotify.connect

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.theveloper.pixelplay.data.network.spotify.SpotifyArtistRef
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.youtube.TrackMatcher
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * The strict match rules for turning a non-Spotify song (a local file, a YouTube Music row) into a
 * Spotify track: same normalized title and artist, duration within ±3 s, and no live/remix/cover/…
 * mismatch. Reuses [TrackMatcher]'s normalization and variant words. Mirrors iOS
 * `SpotifyConnectMatcher` rule for rule.
 */
object SpotifyConnectMatcher {
    const val DURATION_TOLERANCE_MS = 3_000L

    /** Words that make a different recording (TrackMatcher's list plus "acoustic" and "demo"). */
    internal val VARIANT_WORDS: List<String> = TrackMatcher.VARIANT_PENALTY_WORDS + listOf("acoustic", "demo")

    /** Bracketed or dashed suffixes that name the same recording ("(feat. X)", "- Remastered 2011"). */
    internal val SAME_RECORDING_MARKERS = listOf(
        "feat", "ft", "featuring", "with", "remaster", "remastered", "mono", "stereo",
        "single version", "album version", "original mix", "explicit", "clean"
    )

    private val ARTIST_SEPARATORS = listOf(
        ",", ";", " & ", " / ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " x ", " with "
    )

    /** The ISRC query (`isrc` field filter), or null for something that isn't an ISRC (12 alphanumerics). */
    fun isrcQuery(isrc: String?): String? {
        if (isrc == null) return null
        val code = isrc.filter { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }.uppercase()
        return if (code.length == 12) "isrc:$code" else null
    }

    /** The text query: "title primaryArtist", stripped of quotes and colons so it can't become a field filter. */
    fun textQuery(track: ConnectTrack): String? {
        val title = clean(track.title)
        val artist = clean(primaryArtist(track.artist))
        if (title.isBlank()) return null
        return if (artist.isBlank()) title else "$title $artist"
    }

    private fun clean(s: String): String = s.map { if (it == '"' || it == ':') ' ' else it }.joinToString("").trim()

    /** The first artist of a tag ("A feat. B", "A & B", "A, B", "A; B", "A / B", "A x B"). */
    fun primaryArtist(artist: String): String {
        var best = artist
        for (separator in ARTIST_SEPARATORS) {
            val at = best.indexOf(separator, ignoreCase = true)
            if (at >= 0) best = best.substring(0, at)
        }
        return best.trim()
    }

    /** The title with same-recording suffixes removed, normalized. */
    internal fun coreTitle(title: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < title.length) {
            val c = title[i]
            if (c == '(' || c == '[') {
                val close = (i + 1 until title.length).firstOrNull { title[it] == ')' || title[it] == ']' }
                if (close != null) {
                    val inner = TrackMatcher.normalize(title.substring(i + 1, close))
                    if (SAME_RECORDING_MARKERS.any { TrackMatcher.containsPhrase(inner, it) }) {
                        out.append(' ')
                        i = close + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        val text = out.toString()
        val dash = text.indexOf(" - ")
        if (dash >= 0) {
            val tail = TrackMatcher.normalize(text.substring(dash + 3))
            if (SAME_RECORDING_MARKERS.any { TrackMatcher.containsPhrase(tail, it) }) {
                return TrackMatcher.normalize(text.substring(0, dash))
            }
        }
        return TrackMatcher.normalize(text)
    }

    internal fun titlesMatch(local: String, remote: String): Boolean {
        val a = TrackMatcher.normalize(local)
        val b = TrackMatcher.normalize(remote)
        if (a.isNotEmpty() && a == b) return true
        val coreA = coreTitle(local)
        return coreA.isNotEmpty() && coreA == coreTitle(remote)
    }

    internal fun variantsMatch(local: String, remote: String): Boolean {
        val a = TrackMatcher.normalize(local)
        val b = TrackMatcher.normalize(remote)
        return VARIANT_WORDS.all { TrackMatcher.containsPhrase(a, it) == TrackMatcher.containsPhrase(b, it) }
    }

    internal fun artistsMatch(local: String, remote: List<SpotifyArtistRef>): Boolean {
        val whole = TrackMatcher.normalize(local)
        val primary = TrackMatcher.normalize(primaryArtist(local))
        if (whole.isEmpty()) return false
        for (artist in remote) {
            val name = TrackMatcher.normalize(artist.name.orEmpty())
            if (name.isEmpty()) continue
            if (name == primary || name == whole) return true
            if (TrackMatcher.containsPhrase(whole, name) || TrackMatcher.containsPhrase(name, primary)) return true
            if (TrackMatcher.artistSimilarity(primary, name) >= 0.9f) return true
        }
        return false
    }

    internal fun durationsMatch(localMs: Long, remoteMs: Long?): Boolean {
        if (localMs <= 0L) return true // unknown locally: title + artist decide
        if (remoteMs == null || remoteMs <= 0L) return false
        return abs(localMs - remoteMs) <= DURATION_TOLERANCE_MS
    }

    internal fun isPlayableTrack(candidate: SpotifyTrack): Boolean {
        if (!SpotifyConnect.isTrackId(candidate.id)) return false
        if (candidate.isLocal == true) return false
        val type = candidate.type
        return type == null || type == "track"
    }

    /** A text-search candidate is the same recording. */
    fun isStrictMatch(track: ConnectTrack, candidate: SpotifyTrack): Boolean {
        if (!isPlayableTrack(candidate)) return false
        val name = candidate.name ?: return false
        return titlesMatch(track.title, name) && variantsMatch(track.title, name) &&
            artistsMatch(track.artist, candidate.artists.orEmpty()) && durationsMatch(track.durationMs, candidate.durationMs)
    }

    /** An ISRC hit is the recording by definition; the duration still has to agree (a re-release can reuse it). */
    fun isIsrcMatch(track: ConnectTrack, candidate: SpotifyTrack): Boolean =
        isPlayableTrack(candidate) && durationsMatch(track.durationMs, candidate.durationMs)

    /** The accepted candidate closest in duration, if any. */
    fun best(track: ConnectTrack, candidates: List<SpotifyTrack>, isrc: Boolean): SpotifyTrack? =
        candidates.filter { if (isrc) isIsrcMatch(track, it) else isStrictMatch(track, it) }
            .minByOrNull { abs((it.durationMs ?: 0L) - track.durationMs) }
}

/** A cached answer: the URI, or null for "not on Spotify". */
data class SpotifyConnectResolution(
    @SerializedName("uri") val uri: String?,
    @SerializedName("fingerprint") val fingerprint: String,
    @SerializedName("resolvedAtMs") val resolvedAtMs: Long
)

/** Where the cache lives (the app: a JSON file in filesDir). */
interface SpotifyConnectResolutionStorage {
    fun load(): String?
    fun save(json: String)
}

/** A JSON file, written through a temp file so a crash mid-write never leaves half a cache. */
class SpotifyConnectFileStorage(private val file: File) : SpotifyConnectResolutionStorage {
    override fun load(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()

    override fun save(json: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { Timber.w(it, "Couldn't save the Spotify Connect lookup cache") }
    }
}

/**
 * Turns PixlAudio queue entries into Spotify track URIs. A song with a real Spotify id plays as
 * `spotify:track:<id>`; any other song is looked up with Spotify search — `isrc:<ISRC>` first when
 * it has one, then title + artist — and only a strict match is accepted. Every answer, misses
 * included, is cached in a small JSON file (no Room change: the DB is at version 1 with no
 * migrations). Misses are asked again after a week; failed searches are never cached; a cached
 * answer for other tags (see [ConnectTrack.fingerprint]) is ignored. Mirrors iOS `SpotifyConnectResolver`.
 *
 * @param search one track search; null when the request failed (nothing is cached then).
 * @param isrc the song's ISRC, read on demand (only on a cache miss); null when it has none.
 */
class SpotifyConnectResolver(
    private val storage: SpotifyConnectResolutionStorage?,
    private val search: suspend (query: String) -> List<SpotifyTrack>?,
    private val isrc: suspend (ConnectTrack) -> String? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val entries = HashMap<String, SpotifyConnectResolution>()
    private var loaded = false
    private var dirty = false

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val json = storage?.load() ?: return
        try {
            val type = object : TypeToken<Map<String, SpotifyConnectResolution>>() {}.type
            val decoded: Map<String, SpotifyConnectResolution>? = gson.fromJson(json, type)
            decoded?.forEach { (key, value) ->
                @Suppress("SENSELESS_COMPARISON") // Gson can leave non-null fields null.
                if (value.fingerprint != null) entries[key] = value
            }
        } catch (e: Exception) {
            Timber.w(e, "Spotify Connect lookup cache unreadable; starting empty")
        }
    }

    private fun cachedLocked(track: ConnectTrack): SpotifyConnectSlot {
        track.directUri?.let { return SpotifyConnectSlot.Uri(it) }
        ensureLoadedLocked()
        val entry = entries[track.songId]
        if (entry == null || entry.fingerprint != track.fingerprint) return SpotifyConnectSlot.Pending
        entry.uri?.let { return SpotifyConnectSlot.Uri(it) }
        return if (nowMs() - entry.resolvedAtMs < MISS_RETRY_MS) SpotifyConnectSlot.Skipped else SpotifyConnectSlot.Pending
    }

    /** The answer without a network call: known (direct id, fresh cache entry) or [SpotifyConnectSlot.Pending]. */
    suspend fun cached(track: ConnectTrack): SpotifyConnectSlot = mutex.withLock { cachedLocked(track) }

    /** Resolves one song (cache first). A failed search answers [SpotifyConnectSlot.Pending] and caches nothing. */
    suspend fun resolve(track: ConnectTrack): SpotifyConnectSlot {
        val known = cached(track)
        if (known != SpotifyConnectSlot.Pending) return known
        var searchFailed = false
        val isrcQuery = SpotifyConnectMatcher.isrcQuery(runCatchingNonCancel { isrc(track) })
        if (isrcQuery != null) {
            val tracks = search(isrcQuery)
            if (tracks != null) {
                val hit = SpotifyConnectMatcher.best(track, tracks, isrc = true)
                if (hit?.id != null) return store(track, SpotifyConnect.trackUri(hit.id))
            } else {
                searchFailed = true
            }
        }
        currentCoroutineContext().ensureActive()
        val textQuery = SpotifyConnectMatcher.textQuery(track)
        if (textQuery != null) {
            val tracks = search(textQuery)
            if (tracks != null) {
                val hit = SpotifyConnectMatcher.best(track, tracks, isrc = false)
                if (hit?.id != null) return store(track, SpotifyConnect.trackUri(hit.id))
            } else {
                searchFailed = true
            }
        }
        if (searchFailed) return SpotifyConnectSlot.Pending
        return store(track, null)
    }

    private suspend fun store(track: ConnectTrack, uri: String?): SpotifyConnectSlot = mutex.withLock {
        ensureLoadedLocked()
        entries[track.songId] = SpotifyConnectResolution(uri, track.fingerprint, nowMs())
        dirty = true
        if (entries.size > MAX_ENTRIES) {
            val overflow = entries.size - MAX_ENTRIES
            entries.entries.sortedBy { it.value.resolvedAtMs }.take(overflow).map { it.key }.forEach(entries::remove)
        }
        if (uri != null) SpotifyConnectSlot.Uri(uri) else SpotifyConnectSlot.Skipped
    }

    /** Writes the cache if anything changed. Call off the main thread. */
    suspend fun flush() {
        val json = mutex.withLock {
            if (!dirty || storage == null) return
            dirty = false
            gson.toJson(entries)
        }
        storage?.save(json)
    }

    /** Forgets the cache (logout). */
    suspend fun clear() {
        mutex.withLock {
            entries.clear()
            loaded = true
            dirty = true
        }
        flush()
    }

    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "ISRC lookup failed")
        null
    }

    companion object {
        /** A miss is asked again after this long (the catalogue grows). */
        const val MISS_RETRY_MS = 7L * 24 * 3600 * 1000
        /** Entries kept (oldest dropped beyond it). */
        const val MAX_ENTRIES = 20_000
        private val gson = Gson()
    }
}

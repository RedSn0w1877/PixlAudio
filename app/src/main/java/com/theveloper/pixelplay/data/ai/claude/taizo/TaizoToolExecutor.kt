package com.theveloper.pixelplay.data.ai.claude.taizo

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyAuthManager
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.stats.StatsTimeRange
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Runs Ask Taizo's client tools against the app: the library and playlists through their
 * repositories, the Spotify catalogue through [SpotifyRepository] (found tracks are imported the
 * same way the Spotify browse tab does), and playback through [player], which the chat binds to
 * PlayerViewModel's own play/queue/transport calls. One per chat.
 */
class TaizoToolExecutor @Inject constructor(
    private val musicRepository: MusicRepository,
    private val spotifyRepository: SpotifyRepository,
    private val spotifyAuth: SpotifyAuthManager,
    private val playlists: PlaylistPreferencesRepository,
    private val statsRepository: PlaybackStatsRepository,
    private val taste: MusicTasteRepository,
    private val contextProvider: TaizoContextProvider,
) : TaizoToolRunner {

    @Volatile
    var player: TaizoPlayer? = null

    /** Spotify tracks from this chat's searches, by id, so play_songs can import them. */
    private val spotifyTracks = java.util.concurrent.ConcurrentHashMap<String, SpotifyTrack>()

    override suspend fun confirmationFor(name: String, input: JsonObject): String? {
        val requested = input.strings("song_ids").size + input.strings("queries").size
        return when (name) {
            TaizoTools.PLAY_SONGS -> {
                val p = player ?: return null
                val queueSize = p.queue().size
                // Replacing a real queue asks first; starting from nothing doesn't.
                if (p.currentSong() != null && queueSize > 1) {
                    "Replace your queue ($queueSize songs) with ${plural(requested, "new song")}?"
                } else {
                    null
                }
            }
            TaizoTools.CREATE_PLAYLIST -> if (requested > TaizoTools.CONFIRM_PLAYLIST_SIZE) {
                "Create the playlist \"${input.str("name").orEmpty()}\" with $requested songs?"
            } else {
                null
            }
            else -> null
        }
    }

    override suspend fun run(name: String, input: JsonObject): String = when (name) {
        TaizoTools.SEARCH_LIBRARY -> searchLibrary(input)
        TaizoTools.SEARCH_SPOTIFY -> searchSpotify(input)
        TaizoTools.PLAY_SONGS -> playSongs(input)
        TaizoTools.ADD_TO_QUEUE -> addToQueue(input)
        TaizoTools.PLAYBACK_CONTROL -> playbackControl(input)
        TaizoTools.CREATE_PLAYLIST -> createPlaylist(input)
        TaizoTools.ADD_TO_PLAYLIST -> addToPlaylist(input)
        TaizoTools.LISTENING_INSIGHTS -> insights(input)
        TaizoTools.GET_LYRICS -> lyrics(input)
        TaizoTools.START_SING -> startSing()
        TaizoTools.LIKE_SONG -> likeSong(input)
        else -> throw IllegalArgumentException("Unknown tool: $name")
    }

    private suspend fun searchLibrary(input: JsonObject): String {
        val query = input.str("query").orEmpty().trim()
        val limit = (input.int("limit") ?: 20).coerceIn(1, 50)
        val found = withContext(Dispatchers.IO) { musicRepository.searchSongs(query).first() }
        return buildJsonObject {
            put("query", query)
            put("total", found.size)
            putJsonArray("results") { found.take(limit).forEach { add(TaizoContext.songJson(it)) } }
        }.toString()
    }

    private suspend fun searchSpotify(input: JsonObject): String {
        requireSpotify()
        val query = input.str("query").orEmpty().trim()
        val limit = (input.int("limit") ?: 10).coerceIn(1, 20)
        val tracks = withContext(Dispatchers.IO) {
            spotifyRepository.searchCatalog(query = query, types = "track", limit = limit).tracks
        }.filter { !it.id.isNullOrBlank() }.take(limit)
        tracks.forEach { track -> track.id?.let { spotifyTracks[it] = track } }
        return buildJsonObject {
            put("query", query)
            putJsonArray("results") {
                tracks.forEach { track ->
                    add(buildJsonObject {
                        put("id", SPOTIFY_PREFIX + track.id)
                        put("title", track.name.orEmpty())
                        put("artist", track.artists.orEmpty().mapNotNull { it.name }.joinToString(", "))
                        track.album?.name?.let { put("album", it) }
                        track.album?.releaseDate?.take(4)?.let { put("year", it) }
                        track.durationMs?.let { put("duration", TaizoContext.formatTime(it)) }
                    })
                }
            }
        }.toString()
    }

    private suspend fun playSongs(input: JsonObject): String {
        val p = requirePlayer()
        val resolved = resolve(input.strings("song_ids"), input.strings("queries"))
        if (resolved.songs.isEmpty()) throw IllegalStateException(notFoundMessage(resolved))
        val start = resolved.songs[(input.int("start_index") ?: 0).coerceIn(0, resolved.songs.lastIndex)]
        withContext(Dispatchers.Main) { p.playSongs(resolved.songs, start, QUEUE_NAME) }
        return buildJsonObject {
            put("ok", true)
            put("now_playing", TaizoContext.songJson(start))
            put("queue_length", resolved.songs.size)
            putMissing(resolved)
        }.toString()
    }

    private suspend fun addToQueue(input: JsonObject): String {
        val p = requirePlayer()
        val resolved = resolve(input.strings("song_ids"), input.strings("queries"))
        if (resolved.songs.isEmpty()) throw IllegalStateException(notFoundMessage(resolved))
        val playNext = input.bool("play_next") ?: false
        withContext(Dispatchers.Main) {
            if (p.currentSong() == null) p.playSongs(resolved.songs, resolved.songs.first(), QUEUE_NAME)
            else p.addToQueue(resolved.songs, playNext)
        }
        return buildJsonObject {
            put("ok", true)
            put("added", resolved.songs.size)
            put("position", if (playNext) "next" else "end of queue")
            putJsonArray("songs") { resolved.songs.take(20).forEach { add(TaizoContext.songJson(it)) } }
            putMissing(resolved)
        }.toString()
    }

    private suspend fun playbackControl(input: JsonObject): String {
        val p = requirePlayer()
        val action = input.str("action").orEmpty()
        withContext(Dispatchers.Main) {
            when (action) {
                "play" -> p.play()
                "pause" -> p.pause()
                "next" -> p.next()
                "previous" -> p.previous()
                "seek" -> {
                    val position = input.long("position_ms")
                        ?: throw IllegalArgumentException("seek needs position_ms")
                    p.seekTo(position.coerceAtLeast(0L))
                }
                else -> throw IllegalArgumentException("Unknown action: $action")
            }
        }
        return buildJsonObject {
            put("ok", true)
            put("action", action)
        }.toString()
    }

    private suspend fun createPlaylist(input: JsonObject): String {
        val name = input.str("name").orEmpty().trim().ifBlank { throw IllegalArgumentException("The playlist needs a name") }
        val resolved = resolve(input.strings("song_ids"), input.strings("queries"))
        val playlist = withContext(Dispatchers.IO) {
            playlists.createPlaylist(name = name, songIds = resolved.songs.map { it.id }, isAiGenerated = true)
        }
        return buildJsonObject {
            put("ok", true)
            put("playlist_id", playlist.id)
            put("name", playlist.name)
            put("songs", resolved.songs.size)
            putMissing(resolved)
        }.toString()
    }

    private suspend fun addToPlaylist(input: JsonObject): String {
        val wanted = input.str("playlist").orEmpty().trim()
        val all = withContext(Dispatchers.IO) { playlists.userPlaylistsFlow.first() }
        val target = all.firstOrNull { it.id == wanted }
            ?: all.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: all.firstOrNull { it.name.contains(wanted, ignoreCase = true) }
            ?: throw IllegalArgumentException("No playlist called \"$wanted\". Playlists: ${all.take(30).joinToString(", ") { it.name }}")
        val resolved = resolve(input.strings("song_ids"), input.strings("queries"))
        if (resolved.songs.isEmpty()) throw IllegalStateException(notFoundMessage(resolved))
        withContext(Dispatchers.IO) { playlists.addSongsToPlaylist(target.id, resolved.songs.map { it.id }) }
        return buildJsonObject {
            put("ok", true)
            put("playlist", target.name)
            put("added", resolved.songs.size)
            putMissing(resolved)
        }.toString()
    }

    private suspend fun insights(input: JsonObject): String = withContext(Dispatchers.IO) {
        val period = input.str("period") ?: "all"
        val range = when (period) {
            "today" -> StatsTimeRange.DAY
            "week" -> StatsTimeRange.WEEK
            "month" -> StatsTimeRange.MONTH
            "year" -> StatsTimeRange.YEAR
            else -> StatsTimeRange.ALL
        }
        val songs = musicRepository.getAllSongsOnce()
        val summary = statsRepository.loadSummary(range, songs)
        val tasteState = runCatching { taste.state.first() }.getOrNull()
        buildJsonObject {
            put("period", period)
            put("listening_minutes", summary.totalDurationMs / 60_000L)
            put("plays", summary.totalPlayCount)
            put("unique_songs", summary.uniqueSongs)
            put("active_days", summary.activeDays)
            put("sessions", summary.totalSessions)
            put("average_session_minutes", summary.averageSessionDurationMs / 60_000L)
            summary.peakDayLabel?.let { put("busiest_day", it) }
            putJsonArray("top_artists") {
                summary.topArtists.take(10).forEach { artist ->
                    add(buildJsonObject {
                        put("name", artist.artist)
                        put("plays", artist.playCount)
                        put("minutes", artist.totalDurationMs / 60_000L)
                    })
                }
            }
            putJsonArray("top_songs") {
                summary.topSongs.take(10).forEach { song ->
                    add(buildJsonObject {
                        put("id", song.songId)
                        put("title", song.title)
                        put("artist", song.artist)
                        put("plays", song.playCount)
                    })
                }
            }
            putJsonArray("top_genres") {
                summary.topGenres.filter { it.genre != PlaybackStatsRepository.UNKNOWN_GENRE_LABEL }.take(8).forEach { genre ->
                    add(buildJsonObject {
                        put("genre", genre.genre)
                        put("plays", genre.playCount)
                    })
                }
            }
            if (tasteState != null) {
                put("all_time_completions", tasteState.completions)
                put("all_time_early_skips", tasteState.skips)
            }
        }.toString()
    }

    private suspend fun lyrics(input: JsonObject): String {
        val song = songFor(input.str("song_id"))
        val text = contextProvider.lyricsText(song)
            ?: return buildJsonObject {
                put("song", TaizoContext.songJson(song))
                put("lyrics", JsonPrimitive(null as String?))
                put("note", "No lyrics found for this song.")
            }.toString()
        return buildJsonObject {
            put("song", TaizoContext.songJson(song))
            put("lyrics", TaizoContext.trimLyrics(text, LYRICS_TOOL_MAX_CHARS))
        }.toString()
    }

    private suspend fun startSing(): String {
        val p = requirePlayer()
        val song = p.currentSong() ?: throw IllegalStateException("Nothing is playing. Play a song first.")
        if (p.singActive()) {
            return buildJsonObject {
                put("ok", true)
                put("note", "Sing is already on for \"${song.title}\".")
            }.toString()
        }
        val ready = p.instrumentalReady()
        withContext(Dispatchers.Main) { p.toggleSing() }
        return buildJsonObject {
            put("ok", true)
            put("song", TaizoContext.songJson(song))
            put(
                "note",
                if (ready) "Switched to the instrumental: vocals are off."
                else "PixlAudio is preparing the instrumental; it switches over by itself when it's ready.",
            )
        }.toString()
    }

    private suspend fun likeSong(input: JsonObject): String {
        val song = songFor(input.str("song_id"))
        if (song.id.toLongOrNull() == null) throw IllegalStateException("This song can't be liked from here.")
        withContext(Dispatchers.IO) { musicRepository.setFavoriteStatus(song.id, true) }
        return buildJsonObject {
            put("ok", true)
            put("liked", TaizoContext.songJson(song))
        }.toString()
    }

    // ---------------------------------------------------------------------------------------

    private data class Resolution(val songs: List<Song>, val missing: List<String>)

    /** Library ids, spotify:track:<id> ids and free-text queries -> library songs, in order. */
    private suspend fun resolve(ids: List<String>, queries: List<String>): Resolution = withContext(Dispatchers.IO) {
        val songs = ArrayList<Song>()
        val missing = ArrayList<String>()
        for (id in ids.map { it.trim() }.filter { it.isNotEmpty() }) {
            val song = if (id.startsWith(SPOTIFY_PREFIX)) spotifySong(id.removePrefix(SPOTIFY_PREFIX)) else librarySong(id)
            if (song != null) songs += song else missing += id
        }
        for (query in queries.map { it.trim() }.filter { it.isNotEmpty() }) {
            val song = bestLibraryMatch(query) ?: spotifyMatch(query)
            if (song != null) songs += song else missing += query
        }
        Resolution(songs.distinctBy { it.id }, missing)
    }

    private suspend fun librarySong(id: String): Song? =
        musicRepository.getSongsByIds(listOf(id)).first().firstOrNull()

    private suspend fun spotifySong(spotifyId: String): Song? {
        // Already in the library (synced, liked or imported before)?
        librarySong(SpotifyRepository.unifiedSongId(spotifyId).toString())?.let { return it }
        val track = spotifyTracks[spotifyId] ?: return null
        return importAndLoad(listOf(track)).firstOrNull()
    }

    private suspend fun importAndLoad(tracks: List<SpotifyTrack>): List<Song> {
        if (tracks.isEmpty()) return emptyList()
        spotifyRepository.importTracks(tracks)
        val ids = spotifyRepository.unifiedIdsFor(tracks).map { it.toString() }
        val byId = musicRepository.getSongsByIds(ids).first().associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    private suspend fun bestLibraryMatch(query: String): Song? {
        val candidates = musicRepository.searchSongs(query).first()
        if (candidates.isEmpty()) return null
        val tokens = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length > 1 }
        return candidates.maxByOrNull { song ->
            val haystack = "${song.title} ${song.displayArtist} ${song.album}".lowercase(Locale.ROOT)
            tokens.count { haystack.contains(it) } * 10 - song.title.length / 20
        }
    }

    private suspend fun spotifyMatch(query: String): Song? {
        if (!spotifyAvailable()) return null
        return try {
            val track = spotifyRepository.searchCatalog(query = query, types = "track", limit = 1).tracks
                .firstOrNull { !it.id.isNullOrBlank() } ?: return null
            track.id?.let { spotifyTracks[it] = track }
            importAndLoad(listOf(track)).firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun songFor(songId: String?): Song {
        if (songId.isNullOrBlank()) {
            return requirePlayer().currentSong() ?: throw IllegalStateException("Nothing is playing right now.")
        }
        return withContext(Dispatchers.IO) { librarySong(songId) }
            ?: throw IllegalArgumentException("No song with id $songId in the library.")
    }

    private fun spotifyAvailable(): Boolean = spotifyAuth.hasClientId() && spotifyAuth.isLoggedIn.value

    private fun requireSpotify() {
        if (!spotifyAvailable()) throw IllegalStateException("Spotify isn't connected in PixlAudio, so the catalogue can't be searched.")
    }

    private fun requirePlayer(): TaizoPlayer =
        player ?: throw IllegalStateException("The player isn't ready. Open PixlAudio's player and try again.")

    private fun notFoundMessage(resolution: Resolution): String =
        if (resolution.missing.isEmpty()) "No songs were given."
        else "Couldn't find: ${resolution.missing.take(10).joinToString(", ")}."

    private fun kotlinx.serialization.json.JsonObjectBuilder.putMissing(resolution: Resolution) {
        if (resolution.missing.isNotEmpty()) {
            putJsonArray("not_found") { resolution.missing.take(20).forEach { add(it) } }
        }
    }

    private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    companion object {
        const val SPOTIFY_PREFIX = "spotify:track:"
        const val QUEUE_NAME = "Taizo"
        const val LYRICS_TOOL_MAX_CHARS = 6_000

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}

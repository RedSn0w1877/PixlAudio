package com.theveloper.pixelplay.data.ai.claude.taizo

import com.theveloper.pixelplay.data.model.Song
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The player, as Ask Taizo's tools and context see it (backed by PlayerViewModel's own calls). */
interface TaizoPlayer {
    fun currentSong(): Song?
    fun positionMs(): Long
    fun isPlaying(): Boolean
    /** The whole play queue, and where the current song is in it. */
    fun queue(): List<Song>
    fun currentIndex(): Int
    fun playSongs(songs: List<Song>, startSong: Song, queueName: String)
    fun addToQueue(songs: List<Song>, playNext: Boolean)
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun singActive(): Boolean
    fun instrumentalReady(): Boolean
    fun toggleSing()
}

/** What is playing, for the latest user turn (it changes, so it stays out of the cached prefix). */
data class TaizoNowPlaying(
    val songId: String,
    val title: String,
    val artists: String,
    val album: String,
    val year: Int,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val spotifyId: String?,
    val isrc: String?,
    val instrumentalReady: Boolean,
    val lyrics: String?,
)

/** The listener's profile for the cached context block (built once per chat). */
data class TaizoProfile(
    val librarySongs: Int,
    val libraryArtists: Int,
    val totalPlays: Int,
    val listeningMinutes: Long,
    val topArtists: List<Pair<String, Int>>,
    val topGenres: List<Pair<String, Int>>,
    val topSongs: List<String>,
    val recentPlays: List<String>,
    val playlists: List<String>,
    val skips: Int,
    val completions: Int,
)

/**
 * Ask Taizo's prompt text, pure (TaizoContextTest). The system prompt is two cached blocks: the
 * stable instructions, then the listener's profile. What changes during a chat (the song, its
 * position, the queue, the time) goes into the user's turn instead, and only when it changed.
 */
object TaizoContext {
    const val LYRICS_MAX_CHARS = 2_500
    const val QUEUE_PREVIEW = 20

    val INSTRUCTIONS: String = """
        You are Taizo, PixlAudio's music assistant. PixlAudio is a music player on the user's phone; its
        library is the user's Spotify music (liked songs, playlists, imports) played through the app.

        What you can do:
        - Talk about music: the song playing now, its lyrics and meaning, artists, albums, genres, history,
          recommendations. The listener's profile and what's playing are given below and in each message.
        - Act in the app with your tools: search the library and the Spotify catalogue, play songs, add to
          the queue, control playback, create and extend playlists, read listening insights, get lyrics,
          start Sing (karaoke: vocals off), and like songs.
        - Use web_search and web_fetch for facts you don't know or that may have changed: artist and song
          background, meanings, news, tour dates and concerts, release dates. Cite the pages you used.

        How to work:
        - Never invent song ids. Find songs with search_library (or search_spotify_catalog for music the user
          doesn't have) before playing, queueing or adding them to a playlist. Prefer the user's own library.
        - When the user asks you to do something, do it with the tools, then say briefly what you did.
        - Some actions (replacing the queue, very large playlists) ask the user to tap to confirm; if they
          decline, accept that and don't retry.
        - Keep replies short and warm: a few sentences, or a short "-" list. Plain text only, no tables,
          no headings, no code blocks. Answer in the user's language.
        - If a tool fails, tell the user plainly and suggest what they can do.
    """.trimIndent()

    fun profileBlock(profile: TaizoProfile): String = buildString {
        append("<listener_profile>\n")
        append("Library: ${profile.librarySongs} songs, ${profile.libraryArtists} artists")
        if (profile.playlists.isNotEmpty()) append(", ${profile.playlists.size} playlists")
        append('\n')
        if (profile.totalPlays > 0) {
            append("Listening history: ${profile.totalPlays} plays, about ${profile.listeningMinutes / 60} hours")
            if (profile.skips > 0 || profile.completions > 0) {
                append(", ${profile.completions} songs finished and ${profile.skips} skipped early")
            }
            append('\n')
        }
        if (profile.topArtists.isNotEmpty()) {
            append("Top artists: ").append(profile.topArtists.joinToString(", ") { "${it.first} (${it.second})" }).append('\n')
        }
        if (profile.topGenres.isNotEmpty()) {
            append("Top genres: ").append(profile.topGenres.joinToString(", ") { "${it.first} (${it.second})" }).append('\n')
        }
        if (profile.topSongs.isNotEmpty()) {
            append("Most played songs:\n")
            profile.topSongs.forEach { append("- ").append(it).append('\n') }
        }
        if (profile.recentPlays.isNotEmpty()) {
            append("Recently played (newest first):\n")
            profile.recentPlays.forEach { append("- ").append(it).append('\n') }
        }
        if (profile.playlists.isNotEmpty()) {
            append("Playlists: ").append(profile.playlists.joinToString(", ")).append('\n')
        }
        append("</listener_profile>")
    }

    /**
     * The now-playing block for a user turn. [includeDetails] = false when the song and queue are
     * the ones the previous turn already described: then only the position is repeated.
     */
    fun nowPlayingBlock(
        now: TaizoNowPlaying?,
        upNext: List<Song>,
        includeDetails: Boolean,
        localTime: String,
    ): String = buildString {
        append("<now>\n")
        append("Local time: ").append(localTime).append('\n')
        if (now == null) {
            append("Nothing is playing.\n")
        } else if (!includeDetails) {
            append("Still on \"").append(now.title).append("\" (same queue), at ")
                .append(formatTime(now.positionMs)).append(if (now.isPlaying) ", playing" else ", paused").append('\n')
        } else {
            append("Song: \"").append(now.title).append("\" by ").append(now.artists).append('\n')
            if (now.album.isNotBlank()) append("Album: ").append(now.album).append('\n')
            if (now.year > 0) append("Year: ").append(now.year).append('\n')
            append("Position: ").append(formatTime(now.positionMs)).append(" of ").append(formatTime(now.durationMs))
                .append(if (now.isPlaying) " (playing)" else " (paused)").append('\n')
            append("Library song id: ").append(now.songId).append('\n')
            now.spotifyId?.let { append("Spotify id: ").append(it).append('\n') }
            now.isrc?.let { append("ISRC: ").append(it).append('\n') }
            append("Instrumental for Sing: ").append(if (now.instrumentalReady) "ready" else "not prepared yet").append('\n')
            val lyrics = now.lyrics?.let { trimLyrics(it) }
            if (lyrics.isNullOrBlank()) {
                append("Lyrics: not available\n")
            } else {
                append("<lyrics>\n").append(lyrics).append("\n</lyrics>\n")
            }
            if (upNext.isNotEmpty()) {
                append("Up next:\n")
                upNext.take(QUEUE_PREVIEW).forEachIndexed { i, song ->
                    append(i + 1).append(". ").append(song.title).append(" - ").append(song.displayArtist)
                        .append(" [id ").append(song.id).append("]\n")
                }
            }
        }
        append("</now>")
    }

    private val DEEP_WORK = Regex(
        "\\b(playlists?|mix(es)?|insights?|stats|statistics|habits?|taste|listening history|most played|wrapped)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Effort for a chat turn: high for playlist building and listening insights, medium otherwise (owner, 2026-10-23). */
    fun effortFor(message: String): com.theveloper.pixelplay.data.ai.claude.ClaudeEffort =
        if (DEEP_WORK.containsMatchIn(message)) com.theveloper.pixelplay.data.ai.claude.ClaudeEffort.HIGH
        else com.theveloper.pixelplay.data.ai.claude.ClaudeEffort.MEDIUM

    /** Lyrics capped at [maxChars], cut at a line break. */
    fun trimLyrics(text: String, maxChars: Int = LYRICS_MAX_CHARS): String {
        val clean = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (clean.length <= maxChars) return clean
        val cut = clean.lastIndexOf('\n', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
        return clean.substring(0, cut) + "\n[…]"
    }

    /** "3:07"; hours when needed. */
    fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    /** One song as a tool result entry: what Claude needs to refer back to it. */
    fun songJson(song: Song): JsonObject = buildJsonObject {
        put("id", song.id)
        put("title", song.title)
        put("artist", song.displayArtist)
        if (song.album.isNotBlank()) put("album", song.album)
        if (song.year > 0) put("year", song.year)
        put("duration", formatTime(song.duration))
    }
}

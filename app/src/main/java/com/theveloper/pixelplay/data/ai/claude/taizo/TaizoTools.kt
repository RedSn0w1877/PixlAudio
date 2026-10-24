package com.theveloper.pixelplay.data.ai.claude.taizo

import com.theveloper.pixelplay.data.ai.claude.ClaudeConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Ask Taizo's tools, in a fixed order (the tool list is the front of the cached prompt).
 * Client tools are `strict` with `additionalProperties: false`, and get
 * `eager_input_streaming` because the assistant always streams; their inputs are validated
 * locally before anything runs ([com.theveloper.pixelplay.data.ai.claude.ToolInputValidator]).
 * The two server tools (web search and fetch) run at Anthropic and need nothing from us.
 * Same names, descriptions and schemas on iOS.
 */
object TaizoTools {
    const val SEARCH_LIBRARY = "search_library"
    const val SEARCH_SPOTIFY = "search_spotify_catalog"
    const val PLAY_SONGS = "play_songs"
    const val ADD_TO_QUEUE = "add_to_queue"
    const val PLAYBACK_CONTROL = "playback_control"
    const val CREATE_PLAYLIST = "create_playlist"
    const val ADD_TO_PLAYLIST = "add_to_playlist"
    const val LISTENING_INSIGHTS = "get_listening_insights"
    const val GET_LYRICS = "get_lyrics"
    const val START_SING = "start_sing"
    const val LIKE_SONG = "like_song"

    /** Playlists bigger than this ask for a tap before they are created. */
    const val CONFIRM_PLAYLIST_SIZE = 50

    val clientToolNames: List<String> = listOf(
        SEARCH_LIBRARY, SEARCH_SPOTIFY, PLAY_SONGS, ADD_TO_QUEUE, PLAYBACK_CONTROL,
        CREATE_PLAYLIST, ADD_TO_PLAYLIST, LISTENING_INSIGHTS, GET_LYRICS, START_SING, LIKE_SONG,
    )

    /** Client tool definitions by name (their `input_schema` is what inputs are validated against). */
    val clientTools: Map<String, JsonObject> by lazy {
        linkedMapOf(
            tool(
                SEARCH_LIBRARY,
                "Search the songs in the user's PixlAudio library by title, artist, album or genre. Call this " +
                    "before playing, queueing or adding anything to a playlist so you have real song ids, and when the " +
                    "user asks whether they have a song or artist.",
            ) {
                stringProp("query", "Words to match against title, artist, album and genre.")
                integerProp("limit", "Maximum results, 1-50. Default 20.")
                required("query")
            },
            tool(
                SEARCH_SPOTIFY,
                "Search the Spotify catalogue for tracks the user may not have yet. Call this when the user asks " +
                    "for music outside their library, new releases, or a specific song the library search didn't find. " +
                    "Results come back with ids like spotify:track:<id> that play_songs, add_to_queue and the playlist " +
                    "tools accept.",
            ) {
                stringProp("query", "Spotify search text, e.g. an artist, a song title, or 'artist song'.")
                integerProp("limit", "Maximum results, 1-20. Default 10.")
                required("query")
            },
            tool(
                PLAY_SONGS,
                "Replace the play queue with these songs and start playing. Call this when the user asks to play " +
                    "something. Use song ids from search_library or search_spotify_catalog; free-text queries are " +
                    "resolved to the best match. The app may ask the user to confirm before replacing their queue.",
            ) {
                stringArrayProp("song_ids", "Library song ids or spotify:track:<id> ids, in play order.")
                stringArrayProp("queries", "Free-text songs to look up, e.g. 'Bohemian Rhapsody Queen', in play order.")
                integerProp("start_index", "Index of the song to start with. Default 0.")
            },
            tool(
                ADD_TO_QUEUE,
                "Add songs to the play queue without interrupting what is playing. Call this when the user says " +
                    "'queue', 'add', 'play next' or 'after this'.",
            ) {
                stringArrayProp("song_ids", "Library song ids or spotify:track:<id> ids.")
                stringArrayProp("queries", "Free-text songs to look up.")
                booleanProp("play_next", "True to play right after the current song, false to add at the end. Default false.")
            },
            tool(
                PLAYBACK_CONTROL,
                "Control playback: play, pause, next, previous, or seek to a position. Call this when the user asks " +
                    "to pause, resume, skip, go back, or jump to a time in the song.",
            ) {
                enumProp("action", "What to do.", listOf("play", "pause", "next", "previous", "seek"))
                integerProp("position_ms", "For seek: the position in milliseconds from the start of the song.")
                required("action")
            },
            tool(
                CREATE_PLAYLIST,
                "Create a new playlist in the user's library. Call this when the user asks to make or save a " +
                    "playlist. Find the songs first (search_library / search_spotify_catalog). Playlists over " +
                    "$CONFIRM_PLAYLIST_SIZE songs ask the user to confirm.",
            ) {
                stringProp("name", "The playlist name.")
                stringArrayProp("song_ids", "Library song ids or spotify:track:<id> ids, in order.")
                stringArrayProp("queries", "Free-text songs to look up and add.")
                required("name")
            },
            tool(
                ADD_TO_PLAYLIST,
                "Add songs to one of the user's existing playlists (by name or id). Call this when the user asks to " +
                    "add something to a playlist they already have.",
            ) {
                stringProp("playlist", "The playlist's name (as listed in the context) or id.")
                stringArrayProp("song_ids", "Library song ids or spotify:track:<id> ids.")
                stringArrayProp("queries", "Free-text songs to look up and add.")
                required("playlist")
            },
            tool(
                LISTENING_INSIGHTS,
                "Get the user's listening statistics for a period: top artists, songs and genres, listening time, " +
                    "sessions, skips and completions. Call this for questions about their habits, taste, 'what have I " +
                    "been listening to', or before recommending based on their history.",
            ) {
                enumProp("period", "The time range.", listOf("today", "week", "month", "year", "all"))
                required("period")
            },
            tool(
                GET_LYRICS,
                "Get the lyrics of the current song, or of a library song by id. Call this when the user asks about " +
                    "a song's words or meaning and the lyrics aren't already in the context.",
            ) {
                stringProp("song_id", "A library song id. Leave out for the song playing now.")
            },
            tool(
                START_SING,
                "Turn on Sing for the song playing now: it switches to the instrumental (vocals off) so the user can " +
                    "sing along, preparing it first if needed. Call this when the user wants karaoke or to sing.",
            ) {},
            tool(
                LIKE_SONG,
                "Like (favourite) a song. Call this when the user says they love or want to save/like a song.",
            ) {
                stringProp("song_id", "A library song id. Leave out for the song playing now.")
            },
        )
    }

    /** The full `tools` array: client tools, then web search and web fetch (when [withWeb]). */
    fun requestTools(withWeb: Boolean = true): JsonArray = buildJsonArray {
        clientToolNames.forEach { name -> add(clientTools.getValue(name)) }
        if (withWeb) {
            add(buildJsonObject {
                put("type", ClaudeConfig.WEB_SEARCH_TOOL_TYPE)
                put("name", "web_search")
                put("max_uses", ClaudeConfig.WEB_TOOL_MAX_USES)
            })
            add(buildJsonObject {
                put("type", ClaudeConfig.WEB_FETCH_TOOL_TYPE)
                put("name", "web_fetch")
                put("max_uses", ClaudeConfig.WEB_TOOL_MAX_USES)
            })
        }
    }

    fun schemaOf(name: String): JsonObject? = clientTools[name]?.get("input_schema") as? JsonObject

    private class SchemaBuilder {
        val properties = linkedMapOf<String, JsonObject>()
        val required = mutableListOf<String>()

        fun stringProp(name: String, description: String) {
            properties[name] = buildJsonObject { put("type", "string"); put("description", description) }
        }

        fun integerProp(name: String, description: String) {
            properties[name] = buildJsonObject { put("type", "integer"); put("description", description) }
        }

        fun booleanProp(name: String, description: String) {
            properties[name] = buildJsonObject { put("type", "boolean"); put("description", description) }
        }

        fun stringArrayProp(name: String, description: String) {
            properties[name] = buildJsonObject {
                put("type", "array")
                put("description", description)
                putJsonObject("items") { put("type", "string") }
            }
        }

        fun enumProp(name: String, description: String, values: List<String>) {
            properties[name] = buildJsonObject {
                put("type", "string")
                put("description", description)
                putJsonArray("enum") { values.forEach { add(it) } }
            }
        }

        fun required(vararg names: String) {
            required += names
        }
    }

    private fun tool(name: String, description: String, schema: SchemaBuilder.() -> Unit): Pair<String, JsonObject> {
        val built = SchemaBuilder().apply(schema)
        return name to buildJsonObject {
            put("name", name)
            put("description", description)
            put("strict", true)
            put("eager_input_streaming", true)
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") { built.properties.forEach { (key, value) -> put(key, value) } }
                if (built.required.isNotEmpty()) putJsonArray("required") { built.required.forEach { add(it) } }
                put("additionalProperties", false)
            }
        }
    }
}

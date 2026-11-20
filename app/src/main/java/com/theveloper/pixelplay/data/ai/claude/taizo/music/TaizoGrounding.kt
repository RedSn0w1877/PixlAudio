package com.theveloper.pixelplay.data.ai.claude.taizo.music

import com.theveloper.pixelplay.brain.AssistantRules
import com.theveloper.pixelplay.brain.Grounding
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * What one Ask Taizo conversation really held, for the grounding rule (docs/brain-spec.md, 1 principle 9, 9.4): every name a tool returned,
 * every ref a tool issued (so the next call can use it, and a made-up ref is refused), every number a tool or the app stated.
 *
 * The final reply is checked against it ([check], the pure [Grounding.check]); a track, album or artist the reply names that no tool returned
 * is replaced and flagged, and a number about the listener that no tool returned drops its sentence. One per conversation: [reset] on a new
 * chat, [noteHistory] when an older chat is opened again (its tool results are in the history the model is sent anyway).
 *
 * Thread-safe enough for its use: tools run one after another inside a turn, the check runs after the turn; the maps are concurrent.
 */
class TaizoGrounding {
    @Volatile private var registry = Grounding.Registry()
    private val refs = ConcurrentHashMap<String, TrackInfo>()

    /** Refs a tool result of this conversation held (also those of an older chat's history), whose track details were not kept. */
    private val seenRefs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The genre and mood ids lookup_genre returned (id to name): an id the model writes from memory is not one of them. */
    private val genreNames = ConcurrentHashMap<Int, String>()
    private val moodNames = ConcurrentHashMap<Int, String>()
    @Volatile private var seeded = false
    private val userLog = StringBuilder()
    private val seenContexts = HashSet<Long>()

    /** True once [noteHistory] ran for this conversation (or it was started here). */
    val isSeeded: Boolean get() = seeded

    val knownNames: Int get() = registry.size

    fun reset() {
        registry = Grounding.Registry()
        refs.clear()
        seenRefs.clear()
        genreNames.clear()
        moodNames.clear()
        seeded = false
        synchronized(userLog) { userLog.setLength(0) }
        synchronized(seenContexts) { seenContexts.clear() }
    }

    // ---- what tools return ---------------------------------------------------------------------------------

    /** A track a tool returned: its ref becomes usable, its title, album and every credited artist become names the reply may use. */
    fun noteTrack(track: TrackInfo) {
        refs[track.ref] = track
        noteName(track.title)
        if (track.album.isNotBlank()) noteName(track.album)
        noteArtists(track.artist)
    }

    /** A credited artist string ("A, B & C") or one name: the whole and each part. */
    fun noteArtists(display: String) {
        noteName(display)
        for (part in splitCredits(display)) noteName(part)
    }

    fun noteName(name: String?) {
        if (name.isNullOrBlank() || name.length > MAX_NAME) return
        registry.addName(name)
    }

    /**
     * A whole tool result: its text (numbers and quotable words) and every short string in it as a name. Used for tools whose JSON the code
     * does not shape itself (the older 16 tools, a web result), so a reply that names something they returned is grounded too.
     */
    fun noteToolResult(json: String) {
        registry.addText(json)
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            return
        }
        walk(element)
    }

    /** A number the app itself vouches for: how many items a result held. */
    fun noteCount(n: Int) = registry.addNumber(n)

    /** What the listener wrote: a name or number they said first is theirs. Kept for the whole conversation (bounded). */
    fun noteUser(text: String) {
        if (text.isBlank()) return
        synchronized(userLog) {
            if (userLog.length > MAX_USER_LOG) userLog.delete(0, userLog.length - MAX_USER_LOG / 2)
            userLog.append(text).append('\n')
        }
    }

    /** Plain text the app put in front of the model (the profile block, the now-playing block): numbers, and each line's chunks as names. */
    fun noteContext(text: String) {
        if (text.isBlank()) return
        val key = (text.length.toLong() shl 32) xor text.hashCode().toLong()
        if (!synchronized(seenContexts) { seenContexts.add(key) }) return
        registry.addText(text)
        for (line in text.lineSequence()) {
            for (chunk in chunksOf(line)) noteName(chunk)
        }
    }

    /** What an older conversation's history holds: every tool result in it. */
    fun noteHistory(history: List<JsonObject>) {
        seeded = true
        for (message in history) {
            val role = (message["role"] as? JsonPrimitive)?.content
            val raw = message["content"]
            // A chat opened again carries only its words: what the user said is theirs, and what Taizo said was checked when it was said.
            if (raw is JsonPrimitive && raw.isString) {
                if (role == "user") noteUser(raw.content) else noteContext(raw.content.replace("[[", "").replace("]]", ""))
                continue
            }
            val content = raw as? JsonArray ?: continue
            for (block in content) {
                val obj = block as? JsonObject ?: continue
                val type = (obj["type"] as? JsonPrimitive)?.content
                if (type == "text") {
                    val text = (obj["text"] as? JsonPrimitive)?.content.orEmpty()
                    if (role == "user") noteUser(text) else noteContext(text.replace("[[", "").replace("]]", ""))
                } else if (type == "tool_result") {
                    val c = obj["content"]
                    if (c is JsonPrimitive && c.isString) noteToolResult(c.content)
                    else if (c is JsonArray) c.forEach { part -> ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content?.let(::noteToolResult) }
                }
            }
        }
    }

    // ---- refs ----------------------------------------------------------------------------------------------

    fun trackOf(ref: String): TrackInfo? = refs[ref]

    fun isKnownRef(ref: String): Boolean = refs.containsKey(ref) || seenRefs.contains(ref)

    /** The name of a genre id lookup_genre returned in this conversation, or null. */
    fun genreName(id: Int): String? = genreNames[id]

    /** The genre ids of [list] that lookup_genre returned in this conversation; the others are added to [unknown] as "genre 12". */
    fun knownGenreIds(list: List<Int>, unknown: MutableList<String>? = null): List<Int> {
        val out = ArrayList<Int>(list.size)
        for (id in list) if (id <= 0 || genreNames.containsKey(id)) out.add(id) else unknown?.add("genre $id")
        return out
    }

    /** Same for mood ids (a mood and a genre share numbers: an id of one list is not an id of the other). */
    fun knownMoodIds(list: List<Int>, unknown: MutableList<String>? = null): List<Int> {
        val out = ArrayList<Int>(list.size)
        for (id in list) if (id <= 0 || moodNames.containsKey(id)) out.add(id) else unknown?.add("mood $id")
        return out
    }

    /** The refs of [list] that were issued in this conversation, in order; the others are returned in [unknown]. */
    fun known(list: List<String>, unknown: MutableList<String>? = null): List<String> {
        val out = ArrayList<String>(list.size)
        for (ref in list) if (isKnownRef(ref)) out.add(ref) else unknown?.add(ref)
        return out
    }

    val refCount: Int get() = refs.size

    // ---- the reply -----------------------------------------------------------------------------------------

    /** Checks [reply] against what this conversation held; everything the listener wrote in it counts as theirs. */
    fun check(reply: String, placeholder: String): Grounding.Verdict =
        Grounding.check(reply, registry, synchronized(userLog) { userLog.toString() }, placeholder, dropUngroundedNumbers = true, scanUnmarked = true)

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun walk(element: JsonElement) {
        when (element) {
            is JsonPrimitive -> if (element.isString) noteName(element.content)
            is JsonArray -> {
                registry.addNumber(element.size)
                element.forEach(::walk)
            }
            is JsonObject -> {
                (element["ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { if (AssistantRules.parseRef(it) != null) seenRefs.add(it) }
                val idPrimitive = element["id"] as? JsonPrimitive
                val id = if (idPrimitive != null && !idPrimitive.isString) idPrimitive.intOrNull else null
                val name = (element["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (id != null && id > 0 && !name.isNullOrBlank()) {
                    if ((element["kind"] as? JsonPrimitive)?.content == "mood") moodNames[id] = name else genreNames[id] = name
                }
                element.values.forEach(::walk)
            }
            else -> Unit
        }
    }

    companion object {
        const val MAX_NAME = Grounding.MAX_SPAN
        private const val MAX_USER_LOG = 8_000

        /** The parts of a credit line: "A, B & C" or "A feat. B" are A, B and C. */
        fun splitCredits(display: String): List<String> {
            var parts = listOf(display)
            for (joiner in CREDIT_JOINERS) {
                val next = ArrayList<String>()
                for (p in parts) next.addAll(p.split(joiner))
                parts = next
            }
            return parts.map { it.trim() }.filter { it.isNotEmpty() }
        }

        private val CREDIT_JOINERS = listOf(", ", " & ", " x ", " X ", " feat. ", " ft. ", " feat ", " ft ", " featuring ", " with ", " / ", " + ", " and ")

        /** The pieces of a context line ("Title - Artist (212 plays)", "Top artists: A (9), B (4)") that may be a name. */
        fun chunksOf(line: String): List<String> {
            var parts = listOf(line)
            for (sep in CONTEXT_SEPARATORS) {
                val next = ArrayList<String>()
                for (p in parts) next.addAll(p.split(sep))
                parts = next
            }
            val out = ArrayList<String>()
            for (p in parts) {
                val t = p.trim().trimEnd(')', '.', ';').trim()
                if (t.length in 2..MAX_NAME) out.add(t)
            }
            return out
        }

        private val CONTEXT_SEPARATORS = listOf(": ", ", ", " - ", " (", "; ", " | ")
    }
}

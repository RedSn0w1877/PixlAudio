package com.theveloper.pixelplay.data.ai.claude.taizo.music

import com.theveloper.pixelplay.brain.AssistantRules
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoPlayer
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The ten music tools of Ask Taizo (docs/brain-spec.md, 9.3): a tool call in, the JSON the model reads out, over a [MusicBackend].
 *
 * The assistant never ranks and never invents a track. Every track in a result comes from the backend (the engine, the alias table, a
 * search) and is noted in [grounding] as it is returned, so the reply check can tell a returned name from an invented one; a ref the model
 * passes that no tool issued in this conversation is refused and reported, never guessed at. Results are capped (25 by default), carry refs
 * the next call can use, and say what they do not know instead of leaving it out.
 *
 * Pure of Android: tested with a fake backend ([TaizoMusicToolsTest]).
 */
class TaizoMusicTools(
    private val backend: MusicBackend,
    private val grounding: TaizoGrounding,
) {
    /** Names looked up on the network in the turn that is running: a runaway loop cannot spend the day's YouTube requests. */
    @Volatile
    private var lookedUpThisTurn = 0

    /** Lookups the backend skipped in this turn because the day's budget was spent (the model is told, not left to retry). */
    @Volatile
    var budgetSpentThisTurn: Boolean = false
        private set

    fun beginTurn() {
        lookedUpThisTurn = 0
        budgetSpentThisTurn = false
    }

    /** True for the ten music tools. */
    fun handles(name: String): Boolean = name in TaizoTools.musicToolNames

    /**
     * The question to ask before the call runs, or null. `steer_session` with `always` is a lasting change of what the app recommends, and any
     * steer or station start after text from a web page or lyrics (`untrusted`) asks too: the model's say-so is not enough there.
     */
    fun confirmationFor(name: String, input: JsonObject, untrusted: Boolean): String? = when (name) {
        TaizoTools.STEER_SESSION -> {
            val change = input.str("change").orEmpty()
            val scope = input.str("scope").orEmpty()
            when {
                scope == "always" -> "Keep this for good: ${describeChange(change, input.str("target").orEmpty())}? You can undo it in Settings."
                untrusted -> "${describeChange(change, input.str("target").orEmpty()).replaceFirstChar { it.uppercase() }} for this queue?"
                else -> null
            }
        }
        else -> null
    }

    suspend fun run(name: String, input: JsonObject, player: TaizoPlayer?): String {
        val result = when (name) {
            TaizoTools.RESOLVE_TRACKS -> resolveTracks(input)
            TaizoTools.RECOMMEND -> recommend(input)
            TaizoTools.BUILD_MIX -> buildMix(input)
            TaizoTools.START_STATION -> startStation(input, player)
            TaizoTools.STEER_SESSION -> steer(input, player)
            TaizoTools.EXPLAIN_TRACK -> explainTrack(input, player)
            TaizoTools.EXPLAIN_TASTE -> explainTaste(input)
            TaizoTools.LOOKUP_GENRE -> lookupGenre(input)
            TaizoTools.SIMILAR_ARTISTS -> similarArtists(input)
            TaizoTools.NEW_RELEASES -> newReleases(input)
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
        // Every name and number in the result is something the reply may say: noted here, so the rule holds wherever the tools run.
        return result.toString().also(grounding::noteToolResult)
    }

    // ---- resolve_tracks ----------------------------------------------------------------------------------

    private suspend fun resolveTracks(input: JsonObject): JsonObject {
        val asked = (input["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .map { it.str("title").orEmpty().trim() to it.str("artist").orEmpty().trim() }
        val wanted = asked.take(AssistantRules.MAX_RESOLVE_ITEMS)
        // The network part of a turn is bounded: past the cap an item is skipped, not looked up (the alias table and library are free).
        val room = (MAX_LOOKUPS_PER_TURN - lookedUpThisTurn).coerceAtLeast(0)
        val allowed = wanted.take(room).filter { it.first.isNotEmpty() }
        val looked = if (allowed.isEmpty()) emptyList() else backend.resolve(allowed)
        lookedUpThisTurn += allowed.size
        val byItem = HashMap<Pair<String, String>, Lookup>()
        for (i in allowed.indices) byItem.putIfAbsent(allowed[i], looked.getOrElse(i) { Lookup(null) })
        var found = 0
        val results = buildJsonArray {
            wanted.forEachIndexed { index, item ->
                add(buildJsonObject {
                    put("index", index)
                    val lookup = if (item.first.isEmpty()) Lookup(null) else byItem[item] ?: Lookup(null, skipped = true)
                    val track = lookup.track
                    if (track == null) {
                        put("not_found", true)
                        if (lookup.skipped) {
                            budgetSpentThisTurn = true
                            put("reason", "not looked up: PixlAudio's lookups for this chat or this day are used up")
                        }
                    } else {
                        found++
                        grounding.noteTrack(track)
                        putTrack(track)
                    }
                })
            }
        }
        grounding.noteCount(found)
        grounding.noteCount(wanted.size)
        return buildJsonObject {
            put("results", results)
            if (asked.size > wanted.size) put("ignored_over_limit", asked.size - wanted.size)
            put("note", "Use the refs in later calls. Drop every not_found item; do not name it as a recommendation.")
        }
    }

    // ---- recommend / build_mix ---------------------------------------------------------------------------

    private suspend fun recommend(input: JsonObject): JsonObject {
        val unknown = ArrayList<String>()
        val seeds = grounding.known(input.strings("seed_refs"), unknown)
        val query = AssistantRules.normalize(
            title = "", seedRefs = seeds, genreIds = input.ints("genre_ids"), moodIds = emptyList(),
            yearFrom = input.int("year_from"), yearTo = input.int("year_to"), energy = input.str("energy"), tempo = null,
            novelty = input.str("novelty"), noveltySharePct = -1, count = input.int("count"), excludeArtistRefs = emptyList(),
            minCount = 1, maxCount = AssistantRules.MAX_RECOMMEND, defaultCount = AssistantRules.DEFAULT_RESULTS,
        )
        val rec = backend.recommend(query)
        val picks = rec.picks.take(query.count)
        for (p in picks) notePick(p)
        grounding.noteCount(picks.size)
        return buildJsonObject {
            putJsonArray("picks") { picks.forEach { add(pickJson(it)) } }
            put("count", picks.size)
            putFilters(rec.unsupportedFilters)
            if (rec.coldStart) put("note", "PixlAudio has little of the listener's history yet; these follow the radios' own order, not their taste.")
            putUnknownRefs(unknown)
        }
    }

    private suspend fun buildMix(input: JsonObject): JsonObject {
        val unknown = ArrayList<String>()
        val seeds = grounding.known(input.strings("seed_refs"), unknown)
        val excluded = grounding.known(input.strings("exclude_artist_refs"), unknown)
        val query = AssistantRules.normalize(
            title = input.str("title"), seedRefs = seeds, genreIds = input.ints("genre_ids"), moodIds = input.ints("mood_ids"),
            yearFrom = input.int("year_from"), yearTo = input.int("year_to"), energy = input.str("energy"), tempo = input.str("tempo"),
            novelty = null, noveltySharePct = input.intOr("novelty_share_pct", -1), count = input.intOr("length", AssistantRules.DEFAULT_MIX),
            excludeArtistRefs = excluded, minCount = AssistantRules.MIN_MIX, maxCount = AssistantRules.MAX_MIX, defaultCount = AssistantRules.DEFAULT_MIX,
        )
        val mix = backend.buildMix(query)
            ?: return buildJsonObject {
                put("built", false)
                put("note", "PixlAudio has nothing to build this from yet (no songs and no cached radios). Say so; do not make a list yourself.")
                putUnknownRefs(unknown)
            }
        val json = mixJson(mix)
        return buildJsonObject {
            json.forEach { (k, v) -> put(k, v) }
            putUnknownRefs(unknown)
        }
    }

    private fun mixJson(mix: BuiltMix): JsonObject {
        for (p in mix.tracks) notePick(p)
        val shown = mix.tracks.take(AssistantRules.DEFAULT_RESULTS)
        grounding.noteCount(mix.tracks.size)
        grounding.noteCount(shown.size)
        return buildJsonObject {
            put("built", true)
            put("mix_id", mix.mixId)
            put("title", mix.title)
            put("total", mix.tracks.size)
            putJsonArray("tracks") { shown.forEach { add(pickJson(it)) } }
            putFilters(mix.unsupportedFilters)
            put("note", "The app shows this mix as a card with Play and Save. To play it from chat, call play_songs with song_ids [\"mix:${mix.mixId}\"].")
        }
    }

    // ---- start_station / steer_session -------------------------------------------------------------------

    private suspend fun startStation(input: JsonObject, player: TaizoPlayer?): JsonObject {
        val kind = input.str("seed_kind").orEmpty()
        val seed = input.str("seed_ref").orEmpty().trim()
        if (seed.isEmpty()) return failed("started", "seed_ref is empty.")
        if (kind == "song" && !grounding.isKnownRef(seed) && AssistantRules.parseRef(seed) != null) {
            return failed("started", "That ref was not returned by a tool in this chat. Call resolve_tracks first.")
        }
        if (kind == "song" && AssistantRules.parseRef(seed) == null) {
            return failed("started", "A song seed must be a ref (sp:<id> or yt:<id>) from resolve_tracks.")
        }
        val outcome = backend.startStation(seed, kind, input.str("mode").orEmpty(), player)
        return buildJsonObject {
            put("started", outcome.started)
            outcome.firstTrack?.let {
                grounding.noteTrack(it)
                put("first_track", buildJsonObject { putTrack(it) })
            }
            if (!outcome.started && outcome.reason.isNotEmpty()) put("reason", outcome.reason)
        }
    }

    private suspend fun steer(input: JsonObject, player: TaizoPlayer?): JsonObject {
        val outcome = backend.steer(input.str("change").orEmpty(), input.str("target").orEmpty().trim(), input.str("scope").orEmpty(), player)
        grounding.noteCount(outcome.tailChanged)
        return buildJsonObject {
            put("applied", outcome.applied)
            put("tail_changed", outcome.tailChanged)
            if (outcome.note.isNotEmpty()) put("note", outcome.note)
        }
    }

    // ---- explain_track / explain_taste -------------------------------------------------------------------

    private suspend fun explainTrack(input: JsonObject, player: TaizoPlayer?): JsonObject {
        val ref = input.str("ref").orEmpty().trim()
        val e = backend.explainTrack(ref, player)
            ?: return buildJsonObject {
                put("found", false)
                put("note", "PixlAudio has nothing recorded about that track. Say you can't tell why; do not guess.")
            }
        grounding.noteName(e.title)
        grounding.noteArtists(e.artist)
        e.whyArgs.forEach(grounding::noteName)
        val unknown = ArrayList<String>()
        val rate = e.completionRate
        return buildJsonObject {
            put("found", true)
            put("ref", e.ref)
            put("title", e.title)
            put("artist", e.artist)
            if (e.arm != null) put("arm", e.arm) else unknown.add("arm")
            if (!e.whyKey.isNullOrEmpty()) {
                put("why_key", e.whyKey)
                putJsonArray("why_args") { e.whyArgs.forEach { add(it) } }
            } else unknown.add("why")
            val factors = e.factors
            if (factors != null && factors.isNotEmpty()) {
                putJsonArray("top_factors") {
                    factors.forEach { f -> add(buildJsonObject { put("name", f.name); put("direction", if (f.up) "up" else "down") }) }
                }
            } else unknown.add("top_factors")
            put("facts", buildJsonObject {
                val genres = e.genres
                if (genres != null) {
                    genres.forEach(grounding::noteName)
                    putJsonArray("genres") { genres.forEach { add(it) } }
                } else unknown.add("genres")
                if (e.year > 0) put("year", e.year) else unknown.add("year")
                if (e.plays != null) {
                    put("plays", e.plays)
                    grounding.noteCount(e.plays)
                } else unknown.add("plays")
                if (rate != null) put("completion_rate_pct", (rate * 100.0 + 0.5).toInt()) else unknown.add("completion_rate")
            })
            putJsonArray("unknown") { unknown.forEach { add(it) } }
            put("note", "Report only these fields. Anything in unknown is unknown: say so.")
        }
    }

    private suspend fun explainTaste(input: JsonObject): JsonObject {
        val scope = AssistantRules.oneOf(input.str("scope"), AssistantRules.EXPLAIN_SCOPES, "summary")
        val t = backend.explainTaste(scope)
        for (line in t.lines) {
            grounding.noteName(line.name)
            grounding.noteName(line.value)
            if (line.detail.isNotEmpty()) grounding.noteName(line.detail)
        }
        return buildJsonObject {
            put("scope", t.scope)
            putJsonArray("facts") {
                t.lines.forEach { l ->
                    add(buildJsonObject {
                        put("name", l.name)
                        put("value", l.value)
                        if (l.detail.isNotEmpty()) put("detail", l.detail)
                    })
                }
            }
            putJsonArray("unknown") { t.unknown.forEach { add(it) } }
            put("note", "Report only these facts, with these numbers. Anything in unknown is unknown: say so.")
        }
    }

    // ---- lookup_genre / similar_artists / new_releases ---------------------------------------------------

    private suspend fun lookupGenre(input: JsonObject): JsonObject {
        val r = backend.lookupGenre(input.str("query").orEmpty().trim())
        val g = r.genre
        if (g == null) {
            r.nearest.forEach { grounding.noteName(it.name) }
            return buildJsonObject {
                put("found", false)
                putJsonArray("nearest") { r.nearest.forEach { add(refJson(it)) } }
                put("note", "Not in the genre atlas. Use one of the nearest ids, or say it is not there; do not invent an id.")
            }
        }
        grounding.noteName(g.name)
        (g.parents + g.children + g.related + g.influences).forEach { grounding.noteName(it.name) }
        g.descriptors.forEach(grounding::noteName)
        grounding.noteName(g.region)
        return buildJsonObject {
            put("found", true)
            put("kind", if (g.isMood) "mood" else "genre")
            put("id", g.id)
            put("name", g.name)
            if (g.description.isNotEmpty()) put("description", g.description)
            putJsonArray("parents") { g.parents.forEach { add(refJson(it)) } }
            putJsonArray("children") { g.children.take(AssistantRules.DEFAULT_RESULTS).forEach { add(refJson(it)) } }
            putJsonArray("related") { g.related.take(AssistantRules.DEFAULT_RESULTS).forEach { add(refJson(it)) } }
            putJsonArray("influences") { g.influences.take(AssistantRules.DEFAULT_RESULTS).forEach { add(refJson(it)) } }
            if (g.region.isNotEmpty()) put("region", g.region)
            if (g.era > 0) put("era", g.era)
            if (g.descriptors.isNotEmpty()) putJsonArray("descriptors") { g.descriptors.forEach { add(it) } }
        }
    }

    private suspend fun similarArtists(input: JsonObject): JsonObject {
        val count = AssistantRules.clampInt(input.intOr("count", AssistantRules.DEFAULT_RESULTS), 1, AssistantRules.DEFAULT_RESULTS)
        val r = backend.similarArtists(input.str("artist").orEmpty().trim(), count, input.bool("only_unplayed") == true)
        val artists = r.artists.take(count)
        artists.forEach { grounding.noteName(it.name) }
        grounding.noteCount(artists.size)
        return buildJsonObject {
            putJsonArray("artists") {
                artists.forEach { a ->
                    add(buildJsonObject {
                        put("name", a.name)
                        if (a.ref.isNotEmpty()) put("ref", a.ref)
                        put("score", (a.score * 100.0 + 0.5).toInt().coerceIn(0, 100))
                        putJsonArray("sources") { a.sources.forEach { add(it) } }
                    })
                }
            }
            if (r.note.isNotEmpty()) put("note", r.note)
        }
    }

    private suspend fun newReleases(input: JsonObject): JsonObject {
        val count = AssistantRules.clampInt(input.intOr("count", AssistantRules.DEFAULT_RESULTS), 1, AssistantRules.DEFAULT_RESULTS)
        val scope = AssistantRules.oneOf(input.str("scope"), AssistantRules.RELEASE_SCOPES, "my_artists")
        val r = backend.newReleases(scope, count)
        val releases = r.releases.take(count)
        for (x in releases) {
            grounding.noteName(x.title)
            grounding.noteArtists(x.artist)
        }
        grounding.noteCount(releases.size)
        return buildJsonObject {
            put("scope", scope)
            putJsonArray("releases") {
                releases.forEach { x ->
                    add(buildJsonObject {
                        put("title", x.title)
                        put("artist", x.artist)
                        put("type", x.type)
                        if (x.year > 0) put("year", x.year)
                        if (x.ref.isNotEmpty()) put("ref", x.ref)
                    })
                }
            }
            if (r.note.isNotEmpty()) put("note", r.note)
        }
    }

    // ---- shared pieces -----------------------------------------------------------------------------------

    private fun notePick(p: PickInfo) {
        grounding.noteTrack(p.track)
        p.whyArgs.forEach(grounding::noteName)
    }

    private fun pickJson(p: PickInfo): JsonObject = buildJsonObject {
        putTrack(p.track)
        if (p.whyKey.isNotEmpty()) {
            put("why_key", p.whyKey)
            putJsonArray("why_args") { p.whyArgs.forEach { add(it) } }
        }
    }

    private fun JsonObjectBuilder.putTrack(t: TrackInfo) {
        put("ref", t.ref)
        put("title", t.title)
        put("artist", t.artist)
        if (t.year > 0) put("year", t.year)
        put("in_library", t.inLibrary)
    }

    private fun refJson(g: GenreRef): JsonObject = buildJsonObject {
        put("id", g.id)
        put("name", g.name)
    }

    private fun JsonObjectBuilder.putFilters(unsupported: List<String>) {
        if (unsupported.isEmpty()) return
        putJsonArray("filters_not_applied") { unsupported.forEach { add(it) } }
        put("filters_note", "PixlAudio has no data for these filters yet, so they were not applied. Tell the user.")
    }

    private fun JsonObjectBuilder.putUnknownRefs(unknown: List<String>) {
        if (unknown.isEmpty()) return
        putJsonArray("refs_ignored") { unknown.forEach { add(it) } }
        put("refs_note", "These refs were not returned by a tool in this chat, so they were left out.")
    }

    private fun failed(key: String, reason: String): JsonObject = buildJsonObject {
        put(key, false)
        put("reason", reason)
    }

    private fun describeChange(change: String, target: String): String {
        val name = target.ifBlank { "this" }
        return when (change) {
            "more_artist" -> "play more of $name"
            "less_artist" -> "play less of $name"
            "more_genre" -> "play more of genre $name"
            "less_genre" -> "play less of genre $name"
            "energy_up" -> "turn the energy up"
            "energy_down" -> "turn the energy down"
            "more_familiar" -> "play more of what you know"
            "more_new" -> "play more new music"
            else -> change
        }
    }

    companion object {
        /** Network lookups one turn may start (the YouTube budget of the day is smaller still, and the backend enforces it). */
        const val MAX_LOOKUPS_PER_TURN = 40

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.int(key: String): Int = intOr(key, 0)
        private fun JsonObject.intOr(key: String, default: Int): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: default
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        private fun JsonObject.ints(key: String): List<Int> =
            (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
    }
}

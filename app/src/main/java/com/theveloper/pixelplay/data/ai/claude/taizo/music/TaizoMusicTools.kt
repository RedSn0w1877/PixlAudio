package com.theveloper.pixelplay.data.ai.claude.taizo.music

import com.theveloper.pixelplay.brain.AssistantCards
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

    /** The mixes this chat built, newest last (a card's Play, Save and chips find their request here). */
    private val builtMixes = object : LinkedHashMap<String, BuiltMix>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BuiltMix>?): Boolean = size > MAX_MIXES
    }

    /** What the explain tools named this turn (genres, artists), for the one-tap "Less X" that ends an explanation. */
    private val namedThisTurn = ArrayList<AssistantCards.Named>()

    fun beginTurn() {
        lookedUpThisTurn = 0
        budgetSpentThisTurn = false
        synchronized(namedThisTurn) { namedThisTurn.clear() }
    }

    /** The genres and artists the explain tools of this turn returned; the chat offers "Less X" only for one of these the listener asked about. */
    fun namedThisTurn(): List<AssistantCards.Named> = synchronized(namedThisTurn) { namedThisTurn.toList() }

    private fun noteNamed(kind: Int, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        synchronized(namedThisTurn) { if (namedThisTurn.none { it.kind == kind && it.name == n }) namedThisTurn.add(AssistantCards.Named(kind, n)) }
    }

    private fun remember(mix: BuiltMix) {
        synchronized(builtMixes) { builtMixes[mix.mixId] = mix }
    }

    /** The card of a mix this chat built, or null. */
    fun mixCard(mixId: String): MixCard? = synchronized(builtMixes) { builtMixes[mixId] }?.let(MixCard::of)

    /**
     * A chip tapped under a card: the stored request with the delta applied, built again by the engine. No model call, no tool call of the
     * conversation: the tracks it returns are noted as held, so a reply that names one is not an invented name. Null when the engine
     * had nothing to build or the mix is not this chat's.
     */
    suspend fun adjustMix(mixId: String, chip: AssistantRules.Chip): MixCard? {
        val next = backend.adjustMix(mixId, chip) ?: return null
        for (p in next.tracks) notePick(p)
        grounding.noteName(next.title)
        // The card takes the new list in place of the old one: the old record goes, so chips never push another card out.
        synchronized(builtMixes) { builtMixes.remove(mixId) }
        remember(next)
        return MixCard.of(next)
    }

    private fun titleOfMix(mixId: String): String = synchronized(builtMixes) { builtMixes[mixId]?.title }.orEmpty().ifBlank { "Made for you" }

    /** The songs of a mix this chat built, ready to play (engine tracks registered in `playable`) with its title; null for any other id. */
    suspend fun songsOfMix(mixId: String): Pair<String, List<com.theveloper.pixelplay.data.model.Song>>? {
        val songs = backend.songsForMix(mixId)?.takeIf { it.isNotEmpty() } ?: return null
        return titleOfMix(mixId) to songs
    }

    /**
     * Plays a mix through [player] (the Play button of its card): the queue becomes the mix, and every pick is announced with its tags
     * (surface assistant, arm, served vector) so what is played reaches the event log as it does from Home. Main thread (the player's).
     */
    suspend fun playMix(mixId: String, player: TaizoPlayer?): Boolean {
        val p = player ?: return false
        val (title, songs) = songsOfMix(mixId) ?: return false
        val queueName = "Taizo: $title"
        backend.expectTags(queueName, songs)
        p.playSongs(songs, songs.first(), queueName)
        return true
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
            val what = describeChange(change, input.str("target").orEmpty())
            when {
                scope == "always" -> "Keep this for good: $what? You can undo it in Settings."
                untrusted -> "${what.replaceFirstChar { it.uppercase() }} for this queue?"
                else -> null
            }
        }
        // A station that starts after text from a web page or lyrics asks too: the page's say-so is not the listener's.
        TaizoTools.START_STATION -> if (untrusted) "Start a station from ${describeSeed(input.str("seed_kind").orEmpty(), input.str("seed_ref").orEmpty())}?" else null
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
        val unknownIds = ArrayList<String>()
        val query = AssistantRules.normalize(
            title = "", seedRefs = seeds, genreIds = grounding.knownGenreIds(input.ints("genre_ids"), unknownIds), moodIds = emptyList(),
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
            putSeedsNotUsed(rec.seedsNotUsed)
            if (rec.coldStart) put("note", "PixlAudio has little of the listener's history yet; these follow the radios' own order, not their taste.")
            putUnknownRefs(unknown)
            putUnknownIds(unknownIds)
        }
    }

    private suspend fun buildMix(input: JsonObject): JsonObject {
        val unknown = ArrayList<String>()
        val seeds = grounding.known(input.strings("seed_refs"), unknown)
        val excluded = grounding.known(input.strings("exclude_artist_refs"), unknown)
        val unknownIds = ArrayList<String>()
        val query = AssistantRules.normalize(
            title = input.str("title"), seedRefs = seeds,
            genreIds = grounding.knownGenreIds(input.ints("genre_ids"), unknownIds), moodIds = grounding.knownMoodIds(input.ints("mood_ids"), unknownIds),
            yearFrom = input.int("year_from"), yearTo = input.int("year_to"), energy = input.str("energy"), tempo = input.str("tempo"),
            novelty = null, noveltySharePct = input.intOr("novelty_share_pct", -1), count = input.intOr("length", AssistantRules.DEFAULT_MIX),
            excludeArtistRefs = excluded, minCount = AssistantRules.MIN_MIX, maxCount = AssistantRules.MAX_MIX, defaultCount = AssistantRules.DEFAULT_MIX,
        )
        val mix = backend.buildMix(query)
            ?: return buildJsonObject {
                put("built", false)
                put("note", "PixlAudio has nothing to build this from yet (no songs and no cached radios). Say so; do not make a list yourself.")
                putUnknownRefs(unknown)
                putUnknownIds(unknownIds)
            }
        remember(mix)
        val json = mixJson(mix)
        return buildJsonObject {
            json.forEach { (k, v) -> put(k, v) }
            putUnknownRefs(unknown)
            putUnknownIds(unknownIds)
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
            putSeedsNotUsed(mix.seedsNotUsed)
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
        val target = input.str("target").orEmpty().trim()
        if (AssistantRules.parseRef(target) != null && !grounding.isKnownRef(target)) {
            return buildJsonObject {
                put("applied", false)
                put("tail_changed", 0)
                put("note", "That ref was not returned by a tool in this chat. Call resolve_tracks first.")
            }
        }
        val outcome = backend.steer(input.str("change").orEmpty(), target, input.str("scope").orEmpty(), player)
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
        if (AssistantRules.parseRef(ref) != null && !grounding.isKnownRef(ref)) {
            return buildJsonObject {
                put("found", false)
                put("note", "That ref was not returned by a tool in this chat. Call resolve_tracks first; do not guess why.")
            }
        }
        val e = backend.explainTrack(ref, player)
            ?: return buildJsonObject {
                put("found", false)
                put("note", "PixlAudio has nothing recorded about that track. Say you can't tell why; do not guess.")
            }
        grounding.noteName(e.title)
        grounding.noteArtists(e.artist)
        e.whyArgs.forEach(grounding::noteName)
        // The credited primary artist, not a comma split of the display line ("Tyler, The Creator" is one artist).
        noteNamed(AssistantCards.NAMED_ARTIST, e.primaryArtist.ifBlank { e.artist.substringBefore(", ") })
        e.genres?.forEach { noteNamed(AssistantCards.NAMED_GENRE, it) }
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
                    if (e.genresSuggested) put("genres_note", "These genres were suggested by the assistant for a song no source knew; say they are a suggestion, not a fact.")
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
            // A genre the answer states (the genres list, or the summary's top genres) is something "Less X" can be about.
            if (t.scope == "genres") noteNamed(AssistantCards.NAMED_GENRE, line.name)
            else if (t.scope == "summary" && line.name == "top genre") noteNamed(AssistantCards.NAMED_GENRE, line.value)
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
                artists.forEachIndexed { index, a ->
                    add(buildJsonObject {
                        put("name", a.name)
                        if (a.ref.isNotEmpty()) put("ref", a.ref)
                        // A position, not a percentage: the closest one found is always first, and its edge may be a weak one.
                        put("rank", index + 1)
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

    private fun JsonObjectBuilder.putSeedsNotUsed(refs: List<String>) {
        if (refs.isEmpty()) return
        putJsonArray("seeds_not_used") { refs.forEach { add(it) } }
        put("seeds_note", "PixlAudio could not get songs similar to these seeds right now (offline, or today's lookups are used up), so the picks do not follow them. Tell the user; do not say the list is like them.")
    }

    private fun JsonObjectBuilder.putUnknownIds(unknown: List<String>) {
        if (unknown.isEmpty()) return
        putJsonArray("ids_ignored") { unknown.forEach { add(it) } }
        put("ids_note", "These genre or mood ids were not returned by lookup_genre in this chat, so they were left out. Call lookup_genre for the id first.")
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

    /** A ref, a genre id or a typed name, as the listener would read it: the artist of a ref, the name of a genre id. */
    private fun describeSeed(kind: String, seed: String): String {
        val t = seed.trim()
        if (t.isEmpty()) return "this"
        if (AssistantRules.parseRef(t) != null) {
            val track = grounding.trackOf(t)
            return if (track == null) "this song" else if (kind == "artist") artistOf(track.artist) else "${track.title} by ${artistOf(track.artist)}"
        }
        return t.toIntOrNull()?.let { grounding.genreName(it) } ?: t
    }

    private fun artistOf(display: String): String = TaizoGrounding.splitCredits(display).firstOrNull() ?: display

    private fun describeChange(change: String, target: String): String {
        val t = target.trim()
        val isRef = AssistantRules.parseRef(t) != null
        val name = when {
            t.isEmpty() -> "this"
            isRef -> grounding.trackOf(t)?.let { artistOf(it.artist) } ?: "this artist"
            else -> t.toIntOrNull()?.let { grounding.genreName(it) } ?: t
        }
        return when (change) {
            "more_artist" -> "play more of $name"
            "less_artist" -> "play less of $name"
            "more_genre" -> "play more of $name"
            "less_genre" -> "play less of $name"
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

        /** Mixes kept so their cards can still play, save and change. */
        const val MAX_MIXES = 16

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

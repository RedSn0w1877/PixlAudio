package com.theveloper.pixelplay.data.ai.claude.taizo.music

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.brain.AssistantRules
import com.theveloper.pixelplay.data.ai.claude.ToolInputValidator
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** The ten music tools of Ask Taizo (docs/brain-spec.md, 9.3) over a fake backend: what they return, what they refuse, what they never invent. */
class TaizoMusicToolsTest {
    private val backend = FakeMusicBackend()
    private val grounding = TaizoGrounding()
    private val tools = TaizoMusicTools(backend, grounding)

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun run(name: String, input: String): JsonObject = runBlocking { json(tools.run(name, json(input), null)) }

    private fun yt(n: Int) = "yt:" + "abcdefgh" + n.toString().padStart(3, '0')

    // ---- schemas ----------------------------------------------------------------------------------------

    @Test
    fun `the ten tools come after the sixteen, in a fixed order`() {
        assertThat(TaizoTools.legacyToolNames).hasSize(16)
        assertThat(TaizoTools.musicToolNames).containsExactly(
            "resolve_tracks", "recommend", "build_mix", "start_station", "steer_session",
            "explain_track", "explain_taste", "lookup_genre", "similar_artists", "new_releases",
        ).inOrder()
        assertThat(TaizoTools.clientToolNames).containsExactlyElementsIn(TaizoTools.legacyToolNames + TaizoTools.musicToolNames).inOrder()
        // The cached prefix: the first 16 never move.
        assertThat(TaizoTools.clientToolNames.take(16)).containsExactly(
            "search_library", "search_spotify_catalog", "play_songs", "add_to_queue", "playback_control", "create_playlist",
            "add_to_playlist", "get_listening_insights", "get_lyrics", "start_sing", "like_song", "get_library_overview", "get_taste_profile",
            "adjust_taste", "list_taste_adjustments", "remove_taste_adjustment",
        ).inOrder()
    }

    @Test
    fun `every music tool is closed, eager and has every parameter required, and the strict ones stay within the API's 20`() {
        // 15 older strict tools + 5 music ones = 20 (deviation 222): the five small read-only tools are validated locally instead.
        val strictMusic = TaizoTools.musicToolNames.filter { it !in TaizoTools.nonStrictToolNames }
        assertThat(strictMusic).containsExactly("resolve_tracks", "recommend", "build_mix", "start_station", "steer_session").inOrder()
        assertThat(TaizoTools.clientToolNames.count { it !in TaizoTools.nonStrictToolNames }).isAtMost(20)
        for (name in TaizoTools.musicToolNames) {
            val tool = TaizoTools.clientTools.getValue(name)
            if (name in strictMusic) assertThat(tool["strict"]).isEqualTo(JsonPrimitive(true)) else assertThat(tool["strict"]).isNull()
            assertThat(tool["eager_input_streaming"]).isEqualTo(JsonPrimitive(true))
            val schema = tool["input_schema"]!!.jsonObject
            assertThat(schema["additionalProperties"]).isEqualTo(JsonPrimitive(false))
            val properties = schema["properties"]!!.jsonObject.keys
            val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertThat(required).containsExactlyElementsIn(properties)
            // Nested objects (resolve_tracks items) are closed and fully required too.
            assertThat(nestedObjects(schema)).isNotNull()
        }
    }

    private fun nestedObjects(schema: JsonObject): Boolean {
        for ((_, prop) in schema["properties"]!!.jsonObject) {
            val items = (prop as JsonObject)["items"] as? JsonObject ?: continue
            if (items["type"]?.jsonPrimitive?.content == "object") {
                assertThat(items["additionalProperties"]).isEqualTo(JsonPrimitive(false))
                assertThat(items["required"]!!.jsonArray.map { it.jsonPrimitive.content })
                    .containsExactlyElementsIn(items["properties"]!!.jsonObject.keys)
            }
        }
        return true
    }

    @Test
    fun `the music tools spend none of the 24 optional parameters a strict grammar allows`() {
        fun optionalIn(schema: JsonObject): Int {
            val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
            var n = 0
            for ((name, prop) in schema["properties"]!!.jsonObject) {
                if (name !in required) n++
                val items = (prop as JsonObject)["items"] as? JsonObject
                if (items != null && items["type"]?.jsonPrimitive?.content == "object") n += optionalIn(items)
            }
            return n
        }
        var legacy = 0
        for (name in TaizoTools.legacyToolNames) {
            if (name in TaizoTools.nonStrictToolNames) continue
            legacy += optionalIn(TaizoTools.schemaOf(name)!!)
        }
        val music = TaizoTools.musicToolNames.sumOf { optionalIn(TaizoTools.schemaOf(it)!!) }
        assertThat(music).isEqualTo(0)
        assertThat(legacy + music).isAtMost(24)
        // The older tools use 20 of the 24 (the budget this stage was designed around): counted, so a change to either shows here.
        assertThat(legacy).isAtMost(20)
    }

    @Test
    fun `the sentinels and enums are the ones the pure rules use`() {
        fun enumOf(tool: String, prop: String): List<String> =
            TaizoTools.schemaOf(tool)!!["properties"]!!.jsonObject[prop]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(enumOf(TaizoTools.RECOMMEND, "energy")).containsExactlyElementsIn(AssistantRules.ENERGY).inOrder()
        assertThat(enumOf(TaizoTools.RECOMMEND, "novelty")).containsExactlyElementsIn(AssistantRules.NOVELTY).inOrder()
        assertThat(enumOf(TaizoTools.BUILD_MIX, "tempo")).containsExactlyElementsIn(AssistantRules.TEMPO).inOrder()
        assertThat(enumOf(TaizoTools.START_STATION, "seed_kind")).containsExactlyElementsIn(AssistantRules.SEED_KINDS).inOrder()
        assertThat(enumOf(TaizoTools.START_STATION, "mode")).containsExactlyElementsIn(AssistantRules.STATION_MODES).inOrder()
        assertThat(enumOf(TaizoTools.STEER_SESSION, "change")).containsExactlyElementsIn(AssistantRules.STEER_CHANGES).inOrder()
        assertThat(enumOf(TaizoTools.STEER_SESSION, "scope")).containsExactlyElementsIn(AssistantRules.STEER_SCOPES).inOrder()
        assertThat(enumOf(TaizoTools.EXPLAIN_TASTE, "scope")).containsExactlyElementsIn(AssistantRules.EXPLAIN_SCOPES).inOrder()
        assertThat(enumOf(TaizoTools.NEW_RELEASES, "scope")).containsExactlyElementsIn(AssistantRules.RELEASE_SCOPES).inOrder()
    }

    @Test
    fun `an input with a missing parameter or a stray one is refused before it runs`() {
        val schema = TaizoTools.schemaOf(TaizoTools.RECOMMEND)!!
        val ok = """{"seed_refs":[],"genre_ids":[],"year_from":0,"year_to":0,"energy":"any","novelty":"any","count":10}"""
        assertThat(ToolInputValidator.validate(schema, Json.parseToJsonElement(ok))).isNull()
        assertThat(ToolInputValidator.validate(schema, Json.parseToJsonElement(ok.replace(""","count":10""", "")))).contains("count is required")
        assertThat(ToolInputValidator.validate(schema, Json.parseToJsonElement(ok.replace("\"count\":10", "\"count\":10,\"mood\":\"x\"")))).contains("not an allowed property")
        assertThat(ToolInputValidator.validate(schema, Json.parseToJsonElement(ok.replace("\"any\",\"novelty\"", "\"loud\",\"novelty\"")))).contains("one of")
        assertThat(ToolInputValidator.validate(schema, Json.parseToJsonElement(ok.replace("\"genre_ids\":[]", "\"genre_ids\":[\"x\"]")))).contains("integer")
        val resolve = TaizoTools.schemaOf(TaizoTools.RESOLVE_TRACKS)!!
        assertThat(ToolInputValidator.validate(resolve, Json.parseToJsonElement("""{"items":[{"title":"A","artist":""}]}"""))).isNull()
        assertThat(ToolInputValidator.validate(resolve, Json.parseToJsonElement("""{"items":[{"title":"A"}]}"""))).contains("artist is required")
    }

    // ---- resolve_tracks ---------------------------------------------------------------------------------

    @Test
    fun `resolve returns refs for what is found and says nothing about what is not`() {
        backend.add(yt(1), "Espresso", "Sabrina Carpenter", 2024)
        val out = run(
            TaizoTools.RESOLVE_TRACKS,
            """{"items":[{"title":"Espresso","artist":"Sabrina Carpenter"},{"title":"A Song That Does Not Exist","artist":"Nobody"}]}""",
        )
        val results = out["results"]!!.jsonArray
        assertThat(results).hasSize(2)
        assertThat(results[0].jsonObject["ref"]!!.jsonPrimitive.content).isEqualTo(yt(1))
        assertThat(results[0].jsonObject["year"]!!.jsonPrimitive.intOrNull).isEqualTo(2024)
        assertThat(results[1].jsonObject["not_found"]!!.jsonPrimitive.content).isEqualTo("true")
        // The name that was not found is not echoed, so it can never count as "returned by a tool".
        assertThat(out.toString()).doesNotContain("Does Not Exist")
        assertThat(grounding.check("Try [[Espresso]] by [[Sabrina Carpenter]].", "(?)").ok).isTrue()
        assertThat(grounding.check("Try [[A Song That Does Not Exist]].", "(?)").ok).isFalse()
        assertThat(grounding.isKnownRef(yt(1))).isTrue()
    }

    @Test
    fun `resolve takes at most 25 items a call`() {
        val items = (1..40).joinToString(",") { """{"title":"T$it","artist":"A"}""" }
        val out = run(TaizoTools.RESOLVE_TRACKS, """{"items":[$items]}""")
        assertThat(out["results"]!!.jsonArray).hasSize(25)
        assertThat(out["ignored_over_limit"]!!.jsonPrimitive.intOrNull).isEqualTo(15)
        assertThat(backend.resolveCalls.single()).hasSize(25)
    }

    @Test
    fun `a turn may start only so many lookups, and the day's budget stops the rest`() {
        val items = { from: Int -> (from until from + 25).joinToString(",") { """{"title":"T$it","artist":"A"}""" } }
        run(TaizoTools.RESOLVE_TRACKS, """{"items":[${items(0)}]}""")
        // 25 used of 40: the next call may look up 15, the other 10 are skipped without asking the backend.
        val second = run(TaizoTools.RESOLVE_TRACKS, """{"items":[${items(100)}]}""")
        assertThat(backend.resolveCalls[1]).hasSize(15)
        val skipped = second["results"]!!.jsonArray.count { it.jsonObject["reason"] != null }
        assertThat(skipped).isEqualTo(10)
        assertThat(tools.budgetSpentThisTurn).isTrue()
        // A new turn starts again.
        tools.beginTurn()
        assertThat(tools.budgetSpentThisTurn).isFalse()
        run(TaizoTools.RESOLVE_TRACKS, """{"items":[{"title":"X","artist":"A"}]}""")
        assertThat(backend.resolveCalls.last()).hasSize(1)
    }

    @Test
    fun `when the backend says the day's requests are spent the items are reported as not looked up`() {
        backend.skipAfter = 1
        backend.add(yt(1), "One", "A")
        val out = run(TaizoTools.RESOLVE_TRACKS, """{"items":[{"title":"One","artist":"A"},{"title":"Two","artist":"A"}]}""")
        val r = out["results"]!!.jsonArray
        assertThat(r[0].jsonObject["ref"]).isNotNull()
        assertThat(r[1].jsonObject["not_found"]).isNotNull()
        assertThat(r[1].jsonObject["reason"]!!.jsonPrimitive.content).contains("used up")
        assertThat(tools.budgetSpentThisTurn).isTrue()
    }

    // ---- recommend / build_mix --------------------------------------------------------------------------

    @Test
    fun `build_mix cleans the request, ignores refs no tool returned, and fills the card from the engine`() {
        val a = backend.add(yt(1), "Espresso", "Sabrina Carpenter", 2024)
        val b = backend.add(yt(2), "Good Luck, Babe!", "Chappell Roan", 2024)
        backend.mixTracks = listOf(a, b)
        grounding.noteTrack(a)
        val out = run(
            TaizoTools.BUILD_MIX,
            """{"title":"  Late   drive\n","seed_refs":["${yt(1)}","${yt(99)}"],"genre_ids":[5,5,0,7],"mood_ids":[],"year_from":2024,"year_to":2000,
                "energy":"lower","tempo":"slow","novelty_share_pct":150,"length":500,"exclude_artist_refs":[]}""".replace("\n", " "),
        )
        val q = backend.mixQueries.single()
        assertThat(q.title).isEqualTo("Late drive")
        assertThat(q.seedRefs).containsExactly(yt(1))
        assertThat(q.genreIds).containsExactly(5, 7).inOrder()
        assertThat(q.yearFrom).isEqualTo(2000)
        assertThat(q.yearTo).isEqualTo(2024)
        assertThat(q.noveltySharePct).isEqualTo(100)
        assertThat(q.count).isEqualTo(AssistantRules.MAX_MIX)
        assertThat(q.energy).isEqualTo("lower")
        assertThat(out["built"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(out["mix_id"]!!.jsonPrimitive.content).isEqualTo("m1")
        assertThat(out["total"]!!.jsonPrimitive.intOrNull).isEqualTo(2)
        assertThat(out["refs_ignored"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly(yt(99))
        assertThat(out["note"]!!.jsonPrimitive.content).contains("mix:m1")
        // Both tracks were returned, so both may be named; one nobody returned may not.
        assertThat(grounding.check("[[Espresso]] and [[Good Luck, Babe!]]", "(?)").ok).isTrue()
        assertThat(grounding.check("[[Please Please Please]]", "(?)").ok).isFalse()
    }

    private fun buildLateDrive(): JsonObject = run(
        TaizoTools.BUILD_MIX,
        """{"title":"Late drive","seed_refs":[],"genre_ids":[],"mood_ids":[],"year_from":0,"year_to":0,"energy":"any","tempo":"any","novelty_share_pct":-1,"length":30,"exclude_artist_refs":[]}""",
    )

    @Test
    fun `a built mix gives the chat a card with the words of its chips, made from the engine's answer`() {
        val a = backend.add(yt(1), "Espresso", "Sabrina Carpenter", 2024)
        val b = backend.add(yt(2), "Good Luck, Babe!", "Chappell Roan", 2024)
        backend.mixTracks = listOf(a, b)
        backend.topArtist = yt(1) to "Sabrina Carpenter"
        backend.topGenre = 7 to "R&B"
        buildLateDrive()
        val card = tools.mixCard("m1")!!
        assertThat(card.title).isEqualTo("Late drive")
        assertThat(card.total).isEqualTo(2)
        assertThat(card.preview.map { it.title }).containsExactly("Espresso", "Good Luck, Babe!").inOrder()
        assertThat(card.chips.map { it.label }).containsExactly("Less Sabrina Carpenter", "More R&B", "Shorter", "Slower", "More new", "More familiar").inOrder()
        // A mix this chat never built has no card.
        assertThat(tools.mixCard("zz")).isNull()
    }

    @Test
    fun `a chip changes the stored request on the phone, with no model call, and the new tracks may be named`() {
        val a = backend.add(yt(1), "Espresso", "Sabrina Carpenter", 2024)
        val b = backend.add(yt(2), "Good Luck, Babe!", "Chappell Roan", 2024)
        backend.mixTracks = listOf(a, b)
        backend.topArtist = yt(1) to "Sabrina Carpenter"
        buildLateDrive()
        val next = runBlocking { tools.adjustMix("m1", AssistantRules.Chip(AssistantRules.CHIP_SLOWER)) }!!
        assertThat(backend.chipCalls.single().first).isEqualTo("m1")
        assertThat(backend.mixes.getValue(next.mixId).query.tempo).isEqualTo("slow")
        // The slower mix no longer offers "Slower"; the card of the new mix is the one to use now.
        assertThat(next.chips.map { it.label }).doesNotContain("Slower")
        assertThat(tools.mixCard(next.mixId)).isNotNull()
        // A chip for a mix nobody built is refused, not guessed.
        assertThat(runBlocking { tools.adjustMix("nope", AssistantRules.Chip(AssistantRules.CHIP_SHORTER)) }).isNull()
    }

    @Test
    fun `an explanation names the genres and artists a Less chip may be about, and only those`() {
        backend.taste = TasteExplanation("genres", listOf(TasteLine("Trap", "31% of plays"), TasteLine("Pop", "12% of plays")), emptyList())
        tools.beginTurn()
        run(TaizoTools.EXPLAIN_TASTE, """{"scope":"genres"}""")
        val named = tools.namedThisTurn()
        assertThat(named.map { it.name }).containsExactly("Trap", "Pop")
        assertThat(com.theveloper.pixelplay.brain.AssistantCards.lessTarget("why do I keep getting trap?", named)?.name).isEqualTo("Trap")
        // A new turn starts clean: a name from an earlier answer is not offered again.
        tools.beginTurn()
        assertThat(tools.namedThisTurn()).isEmpty()
        // A summary names its top genres; a rule-less line (a count) names nothing.
        backend.taste = TasteExplanation("summary", listOf(TasteLine("plays recorded", "212"), TasteLine("top genre", "Hip hop", "40% of plays")), emptyList())
        run(TaizoTools.EXPLAIN_TASTE, """{"scope":"summary"}""")
        assertThat(tools.namedThisTurn().map { it.name }).containsExactly("Hip hop")
    }

    @Test
    fun `build_mix says so when the engine has nothing to build from, and never makes a list itself`() {
        val out = run(
            TaizoTools.BUILD_MIX,
            """{"title":"x","seed_refs":[],"genre_ids":[],"mood_ids":[],"year_from":0,"year_to":0,"energy":"any","tempo":"any","novelty_share_pct":-1,"length":30,"exclude_artist_refs":[]}""",
        )
        assertThat(out["built"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(out["tracks"]).isNull()
        assertThat(backend.mixQueries.single().noveltySharePct).isEqualTo(AssistantRules.SHARE_UNSET)
    }

    @Test
    fun `a mix lists at most 25 tracks but reports how many it holds`() {
        backend.mixTracks = (1..40).map { backend.add(yt(it), "Song $it", "Artist") }
        val out = run(
            TaizoTools.BUILD_MIX,
            """{"title":"big","seed_refs":[],"genre_ids":[],"mood_ids":[],"year_from":0,"year_to":0,"energy":"any","tempo":"any","novelty_share_pct":-1,"length":40,"exclude_artist_refs":[]}""",
        )
        assertThat(out["total"]!!.jsonPrimitive.intOrNull).isEqualTo(40)
        assertThat(out["tracks"]!!.jsonArray).hasSize(25)
        // The count the tool vouches for is a number the reply may state.
        assertThat(grounding.check("I made 40 songs for you.", "(?)").ok).isTrue()
        assertThat(grounding.check("I made 41 songs for you.", "(?)").ok).isFalse()
    }

    @Test
    fun `recommend reports filters the data could not answer instead of ignoring them silently`() {
        backend.mixTracks = listOf(backend.add(yt(1), "Song", "Artist"))
        backend.unsupported = listOf("tempo")
        val out = run(
            TaizoTools.RECOMMEND,
            """{"seed_refs":[],"genre_ids":[],"year_from":0,"year_to":0,"energy":"any","novelty":"new","count":10}""",
        )
        assertThat(out["filters_not_applied"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("tempo")
        assertThat(out["picks"]!!.jsonArray).hasSize(1)
        assertThat(out["picks"]!!.jsonArray[0].jsonObject["why_key"]!!.jsonPrimitive.content).isEqualTo("radio_fans")
        assertThat(backend.recommendQueries.single().novelty).isEqualTo("new")
    }

    // ---- explain_* --------------------------------------------------------------------------------------

    @Test
    fun `explain_track lists what it does not know and invents none of it`() {
        backend.explanation = TrackExplanation(
            ref = yt(1), title = "Song", artist = "Artist", arm = null, whyKey = null, whyArgs = emptyList(), factors = null,
            genres = null, year = 0, plays = null, completionRate = null,
        )
        val out = run(TaizoTools.EXPLAIN_TRACK, """{"ref":"${yt(1)}"}""")
        assertThat(out["found"]!!.jsonPrimitive.content).isEqualTo("true")
        val unknown = out["unknown"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(unknown).containsAtLeast("arm", "why", "top_factors", "genres", "year", "plays", "completion_rate")
        assertThat(out["arm"]).isNull()
        assertThat(out["top_factors"]).isNull()
        val facts = out["facts"]!!.jsonObject
        assertThat(facts["plays"]).isNull()
        assertThat(facts["year"]).isNull()
        assertThat(facts["completion_rate_pct"]).isNull()
    }

    @Test
    fun `explain_track reports factors by name and direction only, with the real numbers it has`() {
        backend.explanation = TrackExplanation(
            ref = yt(1), title = "Song", artist = "Artist", arm = "RADIO", whyKey = "radio_fans", whyArgs = listOf("Sabrina Carpenter"),
            factors = listOf(AssistantRules.Factor("you play this artist", true), AssistantRules.Factor("it is far from what you usually play", false)),
            genres = listOf("Pop"), year = 2024, plays = 12, completionRate = 0.75,
        )
        val out = run(TaizoTools.EXPLAIN_TRACK, """{"ref":"${yt(1)}"}""")
        val factors = out["top_factors"]!!.jsonArray
        assertThat(factors[0].jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("you play this artist")
        assertThat(factors[0].jsonObject["direction"]!!.jsonPrimitive.content).isEqualTo("up")
        assertThat(factors[1].jsonObject["direction"]!!.jsonPrimitive.content).isEqualTo("down")
        assertThat(out.toString()).doesNotContain("weight")
        assertThat(out["facts"]!!.jsonObject["completion_rate_pct"]!!.jsonPrimitive.intOrNull).isEqualTo(75)
        assertThat(out["unknown"]!!.jsonArray).isEmpty()
        assertThat(grounding.check("You played it 12 times and finished 75% of them.", "(?)").ok).isTrue()
        assertThat(grounding.check("You played it 30 times.", "(?)").ok).isFalse()
    }

    @Test
    fun `explain_track for a track nothing is recorded about says so`() {
        backend.explanation = null
        val out = run(TaizoTools.EXPLAIN_TRACK, """{"ref":"${yt(5)}"}""")
        assertThat(out["found"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(out["note"]!!.jsonPrimitive.content).contains("do not guess")
    }

    @Test
    fun `explain_taste passes on the facts and the unknowns as they are`() {
        backend.taste = TasteExplanation(
            "genres",
            listOf(TasteLine("V-pop", "31% of plays"), TasteLine("Hip hop", "12% of plays")),
            listOf("Taste islands need about 20 plays in the last 28 days."),
        )
        val out = run(TaizoTools.EXPLAIN_TASTE, """{"scope":"genres"}""")
        assertThat(out["facts"]!!.jsonArray).hasSize(2)
        assertThat(out["unknown"]!!.jsonArray).hasSize(1)
        assertThat(grounding.check("About [[V-pop]]: 31% of what you play.", "(?)").ok).isTrue()
        assertThat(grounding.check("You play 55% trap.", "(?)").ok).isFalse()
    }

    // ---- the rest ---------------------------------------------------------------------------------------

    @Test
    fun `lookup_genre returns the atlas facts, or the nearest names and no id of its own`() {
        backend.genre = GenreLookup(
            GenreInfo(
                7, "Vietnamese pop", "Pop sung in Vietnamese.", listOf(GenreRef(1, "Pop")), emptyList(), listOf(GenreRef(8, "V-rap")),
                emptyList(), "Vietnam", 1990, listOf("upbeat"),
            ),
            emptyList(),
        )
        val found = run(TaizoTools.LOOKUP_GENRE, """{"query":"v-pop"}""")
        assertThat(found["id"]!!.jsonPrimitive.intOrNull).isEqualTo(7)
        assertThat(found["kind"]!!.jsonPrimitive.content).isEqualTo("genre")
        assertThat(found["parents"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("Pop")
        backend.genre = GenreLookup(null, listOf(GenreRef(3, "Trap"), GenreRef(4, "Trap soul")))
        val miss = run(TaizoTools.LOOKUP_GENRE, """{"query":"trapp"}""")
        assertThat(miss["found"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(miss["id"]).isNull()
        assertThat(miss["nearest"]!!.jsonArray).hasSize(2)
    }

    @Test
    fun `similar_artists and new_releases are capped at 25 and carry their refs`() {
        backend.similar = SimilarArtists((1..40).map { SimilarArtist("Artist $it", yt(it), 1.0 - it / 100.0, listOf("radio")) })
        val s = run(TaizoTools.SIMILAR_ARTISTS, """{"artist":"X","count":50,"only_unplayed":false}""")
        assertThat(s["artists"]!!.jsonArray).hasSize(25)
        assertThat(s["artists"]!!.jsonArray[0].jsonObject["ref"]!!.jsonPrimitive.content).isEqualTo(yt(1))
        backend.releases = ReleaseList((1..30).map { ReleaseInfo("Album $it", "Artist", "album", 2026, yt(it)) })
        val r = run(TaizoTools.NEW_RELEASES, """{"scope":"my_artists","count":10}""")
        assertThat(r["releases"]!!.jsonArray).hasSize(10)
        assertThat(grounding.check("[[Artist 3]] is close, and so is [[Artist 99]].", "(?)").ungroundedNames).containsExactly("Artist 99")
    }

    @Test
    fun `a station from a song needs a ref a tool returned`() {
        val made = run(TaizoTools.START_STATION, """{"seed_ref":"${yt(1)}","seed_kind":"song","mode":"blend"}""")
        assertThat(made["started"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(backend.stationCalls).isEmpty()
        val name = run(TaizoTools.START_STATION, """{"seed_ref":"Espresso","seed_kind":"song","mode":"blend"}""")
        assertThat(name["reason"]!!.jsonPrimitive.content).contains("ref")
        val info = backend.add(yt(1), "Espresso", "Sabrina Carpenter")
        grounding.noteTrack(info)
        backend.stationOutcome = StationOutcome(true, info)
        val ok = run(TaizoTools.START_STATION, """{"seed_ref":"${yt(1)}","seed_kind":"song","mode":"discover"}""")
        assertThat(ok["started"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(backend.stationCalls.single()).isEqualTo(Triple(yt(1), "song", "discover"))
        assertThat(ok["first_track"]!!.jsonObject["title"]!!.jsonPrimitive.contentOrNull).isEqualTo("Espresso")
        // An artist or a genre is named, not a ref.
        run(TaizoTools.START_STATION, """{"seed_ref":"Radiohead","seed_kind":"artist","mode":"blend"}""")
        assertThat(backend.stationCalls.last().second).isEqualTo("artist")
    }

    @Test
    fun `steering for good asks the user to confirm, steering the queue does not`() {
        val always = json("""{"change":"less_artist","target":"${yt(1)}","scope":"always"}""")
        val session = json("""{"change":"energy_down","target":"","scope":"session"}""")
        assertThat(tools.confirmationFor(TaizoTools.STEER_SESSION, always, untrusted = false)).contains("for good")
        assertThat(tools.confirmationFor(TaizoTools.STEER_SESSION, session, untrusted = false)).isNull()
        // After text from a web page the model's say-so is not enough, even for the queue.
        assertThat(tools.confirmationFor(TaizoTools.STEER_SESSION, session, untrusted = true)).isNotNull()
        assertThat(tools.confirmationFor(TaizoTools.RECOMMEND, session, untrusted = true)).isNull()
    }

    @Test
    fun `steer reports what changed and what it could not`() {
        backend.steerOutcome = SteerOutcome(true, 5, "Put 5 songs next.")
        val out = run(TaizoTools.STEER_SESSION, """{"change":"energy_down","target":"","scope":"session"}""")
        assertThat(out["applied"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(out["tail_changed"]!!.jsonPrimitive.intOrNull).isEqualTo(5)
        assertThat(backend.steerCalls.single()).isEqualTo(Triple("energy_down", "", "session"))
    }
}

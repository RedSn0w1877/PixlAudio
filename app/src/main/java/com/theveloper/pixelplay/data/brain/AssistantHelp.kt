package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.AssistBatch
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.GenreAtlas
import com.theveloper.pixelplay.brain.Islands
import com.theveloper.pixelplay.data.ai.AiHandler
import com.theveloper.pixelplay.data.ai.claude.ClaudeConfig
import com.theveloper.pixelplay.data.ai.claude.ClaudeEffort
import com.theveloper.pixelplay.data.ai.claude.ClaudeService
import com.theveloper.pixelplay.data.ai.claude.taizo.music.BrainAssistantEngine
import com.theveloper.pixelplay.data.ai.local.AiRoute
import com.theveloper.pixelplay.data.brain.store.ArtistDnaDao
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * The optional batch jobs of "Let Taizo help the engine" (docs/brain-spec.md, 9.4, stage 4 milestone 2). ONE class, run from inside the
 * existing refresh worker ([RefreshWork.assistantHelp]), never a second job, never at app start:
 *  1. names and one-line descriptions for the taste islands (one call for all islands, only for islands whose genres changed);
 *  2. genres for tracks no source knows (tag source 10): 50 tracks a call, each constrained to a shortlist of 30 atlas ids;
 *  3. artists this listener likely does not know, one well-known song each, looked up like `resolve_tracks` does (arm ASK).
 *
 * Off unless the listener turned the switch on AND the remote config does not switch it off; and only on the automatic route with the built-in
 * key, a connection and monthly budget left ([ClaudeService.canUseNow]), the same door and the same budget every assistant feature uses.
 * Every answer is data: [AssistBatch] validates it against the atlas and the island, an invalid id or name is dropped and never shown, and what
 * it kept is cached forever by (model id, prompt version, input hash) in [AssistCache], so the same input is never asked twice. A call is made
 * only when something is missing from the cache; with the switch off nothing is asked, and what the tagging job wrote is taken back out.
 */
@Singleton
class AssistantHelp @Inject constructor(
    private val switch: BrainSwitch,
    private val claude: ClaudeService,
    private val aiHandler: AiHandler,
    private val cache: AssistCache,
    private val islandStore: IslandStore,
    private val atlasProvider: AtlasProvider,
    private val trackDnaDao: TrackDnaDao,
    private val artistDnaDao: ArtistDnaDao,
    private val yourSound: dagger.Lazy<YourSoundRepository>,
    private val assistant: BrainAssistantEngine,
    private val playableStore: PlayableStore,
    private val clock: BrainClock,
) {
    class Report(val islandsNamed: Int, val tracksTagged: Int, val songsSuggested: Int, val calls: Int, val tagsTakenBack: Int)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun run(playing: () -> Boolean): Report {
        // Switched off: the tags the job wrote leave with it (the names and suggestions are not read while it is off).
        if (!switch.assistHelpAllowed()) return Report(0, 0, 0, 0, takeBackTags())
        if (playing() || !mayAsk()) return Report(0, 0, 0, 0, 0)
        val atlas = atlasProvider.atlas() ?: return Report(0, 0, 0, 0, 0)
        var calls = 0
        var islands = 0
        var tagged = 0
        var songs = 0
        val set = islandStore.current()
        val inputs = if (set == null) emptyList() else islandInputs(set.islands, atlas)
        if (inputs.isNotEmpty() && !playing() && mayAsk()) {
            val r = nameIslands(inputs)
            calls += r.first
            islands = r.second
        }
        if (!playing() && mayAsk()) {
            val r = tagTracks(atlas)
            calls += r.first
            tagged = r.second
        }
        if (inputs.isNotEmpty() && !playing() && mayAsk()) {
            val r = suggestSongs(inputs)
            calls += r.first
            songs = r.second
        }
        return Report(islands, tagged, songs, calls, 0)
    }

    /** The assistant's own route (consent and switches), a connection and the monthly budget: asked before every call. */
    private suspend fun mayAsk(): Boolean = switch.assistHelpAllowed() && aiHandler.currentRoute() is AiRoute.Claude && claude.canUseNow()

    // ---- islands --------------------------------------------------------------------------------------------

    private class IslandInput(val id: Int, val genreNames: List<String>, val artists: List<String>) {
        fun key(): String = AssistCache.islandInput(id, genreNames)
    }

    private suspend fun islandInputs(islands: List<Islands.Island>, atlas: GenreAtlas): List<IslandInput> {
        val out = ArrayList<IslandInput>()
        for (island in islands.take(MAX_ISLANDS)) {
            val genres = island.topTags.toList().mapNotNull { AtlasNames.genreName(atlas, it) }.distinct().take(5)
            if (genres.isEmpty()) continue
            val artists = if (island.topArtists.isEmpty()) emptyList()
            else artistDnaDao.getAll(island.topArtists.toList()).map { it.name }.filter { AtlasNames.isShown(it) }.take(3)
            out.add(IslandInput(island.id, genres, artists))
        }
        return out
    }

    private suspend fun nameIslands(inputs: List<IslandInput>): Pair<Int, Int> {
        val missing = inputs.filter { cache.read().islands[cache.keyOf("island", it.key())] == null }
        if (missing.isEmpty()) return 0 to 0
        val prompt = buildString {
            for (i in missing) append("id=").append(i.id).append("; genres=").append(i.genreNames.joinToString(", ")).append("; artists=").append(i.artists.joinToString(", ")).append('\n')
        }
        val text = ask(ISLAND_SYSTEM, prompt, MAX_TOKENS_ISLANDS) ?: return 1 to 0
        val outs = ArrayList<AssistBatch.IslandName>()
        for (el in objectsOf(text, "islands")) {
            val id = el["id"]?.jsonPrimitive?.intOrNull ?: continue
            outs.add(AssistBatch.IslandName(id, el["name"]?.jsonPrimitive?.content.orEmpty(), el["line"]?.jsonPrimitive?.content.orEmpty()))
        }
        val kept = AssistBatch.validateIslandNames(missing.map { AssistBatch.IslandIn(it.id, it.genreNames, it.artists) }, outs).associateBy { it.id }
        cache.update { d ->
            // Every island that was asked is stored: a valid answer by its name, any other as "asked, nothing usable".
            val next = d.islands.toMutableMap()
            for (i in missing) {
                val k = kept[i.id]
                next[cache.keyOf("island", i.key())] = AssistCache.IslandAnswer(k?.name.orEmpty(), k?.line.orEmpty())
            }
            AssistCache.Data(next, d.tags, d.ask, d.askLatest, d.tagRids)
        }
        return 1 to kept.size
    }

    // ---- genres for tracks no source knows ------------------------------------------------------------------

    private class TagTrack(val index: Int, val rid: Long, val title: String, val artist: String, val shortlist: IntArray, val key: String)

    private suspend fun tagTracks(atlas: GenreAtlas): Pair<Int, Int> {
        val listenerGenres = runCatching { yourSound.get().load().genres.map { it.atlasId } }.getOrDefault(emptyList())
        var calls = 0
        var tagged = 0
        var after = 0L
        var scanned = 0
        val chosen = ArrayList<TagTrack>()
        while (chosen.size < TAG_TRACKS_PER_RUN && scanned < TAG_SCAN_LIMIT) {
            val rows = trackDnaDao.untagged(after, TAG_SCAN_PAGE)
            if (rows.isEmpty()) break
            val countries = artistDnaDao.getAll(rows.flatMap { r -> r.artistIds.split(',').mapNotNull { it.toLongOrNull() }.take(1) }.distinct()).associate { it.aid to it.country }
            for (row in rows) {
                after = row.rid
                scanned++
                if (row.title.isBlank() || row.artistDisplay.isBlank()) continue
                val country = countries[row.artistIds.split(',').firstOrNull()?.toLongOrNull() ?: 0L].orEmpty()
                val shortlist = shortlistFor(atlas, listenerGenres, country)
                if (shortlist.size < MIN_SHORTLIST) continue
                val input = "${row.title}|${row.artistDisplay}"
                val key = cache.keyOf("tag", input)
                if (cache.read().tags.containsKey(key)) continue
                chosen.add(TagTrack(chosen.size, row.rid, row.title, row.artistDisplay, shortlist, key))
                if (chosen.size >= TAG_TRACKS_PER_RUN) break
            }
            if (rows.size < TAG_SCAN_PAGE) break
        }
        for (batch in chosen.chunked(AssistBatch.TAG_BATCH)) {
            if (!mayAsk()) break
            val prompt = buildString {
                for ((n, t) in batch.withIndex()) {
                    append(n).append(": ").append(t.title).append(" - ").append(t.artist).append(" | allowed: ")
                    append(t.shortlist.joinToString(", ") { id -> "$id ${atlas.genre(id)?.name.orEmpty()}" }).append('\n')
                }
            }
            calls++
            val text = ask(TAG_SYSTEM, prompt, MAX_TOKENS_TAGS)
            val answers = ArrayList<AssistBatch.TagOut>()
            if (text != null) {
                for (el in objectsOf(text, "tracks")) {
                    val i = el["i"]?.jsonPrimitive?.intOrNull ?: continue
                    for (t in (el["tags"] as? JsonArray).orEmpty()) {
                        val o = t as? JsonObject ?: continue
                        val id = o["id"]?.jsonPrimitive?.intOrNull ?: continue
                        val c = o["c"]?.jsonPrimitive?.doubleOrNull ?: continue
                        answers.add(AssistBatch.TagOut(i, id, c))
                    }
                }
            }
            if (text == null) continue
            val kept = AssistBatch.validateTags(batch.map { it.shortlist }, answers).groupBy { it.index }
            val now = clock.nowMs()
            val written = ArrayList<Long>()
            val byKey = HashMap<String, List<AssistCache.TagAnswer>>()
            for ((n, t) in batch.withIndex()) {
                val claims = kept[n].orEmpty().map { AssistCache.TagAnswer(it.atlasId, (it.confidence * 255.0 + 0.5).toInt().coerceIn(0, 255)) }
                byKey[t.key] = claims
                if (claims.isEmpty()) continue
                val row = trackDnaDao.get(t.rid) ?: continue
                val updated = DnaAssembler.withAssistantClaims(
                    row, claims.map { DnaAssembler.Claim(DnaAssembler.P_ASSISTANT, it.atlasId, it.conf) }, atlas, row.atlasVersion, now,
                )
                trackDnaDao.upsert(updated)
                written.add(t.rid)
                tagged++
            }
            cache.update { d -> AssistCache.Data(d.islands, d.tags + byKey, d.ask, d.askLatest, (d.tagRids + written).distinct()) }
        }
        return calls to tagged
    }

    /** At most 30 atlas ids for one track: the genres the listener plays, then genres of the artist's own region, each by id order within its group. */
    private fun shortlistFor(atlas: GenreAtlas, listenerGenres: List<Int>, country: String): IntArray {
        val out = LinkedHashSet<Int>()
        for (id in listenerGenres) {
            if (atlas.genre(id) != null && AtlasNames.genreName(atlas, id) != null) out.add(id)
            if (out.size >= SHORTLIST_LISTENER) break
        }
        val c = BrainText.fold(country).trim()
        if (c.isNotEmpty()) {
            for (id in atlas.allIds) {
                if (out.size >= AssistBatch.SHORTLIST) break
                val g = atlas.genre(id) ?: continue
                if (g.depth < 1 || AtlasNames.genreName(atlas, id) == null) continue
                if (g.regions.any { BrainText.fold(it).trim() == c }) out.add(id)
            }
        }
        return out.take(AssistBatch.SHORTLIST).toIntArray()
    }

    /** Takes the assistant's claims out of every row the job wrote (the switch was turned off). Returns how many rows changed. */
    private suspend fun takeBackTags(): Int {
        val rids = cache.read().tagRids
        if (rids.isEmpty()) return 0
        val atlas = atlasProvider.atlas() ?: return 0
        var changed = 0
        val now = clock.nowMs()
        for (chunk in rids.chunked(200)) {
            for (row in trackDnaDao.getAll(chunk)) {
                trackDnaDao.upsert(DnaAssembler.withAssistantClaims(row, emptyList(), atlas, row.atlasVersion, now))
                changed++
            }
        }
        cache.update { d -> AssistCache.Data(d.islands, d.tags, d.ask, d.askLatest, emptyList()) }
        return changed
    }

    // ---- artists the listener likely does not know -----------------------------------------------------------

    private suspend fun suggestSongs(inputs: List<IslandInput>): Pair<Int, Int> {
        val week = clock.nowMs() / WEEK_MS
        val input = "week=$week|" + inputs.joinToString("|") { it.key() }
        val key = cache.keyOf("ask", input)
        if (cache.read().ask.containsKey(key)) return 0 to 0
        val prompt = buildString {
            for (i in inputs.take(MAX_ASK_ISLANDS)) append("id=").append(i.id).append("; genres=").append(i.genreNames.joinToString(", ")).append("; artists they play=").append(i.artists.joinToString(", ")).append('\n')
        }
        val text = ask(ASK_SYSTEM, prompt, MAX_TOKENS_ASK)
        // The week is marked asked at once (a failed call is not retried until next week).
        if (text == null) {
            cache.update { d -> AssistCache.Data(d.islands, d.tags, d.ask + (key to emptyList()), d.askLatest, d.tagRids) }
            return 1 to 0
        }
        val knownArtists = HashSet<String>()
        for (i in inputs) for (a in i.artists) knownArtists.add(BrainText.fold(a).trim())
        val wanted = ArrayList<Pair<String, String>>()
        for (el in objectsOf(text, "islands")) {
            val picks = (el["picks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val names = AssistBatch.validateArtistNames(picks.map { it["artist"]?.jsonPrimitive?.content.orEmpty() }, knownArtists)
            for (name in names.take(ASK_PER_ISLAND_LOOKUPS)) {
                val song = picks.firstOrNull { it["artist"]?.jsonPrimitive?.content?.trim() == name }?.get("song")?.jsonPrimitive?.content?.trim().orEmpty()
                if (song.isNotEmpty()) wanted.add(song to name)
            }
        }
        val kept = ArrayList<AssistCache.AskAnswer>()
        // Looked up the way `resolve_tracks` does it: the library and the alias table first (free), then one songs search under the day's YouTube budget.
        val looked = if (wanted.isEmpty()) emptyList() else assistant.resolve(wanted.take(ASK_LOOKUPS_PER_RUN))
        for (l in looked) {
            val t = l.track ?: continue
            if (t.inLibrary || !t.ref.startsWith("yt:")) continue
            val videoId = t.ref.removePrefix("yt:")
            val songId = SpotifyRepository.unifiedSongId(EngineTrack.synthIdOf(videoId)).toString()
            val p = playableStore.bySongId(songId) ?: continue
            kept.add(AssistCache.AskAnswer(videoId, p.title, p.artistDisplay, p.album.orEmpty(), t.year, (p.durationMs / 1000L).toInt(), p.artUrl.orEmpty(), p.rid))
        }
        cache.update { d -> AssistCache.Data(d.islands, d.tags, d.ask + (key to kept), key, d.tagRids) }
        return 1 to kept.size
    }

    // ---- the call ---------------------------------------------------------------------------------------------

    private suspend fun ask(system: String, prompt: String, maxTokens: Long): String? = try {
        claude.complete(system = system, prompt = prompt, effort = ClaudeEffort.LOW, maxTokens = maxTokens).text
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "brain: an assistant batch call failed")
        null
    }

    /** The objects of the array [name] in the first JSON object of [text]; empty for anything that does not parse. */
    private fun objectsOf(text: String, name: String): List<JsonObject> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObject[name]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_ISLANDS = 10
        private const val MAX_ASK_ISLANDS = 6
        private const val ASK_PER_ISLAND_LOOKUPS = 4
        private const val ASK_LOOKUPS_PER_RUN = 12
        private const val TAG_TRACKS_PER_RUN = 100
        private const val TAG_SCAN_PAGE = 200
        private const val TAG_SCAN_LIMIT = 1_000
        private const val MIN_SHORTLIST = 6
        private const val SHORTLIST_LISTENER = 18
        private const val MAX_TOKENS_ISLANDS = 900L
        private const val MAX_TOKENS_TAGS = 2_500L
        private const val MAX_TOKENS_ASK = 1_800L

        /** The model the answers were written by: part of every cache key, so a model change asks again. */
        val MODEL_ID: String get() = ClaudeConfig.MODEL

        private val ISLAND_SYSTEM = """
            You name groups of songs for a music app. Each line gives an island id, the genres it holds and some artists in it.
            Answer with JSON only, no other text: {"islands":[{"id":1,"name":"two to four words","line":"one short sentence"}]}.
            A name must use at least one word from that island's own genre names, must not be an artist's name, and must not use any other name.
            A line has no digits and no quotation marks. Do not mention any company or product.
        """.trimIndent()

        private val TAG_SYSTEM = """
            You give genres to songs. Each line gives an index, a song and artist, and the only genre ids you may use for it, with their names.
            Answer with JSON only, no other text: {"tracks":[{"i":0,"tags":[{"id":123,"c":0.7}]}]}.
            Use only ids from that line's own allowed list, at most three per song, c between 0.5 and 1. Give an empty tags list when you are not sure.
        """.trimIndent()

        private val ASK_SYSTEM = """
            You suggest music. Each line gives an island of one listener's taste: its genres and some artists they play.
            For each island suggest up to four real artists this listener likely does not know yet, each with one real, well-known song by them.
            Answer with JSON only, no other text: {"islands":[{"id":1,"picks":[{"artist":"name","song":"title"}]}]}.
            Never suggest an artist already listed. Do not mention any company or product.
        """.trimIndent()
    }
}

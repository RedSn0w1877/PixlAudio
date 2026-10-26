package com.theveloper.pixelplay.data.ai.claude.taizo

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.ai.claude.ToolInputValidator
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.TasteAction
import com.theveloper.pixelplay.data.recommendation.TasteAdjustment
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.recommendation.TasteChange
import com.theveloper.pixelplay.data.recommendation.TasteKind
import com.theveloper.pixelplay.data.recommendation.TasteOutcome
import com.theveloper.pixelplay.data.recommendation.TasteRequest
import com.theveloper.pixelplay.data.recommendation.TasteSetBy
import com.theveloper.pixelplay.data.recommendation.TasteTarget
import com.theveloper.pixelplay.data.repository.MusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/** The tools that read and change the user's taste: their schemas, the injection guard and the confirmations. */
class TaizoTasteToolsTest {

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // --- schemas ---

    @Test
    fun `the new tools are registered after the old ones and are closed and eager`() {
        val names = TaizoTools.clientToolNames
        assertThat(names.takeLast(5)).containsExactly(
            TaizoTools.LIBRARY_OVERVIEW, TaizoTools.TASTE_PROFILE, TaizoTools.ADJUST_TASTE,
            TaizoTools.LIST_TASTE_ADJUSTMENTS, TaizoTools.REMOVE_TASTE_ADJUSTMENT,
        ).inOrder()
        assertThat(TaizoTools.requestTools(withWeb = false)).hasSize(names.size)
        assertThat(names.toSet()).hasSize(names.size)
    }

    @Test
    fun `only search_library gives up strict, and the strict tools stay inside the optional parameter budget`() {
        val tools = TaizoTools.clientToolNames.map { TaizoTools.clientTools.getValue(it) }
        val nonStrict = tools.filter { it["strict"] == null }.map { (it["name"] as JsonPrimitive).content }
        assertThat(nonStrict).containsExactlyElementsIn(TaizoTools.nonStrictToolNames)

        // The API allows 24 optional parameters across all strict schemas (nested ones included).
        fun optionalCount(schema: JsonObject): Int {
            val props = schema["properties"] as? JsonObject ?: return 0
            val required = (schema["required"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }.toSet()
            var count = props.keys.count { it !in required }
            for (prop in props.values) {
                val items = (prop as? JsonObject)?.get("items") as? JsonObject
                if (items != null && items["type"] == JsonPrimitive("object")) count += optionalCount(items)
            }
            return count
        }
        val optional = tools.filter { it["strict"] != null }.sumOf { optionalCount(it["input_schema"] as JsonObject) }
        assertThat(optional).isAtMost(24)
        assertThat(tools.count { it["strict"] != null }).isAtMost(20)
    }

    @Test
    fun `search_library takes the library filters and paging`() {
        val schema = TaizoTools.schemaOf(TaizoTools.SEARCH_LIBRARY)!!
        fun check(json: String) = ToolInputValidator.validate(schema, Json.parseToJsonElement(json))
        assertThat(check("""{}""")).isNull() // filters alone are enough
        assertThat(check("""{"query":"radiohead","artist":"radiohead","album":"ok computer","genre":"rock","year_from":1990,"year_to":2000,"min_plays":1,"max_plays":50,"liked_only":true,"never_played":false,"sort":"most_played","limit":200,"offset":200}""")).isNull()
        assertThat(check("""{"sort":"loudest"}""")).contains("one of")
        assertThat(check("""{"limit":"many"}""")).contains("integer")
        assertThat(check("""{"liked_only":"yes"}""")).contains("true or false")
        assertThat(check("""{"page":2}""")).contains("not an allowed property")
    }

    @Test
    fun `adjust_taste needs the user flag and well formed changes`() {
        val schema = TaizoTools.schemaOf(TaizoTools.ADJUST_TASTE)!!
        fun check(json: String) = ToolInputValidator.validate(schema, Json.parseToJsonElement(json))
        val ok = """{"user_asked":true,"adjustments":[{"action":"boost","target_type":"artist","target":"Radiohead","strength":3,"reason":"you love them","expires_days":30}]}"""
        assertThat(check(ok)).isNull()
        assertThat(check("""{"adjustments":[]}""")).contains("user_asked")
        assertThat(check("""{"user_asked":true}""")).contains("adjustments")
        assertThat(check("""{"user_asked":true,"adjustments":[{"action":"delete","target_type":"artist","target":"x","reason":"r"}]}""")).contains("one of")
        assertThat(check("""{"user_asked":true,"adjustments":[{"action":"mute","target_type":"playlist","target":"x","reason":"r"}]}""")).contains("one of")
        assertThat(check("""{"user_asked":true,"adjustments":[{"action":"mute","target_type":"artist","reason":"r"}]}""")).contains("target")
        assertThat(check("""{"user_asked":true,"adjustments":[{"action":"mute","target_type":"artist","target":"x","reason":"r","force":true}]}""")).contains("not an allowed property")
        assertThat(check("""{"user_asked":"true","adjustments":[]}""")).contains("true or false")
    }

    @Test
    fun `the other taste tools take their small inputs`() {
        fun check(tool: String, json: String) = ToolInputValidator.validate(TaizoTools.schemaOf(tool)!!, Json.parseToJsonElement(json))
        assertThat(check(TaizoTools.TASTE_PROFILE, """{}""")).isNull()
        assertThat(check(TaizoTools.TASTE_PROFILE, """{"period":"year"}""")).isNull()
        assertThat(check(TaizoTools.TASTE_PROFILE, """{"period":"decade"}""")).contains("one of")
        assertThat(check(TaizoTools.LIBRARY_OVERVIEW, """{}""")).isNull()
        assertThat(check(TaizoTools.LIST_TASTE_ADJUSTMENTS, """{}""")).isNull()
        assertThat(check(TaizoTools.REMOVE_TASTE_ADJUSTMENT, """{"user_asked":true,"all":true}""")).isNull()
        assertThat(check(TaizoTools.REMOVE_TASTE_ADJUSTMENT, """{"user_asked":true,"id":"ab12cd34"}""")).isNull()
        assertThat(check(TaizoTools.REMOVE_TASTE_ADJUSTMENT, """{"all":true}""")).contains("user_asked")
    }

    @Test
    fun `the user message is the last text block of a turn`() {
        val content = TaizoAgent.textContent("<now>\nLocal time: ...\n</now>").let { context ->
            JsonArray(context + TaizoAgent.textContent("play less Drake"))
        }
        assertThat(TaizoAgent.userMessageOf(content)).isEqualTo("play less Drake")
        assertThat(TaizoAgent.userMessageOf(JsonArray(emptyList()))).isEmpty()
    }

    // --- the injection guard and the confirmations ---

    private val songs = listOf(
        Song.emptySong().copy(id = "1", title = "Hotline Bling", artist = "Drake", genre = "Hip-Hop"),
        Song.emptySong().copy(id = "2", title = "Creep", artist = "Radiohead", genre = "Rock"),
    )
    private val repository = mockk<TasteAdjustmentRepository>()
    private val music = mockk<MusicRepository>()
    private val executor = TaizoToolExecutor(
        musicRepository = music,
        spotifyRepository = mockk(),
        spotifyAuth = mockk(),
        playlists = mockk(),
        statsRepository = mockk(),
        taste = mockk(),
        contextProvider = mockk(),
        library = mockk(),
        profileProvider = mockk(),
        tasteAdjustments = repository,
    )

    private fun outcome(vararg texts: String) = TasteOutcome(
        emptyList(), texts.mapIndexed { i, text -> TasteChange("id$i", text, true) },
    )

    private val reduceDrake = """{"user_asked":true,"adjustments":[{"action":"reduce","target_type":"artist","target":"Drake","reason":"too much lately"}]}"""

    @Test
    fun `a taste change is refused unless the model says the user asked`() = runTest {
        executor.beginTurn("play less Drake")
        val error = runCatching {
            executor.run(TaizoTools.ADJUST_TASTE, parse("""{"user_asked":false,"adjustments":[{"action":"reduce","target_type":"artist","target":"Drake","reason":"r"}]}"""))
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("user's own message")
        coVerify(exactly = 0) { repository.apply(any(), any()) }
    }

    @Test
    fun `a taste change is refused when this turn has no message from the user`() = runTest {
        // No beginTurn: nothing the user said is on record, e.g. the request came from a tool result.
        val error = runCatching { executor.run(TaizoTools.ADJUST_TASTE, parse(reduceDrake)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        executor.beginTurn("   ")
        assertThat(runCatching { executor.run(TaizoTools.ADJUST_TASTE, parse(reduceDrake)) }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(
            runCatching { executor.run(TaizoTools.REMOVE_TASTE_ADJUSTMENT, parse("""{"user_asked":true,"all":true}""")) }.exceptionOrNull()
        ).isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 0) { repository.apply(any(), any()) }
        coVerify(exactly = 0) { repository.clear() }
    }

    @Test
    fun `a flagged turn applies the change and reports exactly what changed`() = runTest {
        coEvery { music.getAllSongsOnce() } returns songs
        val requests = slot<List<TasteRequest>>()
        coEvery { repository.apply(capture(requests), TasteSetBy.TAIZO) } returns outcome("Reduced Drake (artist): x1.6 less")
        coEvery { repository.list(any()) } returns listOf(
            TasteAdjustment("id0", TasteKind.REDUCE, TasteTarget.ARTIST, "drake", label = "Drake", createdAtMs = 1L),
        )
        executor.beginTurn("play less Drake please")
        assertThat(executor.consumeTasteChanged()).isFalse()

        val result = parse(executor.run(TaizoTools.ADJUST_TASTE, parse(reduceDrake)))

        assertThat(result["ok"]).isEqualTo(JsonPrimitive(true))
        assertThat(result.toString()).contains("Reduced Drake (artist): x1.6 less")
        assertThat(result.toString()).contains("Settings")
        val sent = requests.captured.single()
        assertThat(sent.action).isEqualTo(TasteAction.REDUCE)
        assertThat(sent.target).isEqualTo(TasteTarget.ARTIST)
        assertThat(sent.key).isEqualTo("drake")
        assertThat(sent.label).isEqualTo("Drake")
        assertThat(sent.reason).isEqualTo("too much lately")
        assertThat(executor.consumeTasteChanged()).isTrue() // the chat refreshes its cached profile
        assertThat(executor.consumeTasteChanged()).isFalse()
    }

    @Test
    fun `too many changes in one call or one turn are refused`() = runTest {
        coEvery { music.getAllSongsOnce() } returns songs
        coEvery { repository.apply(any(), any()) } returns outcome("done")
        coEvery { repository.list(any()) } returns emptyList()
        executor.beginTurn("mute all of these")
        fun muteGenre(i: Int) = """{"action":"mute","target_type":"genre","target":"g$i","reason":"r"}"""
        fun many(n: Int): JsonObject {
            val items = (1..n).joinToString(",") { muteGenre(it) }
            return parse("""{"user_asked":true,"adjustments":[$items]}""")
        }
        val tooMany = runCatching { executor.run(TaizoTools.ADJUST_TASTE, many(TaizoTools.MAX_ADJUSTMENTS_PER_CALL + 1)) }.exceptionOrNull()
        assertThat(tooMany).isInstanceOf(IllegalArgumentException::class.java)

        executor.run(TaizoTools.ADJUST_TASTE, many(TaizoTools.MAX_ADJUSTMENTS_PER_CALL))
        executor.run(TaizoTools.ADJUST_TASTE, many(TaizoTools.MAX_ADJUSTMENTS_PER_CALL))
        val overTurn = runCatching { executor.run(TaizoTools.ADJUST_TASTE, many(1)) }.exceptionOrNull()
        assertThat(overTurn).isInstanceOf(IllegalStateException::class.java)
        // A new user message starts a fresh allowance.
        executor.beginTurn("and one more")
        executor.run(TaizoTools.ADJUST_TASTE, many(1))
    }

    @Test
    fun `blocking and big batches ask the user first, ordinary changes do not`() = runTest {
        coEvery { repository.list(any()) } returns listOf(
            TasteAdjustment("id0", TasteKind.MUTE, TasteTarget.GENRE, "pop", label = "Pop"),
            TasteAdjustment("id1", TasteKind.BOOST, TasteTarget.ARTIST, "radiohead", label = "Radiohead"),
        )
        executor.beginTurn("never play Nickelback, and play less Drake")
        fun change(action: String, target: String) =
            """{"action":"$action","target_type":"artist","target":"$target","reason":"r"}"""
        fun call(vararg changes: String) =
            parse("""{"user_asked":true,"adjustments":[${changes.joinToString(",")}]}""")

        assertThat(executor.confirmationFor(TaizoTools.ADJUST_TASTE, call(change("boost", "Radiohead")))).isNull()
        assertThat(executor.confirmationFor(TaizoTools.ADJUST_TASTE, call(change("mute", "Drake"), change("unblock", "Nickelback")))).isNull()
        val block = executor.confirmationFor(TaizoTools.ADJUST_TASTE, call(change("block", "Nickelback")))
        assertThat(block).contains("Block Nickelback")
        val six = executor.confirmationFor(TaizoTools.ADJUST_TASTE, call(*Array(6) { change("boost", "Artist $it") }))
        assertThat(six).contains("6 taste changes")
        val five = executor.confirmationFor(TaizoTools.ADJUST_TASTE, call(*Array(5) { change("boost", "Artist $it") }))
        assertThat(five).isNull()
        assertThat(executor.confirmationFor(TaizoTools.REMOVE_TASTE_ADJUSTMENT, parse("""{"user_asked":true,"all":true}"""))).contains("all 2")
        assertThat(executor.confirmationFor(TaizoTools.REMOVE_TASTE_ADJUSTMENT, parse("""{"user_asked":true,"id":"id0"}"""))).isNull()
        // Without the user flag there is nothing to confirm: run() refuses it.
        assertThat(
            executor.confirmationFor(TaizoTools.ADJUST_TASTE, parse("""{"user_asked":false,"adjustments":[${change("block", "X")}]}"""))
        ).isNull()
    }
}

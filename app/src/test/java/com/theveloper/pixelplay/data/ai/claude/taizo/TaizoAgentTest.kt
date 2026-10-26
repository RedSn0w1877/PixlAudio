package com.theveloper.pixelplay.data.ai.claude.taizo

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.ai.claude.ClaudeRequest
import com.theveloper.pixelplay.data.ai.claude.ClaudeTransport
import com.theveloper.pixelplay.data.ai.claude.ClaudeTurn
import com.theveloper.pixelplay.data.ai.claude.ClaudeUsage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class TaizoAgentTest {

    private class FakeTransport(private val turns: List<ClaudeTurn>) : ClaudeTransport {
        val requests = mutableListOf<ClaudeRequest>()

        override suspend fun create(request: ClaudeRequest): ClaudeTurn = error("not used")

        override suspend fun stream(
            request: ClaudeRequest,
            onText: (String) -> Unit,
            onBlockStart: (JsonObject) -> Unit,
        ): ClaudeTurn {
            requests += request
            val turn = turns[requests.size - 1]
            turn.content.forEach { block ->
                val obj = block as JsonObject
                onBlockStart(obj)
                if ((obj["type"] as JsonPrimitive).content == "text") onText((obj["text"] as JsonPrimitive).content)
            }
            return turn
        }
    }

    private class FakeTools(
        private val confirmFor: Set<String> = emptySet(),
    ) : TaizoToolRunner {
        val ran = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun confirmationFor(name: String, input: JsonObject): String? =
            if (name in confirmFor) "Sure?" else null

        override suspend fun run(name: String, input: JsonObject): String {
            ran += name to input
            if (name == TaizoTools.LIKE_SONG) throw IllegalStateException("Nothing is playing right now.")
            return """{"ok":true,"tool":"$name"}"""
        }
    }

    private class RecordingListener(private val accept: Boolean = true) : TaizoAgentListener {
        val text = StringBuilder()
        var discards = 0
        val activities = mutableListOf<String>()
        val questions = mutableListOf<String>()
        override fun onText(delta: String) { text.append(delta) }
        override fun onDiscardStep() { discards++ }
        override fun onStepStart() = Unit
        override fun onActivity(toolName: String, isServerTool: Boolean) { activities += toolName }
        override suspend fun confirm(question: String): Boolean {
            questions += question
            return accept
        }
    }

    private fun turn(stop: String, vararg blocks: String, invalid: Map<String, String> = emptyMap()) = ClaudeTurn(
        content = JsonArray(blocks.map { Json.parseToJsonElement(it) }),
        stopReason = stop,
        usage = ClaudeUsage(inputTokens = 10, outputTokens = 5),
        invalidToolInputs = invalid,
    )

    private val system = JsonArray(emptyList())
    private val tools = TaizoTools.requestTools()

    private suspend fun run(transport: FakeTransport, runner: TaizoToolRunner, listener: RecordingListener, history: List<JsonObject> = emptyList()) =
        TaizoAgent(transport, runner).run(history, TaizoAgent.textContent("play radiohead"), system, tools, listener)

    @Test
    fun `all tool results of a turn go back in one user message, then the answer ends the loop`() = runTest {
        val transport = FakeTransport(
            listOf(
                turn(
                    "tool_use",
                    """{"type":"text","text":"Looking."}""",
                    """{"type":"tool_use","id":"t1","name":"search_library","input":{"query":"radiohead"}}""",
                    """{"type":"tool_use","id":"t2","name":"like_song","input":{}}""",
                ),
                turn("end_turn", """{"type":"text","text":"Playing now."}"""),
            )
        )
        val runner = FakeTools()
        val listener = RecordingListener()
        val result = run(transport, runner, listener)

        assertThat(result.outcome).isEqualTo(TaizoAgent.Outcome.DONE)
        assertThat(runner.ran.map { it.first }).containsExactly("search_library", "like_song").inOrder()
        assertThat(result.history).hasSize(4) // user, assistant(tool_use), user(tool results), assistant
        val results = result.history[2]["content"]!!.jsonArray.map { it.jsonObject }
        assertThat(results.map { it["tool_use_id"]!!.jsonPrimitive.content }).containsExactly("t1", "t2").inOrder()
        assertThat(results[0]["is_error"]).isNull()
        assertThat(results[1]["is_error"]).isEqualTo(JsonPrimitive(true))
        // The assistant turn goes back exactly as it came.
        assertThat(result.history[1]["content"]).isEqualTo(transport.requests[1].messages[1].jsonObject["content"])
        assertThat(result.usage).isEqualTo(ClaudeUsage(inputTokens = 20, outputTokens = 10))
        assertThat(listener.activities).containsExactly("search_library", "like_song").inOrder()
    }

    @Test
    fun `an invalid streamed input is answered with INVALID_JSON and never run`() = runTest {
        val transport = FakeTransport(
            listOf(
                turn(
                    "tool_use",
                    """{"type":"tool_use","id":"t1","name":"play_songs","input":{}}""",
                    invalid = mapOf("t1" to """{"song_ids": ["1" """),
                ),
                turn("end_turn", """{"type":"text","text":"Sorry."}"""),
            )
        )
        val runner = FakeTools()
        val result = run(transport, runner, RecordingListener())
        assertThat(runner.ran).isEmpty()
        val toolResult = result.history[2]["content"]!!.jsonArray[0].jsonObject
        assertThat(toolResult["is_error"]).isEqualTo(JsonPrimitive(true))
        val payload = Json.parseToJsonElement(toolResult["content"]!!.jsonPrimitive.content).jsonObject
        assertThat(payload["INVALID_JSON"]!!.jsonPrimitive.content).isEqualTo("""{"song_ids": ["1" """)
    }

    @Test
    fun `a schema mismatch is treated like invalid JSON`() = runTest {
        val transport = FakeTransport(
            listOf(
                turn("tool_use", """{"type":"tool_use","id":"t1","name":"playback_control","input":{"action":"rewind"}}"""),
                turn("end_turn", """{"type":"text","text":"ok"}"""),
            )
        )
        val runner = FakeTools()
        val result = run(transport, runner, RecordingListener())
        assertThat(runner.ran).isEmpty()
        assertThat(result.history[2]["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content).contains("INVALID_JSON")
    }

    @Test
    fun `pause_turn resends without a new user message`() = runTest {
        val transport = FakeTransport(
            listOf(
                turn("pause_turn", """{"type":"server_tool_use","id":"s1","name":"web_search","input":{"query":"x"}}"""),
                turn("end_turn", """{"type":"text","text":"Found it."}"""),
            )
        )
        val listener = RecordingListener()
        val result = run(transport, FakeTools(), listener)
        assertThat(result.outcome).isEqualTo(TaizoAgent.Outcome.DONE)
        val second = transport.requests[1].messages
        assertThat(second).hasSize(2)
        assertThat(second.last().jsonObject["role"]).isEqualTo(JsonPrimitive("assistant"))
        assertThat(listener.activities).contains("web_search")
    }

    @Test
    fun `a refusal stops, runs nothing and leaves the history as it was`() = runTest {
        val transport = FakeTransport(
            listOf(turn("refusal", """{"type":"text","text":"partial"}""", """{"type":"tool_use","id":"t1","name":"start_sing","input":{}}"""))
        )
        val runner = FakeTools()
        val listener = RecordingListener()
        val result = run(transport, runner, listener)
        assertThat(result.outcome).isEqualTo(TaizoAgent.Outcome.REFUSED)
        assertThat(runner.ran).isEmpty()
        assertThat(result.history).isEmpty()
        assertThat(listener.discards).isEqualTo(1)
    }

    @Test
    fun `max_tokens with a tool call retries once with more room instead of running it`() = runTest {
        val transport = FakeTransport(
            listOf(
                turn("max_tokens", """{"type":"tool_use","id":"t1","name":"search_library","input":{"query":"ra"}}"""),
                turn("end_turn", """{"type":"text","text":"done"}"""),
            )
        )
        val runner = FakeTools()
        val result = run(transport, runner, RecordingListener())
        assertThat(runner.ran).isEmpty()
        assertThat(transport.requests[1].maxTokens).isGreaterThan(transport.requests[0].maxTokens)
        assertThat(transport.requests[1].messages).hasSize(1)
        assertThat(result.outcome).isEqualTo(TaizoAgent.Outcome.DONE)
    }

    @Test
    fun `a declined confirmation is reported to Claude and the tool doesn't run`() = runTest {
        val transport = FakeTransport(
            listOf(
                turn("tool_use", """{"type":"tool_use","id":"t1","name":"play_songs","input":{"song_ids":["1"]}}"""),
                turn("end_turn", """{"type":"text","text":"Okay."}"""),
            )
        )
        val runner = FakeTools(confirmFor = setOf(TaizoTools.PLAY_SONGS))
        val listener = RecordingListener(accept = false)
        val result = run(transport, runner, listener)
        assertThat(listener.questions).containsExactly("Sure?")
        assertThat(runner.ran).isEmpty()
        assertThat(result.history[2]["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content).contains("declined")
    }

    @Test
    fun `a new user message joins leftover tool results so roles alternate`() {
        val messages = mutableListOf(
            Json.parseToJsonElement("""{"role":"user","content":"hi"}""").jsonObject,
            Json.parseToJsonElement("""{"role":"assistant","content":[{"type":"tool_use","id":"t","name":"x","input":{}}]}""").jsonObject,
            Json.parseToJsonElement("""{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"ok"}]}""").jsonObject,
        )
        TaizoAgent.appendUserContent(messages, TaizoAgent.textContent("next"))
        assertThat(messages).hasSize(3)
        assertThat(messages[2]["content"]!!.jsonArray).hasSize(2)
    }

    @Test
    fun `trimming only cuts before the user's own words`() {
        val user = { text: String -> Json.parseToJsonElement("""{"role":"user","content":"$text"}""").jsonObject }
        val assistant = Json.parseToJsonElement("""{"role":"assistant","content":"a"}""").jsonObject
        val toolResults = Json.parseToJsonElement("""{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"ok"}]}""").jsonObject
        val history = listOf(user("1"), assistant, toolResults, assistant, user("2"), assistant)
        val trimmed = TaizoAgent.trimHistory(history, maxMessages = 4)
        assertThat(trimmed).hasSize(2)
        assertThat(trimmed.first()).isEqualTo(user("2"))
        assertThat(TaizoAgent.trimHistory(history, maxMessages = 10)).isEqualTo(history)
    }

    @Test
    fun `a reopened chat continues from alternating text that starts with the user`() {
        val history = TaizoChatStore.historyFrom(
            listOf(
                StoredTaizoMessage("assistant", "orphan"),
                StoredTaizoMessage("user", "a"),
                StoredTaizoMessage("user", "b"),
                StoredTaizoMessage("assistant", "c"),
            )
        )
        assertThat(history.map { it["role"]!!.jsonPrimitive.content }).containsExactly("user", "assistant").inOrder()
        assertThat(history[0]["content"]!!.jsonPrimitive.content).isEqualTo("a\n\nb")
    }

    @Test
    fun `playlist building and insights get high effort, chat medium`() {
        assertThat(TaizoContext.effortFor("Make me a running playlist")).isEqualTo(com.theveloper.pixelplay.data.ai.claude.ClaudeEffort.HIGH)
        assertThat(TaizoContext.effortFor("What are my listening habits?")).isEqualTo(com.theveloper.pixelplay.data.ai.claude.ClaudeEffort.HIGH)
        assertThat(TaizoContext.effortFor("What's this song about?")).isEqualTo(com.theveloper.pixelplay.data.ai.claude.ClaudeEffort.MEDIUM)
    }

    @Test
    fun `lyrics are trimmed at a line and times are formatted`() {
        val lyrics = (1..400).joinToString("\n") { "line number $it" }
        val trimmed = TaizoContext.trimLyrics(lyrics, maxChars = 100)
        assertThat(trimmed.length).isAtMost(110)
        assertThat(trimmed).endsWith("[…]")
        assertThat(TaizoContext.formatTime(187_000)).isEqualTo("3:07")
        assertThat(TaizoContext.formatTime(3_725_000)).isEqualTo("1:02:05")
    }
}

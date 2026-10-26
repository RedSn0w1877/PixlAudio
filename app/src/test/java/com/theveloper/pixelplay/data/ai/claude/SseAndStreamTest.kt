package com.theveloper.pixelplay.data.ai.claude

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SseAndStreamTest {

    private fun events(raw: String): List<SseParser.Event> {
        val parser = SseParser()
        val out = raw.lines().mapNotNull { parser.feed(it) }.toMutableList()
        parser.finish()?.let { out += it }
        return out
    }

    @Test
    fun `sse parser splits events, joins data lines and skips comments`() {
        val parsed = events(
            """
            : keep-alive
            event: message_start
            data: {"a":1}

            event: ping
            data: {"type":"ping"}

            data: line1
            data: line2
            """.trimIndent()
        )
        assertThat(parsed).hasSize(3)
        assertThat(parsed[0].name).isEqualTo("message_start")
        assertThat(parsed[0].data).isEqualTo("""{"a":1}""")
        assertThat(parsed[2].name).isNull()
        assertThat(parsed[2].data).isEqualTo("line1\nline2")
    }

    private fun accumulate(vararg data: String, onText: (String) -> Unit = {}): ClaudeTurn {
        val acc = ClaudeStreamAccumulator(onText = onText)
        data.forEach { acc.accept(it) }
        return acc.result()
    }

    @Test
    fun `text deltas, stop reason and usage are rebuilt`() {
        val streamed = StringBuilder()
        val turn = accumulate(
            """{"type":"message_start","message":{"model":"claude-sonnet-5-5","usage":{"input_tokens":120,"cache_read_input_tokens":900,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" there"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""",
            """{"type":"message_stop"}""",
            onText = { streamed.append(it) },
        )
        assertThat(streamed.toString()).isEqualTo("Hello there")
        assertThat(turn.text).isEqualTo("Hello there")
        assertThat(turn.stopReason).isEqualTo("end_turn")
        assertThat(turn.usage).isEqualTo(ClaudeUsage(inputTokens = 120, outputTokens = 42, cacheReadTokens = 900))
        assertThat(turn.model).isEqualTo("claude-sonnet-5-5")
    }

    @Test
    fun `tool input fragments are parsed strictly`() {
        val turn = accumulate(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"search_library","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\": \"Rad"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"iohead\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_2","name":"play_songs","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"song_ids\": [\"1\", "}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
        )
        val first = turn.toolUses[0]
        assertThat(first["input"]!!.jsonObject["query"]!!.jsonPrimitive.content).isEqualTo("Radiohead")
        assertThat(turn.invalidToolInputs).containsExactly("toolu_2", """{"song_ids": ["1", """)
        // The broken call still goes back as a well-formed block.
        assertThat(turn.toolUses[1]["input"]).isEqualTo(JsonObject(emptyMap()))
    }

    @Test
    fun `an empty tool input is an empty object`() {
        assertThat(ClaudeStreamAccumulator.parseToolInput("")).isEqualTo(JsonObject(emptyMap()))
        assertThat(ClaudeStreamAccumulator.parseToolInput("[1]")).isNull()
        assertThat(ClaudeStreamAccumulator.parseToolInput("{'a': 1}")).isNull()
    }

    @Test
    fun `thinking signatures, server tool blocks and citations are kept for the next request`() {
        val turn = accumulate(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"tour dates\"}"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","url":"https://example.com","title":"Ex"}]}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"content_block_start","index":3,"content_block":{"type":"text","text":"","citations":null}}""",
            """{"type":"content_block_delta","index":3,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://example.com","title":"Ex","cited_text":"x"}}}""",
            """{"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"They tour in May."}}""",
            """{"type":"content_block_stop","index":3}""",
        )
        val blocks = turn.content.map { it as JsonObject }
        assertThat(blocks[0]["thinking"]).isEqualTo(JsonPrimitive("hmm"))
        assertThat(blocks[0]["signature"]).isEqualTo(JsonPrimitive("SIG"))
        assertThat(blocks[1]["input"]!!.jsonObject["query"]).isEqualTo(JsonPrimitive("tour dates"))
        assertThat((blocks[2]["content"] as JsonArray)).hasSize(1)
        assertThat((blocks[3]["citations"] as JsonArray)).hasSize(1)
        assertThat(turn.invalidToolInputs).isEmpty()
    }

    @Test
    fun `an error event is a typed failure`() {
        val acc = ClaudeStreamAccumulator()
        val e = assertThrows(ClaudeException::class.java) {
            acc.accept("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        }
        assertThat(e.failure).isEqualTo(ClaudeFailure.BUSY)
        assertThat(e.fallsBackOnDevice).isTrue()
    }
}

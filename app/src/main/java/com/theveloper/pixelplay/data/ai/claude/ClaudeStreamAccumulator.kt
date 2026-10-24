package com.theveloper.pixelplay.data.ai.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Rebuilds a streamed Messages response from its SSE events: message_start,
 * content_block_start / content_block_delta / content_block_stop, message_delta (stop_reason,
 * usage), message_stop, ping and error. The result is the same `content` array a non-streamed
 * call returns, so it can be appended to the conversation unchanged (thinking blocks with their
 * signatures, server_tool_use and *_tool_result blocks included).
 *
 * Tool inputs arrive as `input_json_delta` fragments. With `eager_input_streaming` the API no
 * longer validates them, so the accumulated text is parsed strictly here; anything that is not a
 * JSON object is recorded in [ClaudeTurn.invalidToolInputs] (the caller answers it with an
 * INVALID_JSON tool_result instead of running the tool). Pure (ClaudeStreamAccumulatorTest).
 */
class ClaudeStreamAccumulator(
    private val onText: (String) -> Unit = {},
    private val onBlockStart: (JsonObject) -> Unit = {},
) {
    private class Block(val start: JsonObject) {
        val text = StringBuilder()
        val partialJson = StringBuilder()
        var sawJson = false
        val thinking = StringBuilder()
        var sawThinking = false
        val signature = StringBuilder()
        var sawSignature = false
        val citations = mutableListOf<JsonElement>()
        var done: JsonObject? = null
    }

    private val blocks = sortedMapOf<Int, Block>()
    private var model: String? = null
    private var usage = ClaudeUsage()
    private var stopReason: String? = null
    private val invalidInputs = linkedMapOf<String, String>()
    var finished: Boolean = false
        private set

    /** Handles one SSE event (its `data` JSON). Throws [ClaudeException] for an `error` event. */
    fun accept(eventData: String) {
        val event = runCatching { STRICT.parseToJsonElement(eventData) }.getOrNull() as? JsonObject ?: return
        when (event.type) {
            "message_start" -> {
                val message = event["message"].asObject()
                model = message?.string("model")
                usage = ClaudeUsage.fromJson(message?.get("usage").asObject())
            }
            "content_block_start" -> {
                val index = event.index() ?: return
                val start = event["content_block"].asObject() ?: return
                blocks[index] = Block(start)
                onBlockStart(start)
            }
            "content_block_delta" -> {
                val block = event.index()?.let { blocks[it] } ?: return
                val delta = event["delta"].asObject() ?: return
                when (delta.type) {
                    "text_delta" -> delta.string("text")?.let {
                        block.text.append(it)
                        onText(it)
                    }
                    "input_json_delta" -> {
                        block.sawJson = true
                        block.partialJson.append(delta.string("partial_json").orEmpty())
                    }
                    "thinking_delta" -> {
                        block.sawThinking = true
                        block.thinking.append(delta.string("thinking").orEmpty())
                    }
                    "signature_delta" -> {
                        block.sawSignature = true
                        block.signature.append(delta.string("signature").orEmpty())
                    }
                    "citations_delta" -> delta["citation"]?.let { block.citations += it }
                }
            }
            "content_block_stop" -> {
                val block = event.index()?.let { blocks[it] } ?: return
                block.done = finish(block)
            }
            "message_delta" -> {
                event["delta"].asObject()?.string("stop_reason")?.let { stopReason = it }
                usage = usage.overriddenBy(event["usage"].asObject())
            }
            "message_stop" -> finished = true
            "error" -> {
                val error = event["error"].asObject()
                val errorType = error?.string("type").orEmpty()
                val failure = when (errorType) {
                    "overloaded_error", "api_error", "rate_limit_error" -> ClaudeFailure.BUSY
                    else -> ClaudeFailure.UNAVAILABLE
                }
                throw ClaudeException(failure, "Stream error: $errorType")
            }
        }
    }

    /** The response so far (blocks still open are closed as they stand). */
    fun result(): ClaudeTurn {
        val content = blocks.values.map { it.done ?: finish(it) }
        return ClaudeTurn(
            content = JsonArray(content),
            stopReason = stopReason,
            usage = usage,
            model = model,
            invalidToolInputs = invalidInputs.toMap(),
        )
    }

    private fun finish(block: Block): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(block.start)
        when (block.start.type) {
            "text" -> {
                val text = block.start.string("text").orEmpty() + block.text
                out["text"] = JsonPrimitive(text)
                val existing = (block.start["citations"] as? JsonArray).orEmpty()
                val all = existing + block.citations
                if (all.isEmpty()) out.remove("citations") else out["citations"] = JsonArray(all)
            }
            "thinking" -> {
                if (block.sawThinking) out["thinking"] = JsonPrimitive(block.start.string("thinking").orEmpty() + block.thinking)
                if (block.sawSignature) out["signature"] = JsonPrimitive(block.start.string("signature").orEmpty() + block.signature)
            }
        }
        if (block.sawJson) {
            val raw = block.partialJson.toString()
            val parsed = parseToolInput(raw)
            if (parsed != null) {
                out["input"] = parsed
            } else {
                out["input"] = JsonObject(emptyMap())
                if (block.start.type == "tool_use") {
                    block.start.string("id")?.let { invalidInputs[it] = raw }
                }
            }
        }
        return JsonObject(out)
    }

    private fun JsonObject.index(): Int? = (this["index"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    companion object {
        /** Strict parser: no lenient syntax, so a broken fragment is an error, not a guess. */
        internal val STRICT = Json { isLenient = false; ignoreUnknownKeys = false }

        /** The accumulated input as a JSON object; "" means `{}`; anything else that isn't an object is null. */
        fun parseToolInput(raw: String): JsonObject? {
            if (raw.isBlank()) return JsonObject(emptyMap())
            return runCatching { STRICT.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        }
    }
}

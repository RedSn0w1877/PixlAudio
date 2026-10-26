package com.theveloper.pixelplay.data.ai.claude.taizo

import com.theveloper.pixelplay.data.ai.claude.ClaudeConfig
import com.theveloper.pixelplay.data.ai.claude.ClaudeEffort
import com.theveloper.pixelplay.data.ai.claude.ClaudeRequest
import com.theveloper.pixelplay.data.ai.claude.ClaudeTransport
import com.theveloper.pixelplay.data.ai.claude.ClaudeTurn
import com.theveloper.pixelplay.data.ai.claude.ClaudeUsage
import com.theveloper.pixelplay.data.ai.claude.ToolInputValidator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Runs Ask Taizo's client tools. */
interface TaizoToolRunner {
    /**
     * Called once when a user turn starts, with the user's own words (the last text block of their message,
     * without the context blocks). Tools that change the user's settings compare against it: text that
     * arrives from a web page, a lyric or a tool result is data and never counts as a request.
     */
    fun beginTurn(userMessage: String) {}

    /**
     * Called when text from outside the user's own message reaches the model in this turn (a web search or fetch
     * result). From then on every settings change in the turn asks for a tap, whatever the model claims.
     */
    fun noteUntrustedContent() {}

    /** The question to ask before running this call (a big playlist, replacing the queue), or null to run it now. */
    suspend fun confirmationFor(name: String, input: JsonObject): String?

    /** Runs the tool and returns its result for Claude (JSON text). Throwing makes an `is_error` result. */
    suspend fun run(name: String, input: JsonObject): String
}

/** What the chat shows while a turn runs. */
interface TaizoAgentListener {
    fun onText(delta: String)

    /** Text streamed since the last [onStepStart] is void (a refusal, or a retry with more tokens). */
    fun onDiscardStep()

    fun onStepStart()

    /** A tool or server tool started (web search, a library search...). */
    fun onActivity(toolName: String, isServerTool: Boolean)

    /** Asks the user to tap to confirm; false = declined. */
    suspend fun confirm(question: String): Boolean
}

/** A web page Claude cited. */
data class TaizoSource(val url: String, val title: String)

/**
 * Ask Taizo's agentic loop over the Messages API (manual loop: the stream is parsed by our own
 * accumulator, and destructive tools wait for a tap in the chat).
 *
 * Rules (A3):
 * - the FULL assistant `content` goes back into the history unchanged (thinking, server_tool_use,
 *   web results, citations);
 * - all client tool calls of a turn run, and all their results go back in ONE user message with
 *   matching `tool_use_id`s (`is_error` on failure; a streamed input that doesn't parse or match
 *   its schema is answered with `{"INVALID_JSON": "<raw>"}` and never run);
 * - loop until `end_turn`; `pause_turn` resends as-is with no new user message (at most
 *   [ClaudeConfig.MAX_PAUSE_CONTINUATIONS]); `refusal` stops, discards the partial answer and runs
 *   none of that turn's tools; `max_tokens` with a tool call retries once with more tokens
 *   instead of running a truncated call.
 */
class TaizoAgent(
    private val transport: ClaudeTransport,
    private val tools: TaizoToolRunner,
) {
    enum class Outcome { DONE, REFUSED, TRUNCATED, STEP_LIMIT }

    data class Result(
        /** The conversation after this turn (on a refusal: as it was before the user's message). */
        val history: List<JsonObject>,
        val reply: String,
        val sources: List<TaizoSource>,
        val outcome: Outcome,
        val usage: ClaudeUsage,
    )

    suspend fun run(
        history: List<JsonObject>,
        userContent: JsonArray,
        system: JsonArray,
        toolDefinitions: JsonArray,
        listener: TaizoAgentListener,
        effort: ClaudeEffort = ClaudeEffort.MEDIUM,
    ): Result {
        val messages = history.toMutableList()
        tools.beginTurn(userMessageOf(userContent))
        appendUserContent(messages, userContent)
        val replyText = StringBuilder()
        val sources = linkedMapOf<String, TaizoSource>()
        var usage = ClaudeUsage()
        var continuations = 0
        var retriedWithMoreTokens = false
        var maxTokens = ClaudeConfig.MAX_TOKENS_STREAMING

        repeat(ClaudeConfig.MAX_AGENT_STEPS) {
            listener.onStepStart()
            val stepText = StringBuilder()
            val turn = transport.stream(
                request = ClaudeRequest(
                    system = system,
                    // A snapshot: JsonArray wraps the list it's given, and `messages` keeps growing.
                    messages = JsonArray(messages.toList()),
                    tools = toolDefinitions,
                    maxTokens = maxTokens,
                    effort = effort,
                ),
                onText = { delta ->
                    stepText.append(delta)
                    listener.onText(delta)
                },
                onBlockStart = { block ->
                    when (block.str("type")) {
                        "server_tool_use" -> {
                            tools.noteUntrustedContent()
                            listener.onActivity(block.str("name").orEmpty(), isServerTool = true)
                        }
                        "web_search_tool_result", "web_fetch_tool_result" -> tools.noteUntrustedContent()
                        "tool_use" -> listener.onActivity(block.str("name").orEmpty(), isServerTool = false)
                    }
                },
            )
            usage += turn.usage

            when (turn.stopReason) {
                "refusal" -> {
                    listener.onDiscardStep()
                    return Result(history, "", emptyList(), Outcome.REFUSED, usage)
                }
                "pause_turn" -> {
                    messages += assistantMessage(turn)
                    replyText.append(stepText)
                    collectSources(turn, sources)
                    continuations++
                    if (continuations > ClaudeConfig.MAX_PAUSE_CONTINUATIONS) {
                        return Result(messages, replyText.toString(), sources.values.toList(), Outcome.STEP_LIMIT, usage)
                    }
                    return@repeat
                }
                "max_tokens" -> {
                    if (turn.toolUses.isNotEmpty() && !retriedWithMoreTokens) {
                        // A cut-off tool call is never run: same request again with more room.
                        retriedWithMoreTokens = true
                        maxTokens = ClaudeConfig.MAX_TOKENS_RETRY
                        listener.onDiscardStep()
                        return@repeat
                    }
                    if (turn.toolUses.isEmpty()) {
                        messages += assistantMessage(turn)
                        replyText.append(stepText)
                        collectSources(turn, sources)
                        return Result(messages, replyText.toString(), sources.values.toList(), Outcome.TRUNCATED, usage)
                    }
                    // Still cut off after the retry: keep the text, answer the calls with errors.
                    messages += assistantMessage(turn)
                    messages += toolResultsMessage(turn.toolUses.map { errorResult(it, "Not run: the request was cut off.") })
                    replyText.append(stepText)
                    return Result(messages, replyText.toString(), sources.values.toList(), Outcome.TRUNCATED, usage)
                }
                "tool_use" -> {
                    messages += assistantMessage(turn)
                    replyText.append(stepText)
                    collectSources(turn, sources)
                    messages += toolResultsMessage(runTools(turn, listener))
                    return@repeat
                }
                else -> {
                    // end_turn, stop_sequence, or anything new: the answer is complete.
                    messages += assistantMessage(turn)
                    replyText.append(stepText)
                    collectSources(turn, sources)
                    return Result(messages, replyText.toString(), sources.values.toList(), Outcome.DONE, usage)
                }
            }
        }
        // Out of steps with tool results last: the next user message merges into that turn.
        return Result(messages, replyText.toString(), sources.values.toList(), Outcome.STEP_LIMIT, usage)
    }

    private suspend fun runTools(turn: ClaudeTurn, listener: TaizoAgentListener): List<JsonObject> =
        turn.toolUses.map { call ->
            val id = call.str("id").orEmpty()
            val name = call.str("name").orEmpty()
            val raw = turn.invalidToolInputs[id]
            if (raw != null) return@map invalidJsonResult(id, raw)
            val input = call["input"] as? JsonObject ?: JsonObject(emptyMap())
            val schema = TaizoTools.schemaOf(name)
                ?: return@map errorResult(call, "Unknown tool: $name")
            ToolInputValidator.validate(schema, input)?.let { problem ->
                return@map invalidJsonResult(id, input.toString(), problem)
            }
            try {
                val question = tools.confirmationFor(name, input)
                if (question != null && !listener.confirm(question)) {
                    return@map toolResult(id, """{"declined":true,"message":"The user chose not to do this."}""", isError = false)
                }
                toolResult(id, tools.run(name, input), isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorResult(call, e.message ?: "The tool failed.")
            }
        }

    private fun collectSources(turn: ClaudeTurn, into: MutableMap<String, TaizoSource>) {
        for (block in turn.content) {
            val obj = block as? JsonObject ?: continue
            if (obj.str("type") != "text") continue
            val citations = obj["citations"] as? JsonArray ?: continue
            for (citation in citations) {
                val c = citation as? JsonObject ?: continue
                val url = c.str("url") ?: continue
                val title = c.str("title") ?: c.str("document_title") ?: url
                into.putIfAbsent(url, TaizoSource(url, title))
            }
        }
    }

    companion object {
        fun assistantMessage(turn: ClaudeTurn): JsonObject = buildJsonObject {
            put("role", "assistant")
            put("content", turn.content)
        }

        fun toolResultsMessage(results: List<JsonObject>): JsonObject = buildJsonObject {
            put("role", "user")
            put("content", JsonArray(results))
        }

        fun toolResult(toolUseId: String, content: String, isError: Boolean): JsonObject = buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", toolUseId)
            put("content", content)
            if (isError) put("is_error", true)
        }

        private fun errorResult(call: JsonObject, message: String): JsonObject =
            toolResult(call.str("id").orEmpty(), message, isError = true)

        /** `{"INVALID_JSON": "<raw>"}`, built with the JSON library so quotes in [raw] are escaped. */
        fun invalidJsonResult(toolUseId: String, raw: String, problem: String? = null): JsonObject {
            val payload = buildJsonObject {
                put("INVALID_JSON", raw)
                if (problem != null) put("problem", problem)
            }
            return toolResult(toolUseId, payload.toString(), isError = true)
        }

        /**
         * Adds a user turn. When the history already ends with a user message (tool results left
         * over from a turn that ran out of steps), the blocks join it: roles must alternate.
         */
        fun appendUserContent(messages: MutableList<JsonObject>, content: JsonArray) {
            val last = messages.lastOrNull()
            if (last != null && last.str("role") == "user") {
                val previous = when (val c = last["content"]) {
                    is JsonArray -> c.toList()
                    is JsonPrimitive -> listOf(buildJsonObject { put("type", "text"); put("text", c.contentOrNull.orEmpty()) })
                    else -> emptyList()
                }
                messages[messages.lastIndex] = buildJsonObject {
                    put("role", "user")
                    put("content", JsonArray(previous + content))
                }
            } else {
                messages += buildJsonObject {
                    put("role", "user")
                    put("content", content)
                }
            }
        }

        /**
         * Keeps the conversation under [maxMessages] by dropping its oldest exchanges. It only cuts
         * in front of a user message that is the user's own words (not tool results), so every
         * tool_use keeps its tool_result and the history still starts with the user.
         */
        fun trimHistory(messages: List<JsonObject>, maxMessages: Int = MAX_HISTORY_MESSAGES): List<JsonObject> {
            if (messages.size <= maxMessages) return messages
            for (i in (messages.size - maxMessages) until messages.size) {
                val message = messages[i]
                if (message.str("role") != "user") continue
                val content = message["content"]
                val hasToolResult = content is JsonArray &&
                    content.any { (it as? JsonObject)?.str("type") == "tool_result" }
                if (!hasToolResult) return messages.subList(i, messages.size).toList()
            }
            return messages
        }

        const val MAX_HISTORY_MESSAGES = 60

        /** The user's own words in [content]: its last text block (the blocks before it are the app's context). */
        fun userMessageOf(content: JsonArray): String {
            for (i in content.indices.reversed()) {
                val obj = content[i] as? JsonObject ?: continue
                if (obj.str("type") == "text") return obj.str("text").orEmpty()
            }
            return ""
        }

        fun textContent(text: String): JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

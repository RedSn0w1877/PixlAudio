package com.theveloper.pixelplay.data.ai.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One request to the Messages API, as raw JSON pieces (the SDK carries them as JsonValue). */
data class ClaudeRequest(
    /** System prompt as text blocks; the last stable block carries cache_control. */
    val system: JsonArray,
    val messages: JsonArray,
    /** Tool definitions in a fixed order (the order is part of the cached prefix). Null = no tools. */
    val tools: JsonArray? = null,
    val maxTokens: Long,
    val effort: ClaudeEffort,
)

/** One complete assistant response, streamed or not. */
data class ClaudeTurn(
    /** The assistant `content` array exactly as the API produced it (sent back unchanged). */
    val content: JsonArray,
    val stopReason: String?,
    val usage: ClaudeUsage,
    val model: String? = null,
    /** tool_use id -> the raw input that was not a valid JSON object (eager input streaming). */
    val invalidToolInputs: Map<String, String> = emptyMap(),
) {
    /** All text blocks, joined. */
    val text: String
        get() = content.mapNotNull { block ->
            (block as? JsonObject)?.takeIf { it.type == "text" }?.string("text")
        }.joinToString("")

    /** The client tool calls of this turn, in order. */
    val toolUses: List<JsonObject>
        get() = content.mapNotNull { (it as? JsonObject)?.takeIf { block -> block.type == "tool_use" } }
}

/** Why a Claude request failed, which decides between retrying, falling back and telling the user. */
enum class ClaudeFailure {
    /** No connection, or it dropped mid-way: answer on the phone. */
    OFFLINE,
    /** 429 / 5xx / overloaded after the SDK's retries. */
    BUSY,
    /** 400 / 401 / 403 and anything else the API rejected: no retry, "Cloud AI isn't available right now". */
    UNAVAILABLE,
    /** No built-in key in this build. */
    NO_KEY,
    /** This month's budget for the built-in key is used up. */
    OVER_BUDGET,
    /** stop_reason "refusal" (after the server-side fallback): stop, never run that turn's tools. */
    REFUSED,
}

class ClaudeException(
    val failure: ClaudeFailure,
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Every failure except a refusal is answered by the on-device model instead. */
    val fallsBackOnDevice: Boolean get() = failure != ClaudeFailure.REFUSED
}

internal val JsonObject.type: String? get() = string("type")

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

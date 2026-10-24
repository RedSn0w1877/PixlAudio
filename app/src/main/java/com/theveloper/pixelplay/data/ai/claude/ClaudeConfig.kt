package com.theveloper.pixelplay.data.ai.claude

/**
 * Constants for Claude in the cloud (every AI feature when the phone is online, and Ask Taizo).
 * Same values on iOS.
 *
 * Request rules (API version 2026-10-21, which anthropic-java 3.x sends by default):
 * - never send `temperature`, `top_p` or `top_k` (any value is a 400 on this version);
 * - never send `thinking` (omitted = adaptive; `{type: "disabled"}` is a 400 on Sonnet 5.5);
 * - depth goes through `output_config.effort`;
 * - `tool_choice` stays automatic (forced tool use is a 400 on Sonnet 5.5);
 * - every request opts into the server-side refusal fallback (`fallbacks: "default"` under
 *   [FALLBACK_BETA]).
 */
object ClaudeConfig {
    const val MODEL = "claude-sonnet-5-5"

    /** Beta header that gates the scalar `"fallbacks": "default"` form. */
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    const val FALLBACKS_VALUE = "default"

    /** One-shot features (playlists, greeting, metadata, translations). */
    const val MAX_TOKENS = 16_000L

    /** Streamed requests (Ask Taizo). */
    const val MAX_TOKENS_STREAMING = 32_000L

    /** The single retry after a turn ended at max_tokens with a tool call in it. */
    const val MAX_TOKENS_RETRY = 64_000L

    /** web_search / web_fetch uses per request. */
    const val WEB_TOOL_MAX_USES = 10

    /** pause_turn resends before the turn is given up. */
    const val MAX_PAUSE_CONTINUATIONS = 5

    /** Model calls per user message (tool rounds included). */
    const val MAX_AGENT_STEPS = 12

    /** Per-device monthly cap for the built-in key; Developer mode moves it between 0 and [MAX_MONTHLY_CAP_USD]. */
    const val DEFAULT_MONTHLY_CAP_USD = 50.0
    const val MAX_MONTHLY_CAP_USD = 200.0

    const val WEB_SEARCH_TOOL_TYPE = "web_search_20260209"
    const val WEB_FETCH_TOOL_TYPE = "web_fetch_20260209"
}

/** `output_config.effort`. Medium for chat, high for playlist building and insights (owner, 2026-10-23). */
enum class ClaudeEffort(val wire: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

package com.theveloper.pixelplay.data.ai.claude

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.ceil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What one Claude response consumed, from its `usage` object. */
data class ClaudeUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val webSearchRequests: Long = 0,
) {
    operator fun plus(other: ClaudeUsage) = ClaudeUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
        webSearchRequests = webSearchRequests + other.webSearchRequests,
    )

    /**
     * This usage with the fields present in [update] replacing ours. A stream's message_delta
     * carries cumulative totals for the fields it names; the rest stay as message_start said.
     */
    fun overriddenBy(update: JsonObject?): ClaudeUsage {
        if (update == null) return this
        return ClaudeUsage(
            inputTokens = update.long("input_tokens") ?: inputTokens,
            outputTokens = update.long("output_tokens") ?: outputTokens,
            cacheReadTokens = update.long("cache_read_input_tokens") ?: cacheReadTokens,
            cacheWriteTokens = update.long("cache_creation_input_tokens") ?: cacheWriteTokens,
            webSearchRequests = (update["server_tool_use"] as? JsonObject)?.long("web_search_requests")
                ?: webSearchRequests,
        )
    }

    companion object {
        fun fromJson(usage: JsonObject?): ClaudeUsage = ClaudeUsage().overriddenBy(usage)

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    }
}

/**
 * Claude Sonnet 5.5 prices for the built-in key's budget: $2 / MTok input, $10 / MTok output,
 * $0.20 / MTok cache reads, $2.50 / MTok 5-minute cache writes (1.25x input) and $10 per 1,000
 * web searches. Amounts are micro-dollars so the stored total stays an exact integer.
 */
object ClaudePricing {
    const val INPUT_USD_PER_MTOK = 2.0
    const val OUTPUT_USD_PER_MTOK = 10.0
    const val CACHE_READ_USD_PER_MTOK = 0.20
    const val CACHE_WRITE_USD_PER_MTOK = 2.50
    const val WEB_SEARCH_USD_PER_THOUSAND = 10.0

    /** Cost of [usage] in micro-dollars, rounded up. */
    fun costMicros(usage: ClaudeUsage): Long {
        // USD per MTok is exactly micro-dollars per token.
        val micros = usage.inputTokens * INPUT_USD_PER_MTOK +
            usage.outputTokens * OUTPUT_USD_PER_MTOK +
            usage.cacheReadTokens * CACHE_READ_USD_PER_MTOK +
            usage.cacheWriteTokens * CACHE_WRITE_USD_PER_MTOK +
            usage.webSearchRequests * WEB_SEARCH_USD_PER_THOUSAND * 1_000.0
        return ceil(micros).toLong()
    }

    fun usdToMicros(usd: Double): Long = (usd * 1_000_000.0).toLong()

    fun microsToUsd(micros: Long): Double = micros / 1_000_000.0
}

/** The monthly budget rules, pure (ClaudeBudgetTest). */
object ClaudeBudgetRules {
    /** "2026-10": spending resets when this changes. */
    fun monthKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        YearMonth.from(Instant.ofEpochMilli(epochMs).atZone(zone)).toString()

    /** A zero cap turns the built-in key off; otherwise the month is over budget once spending reaches the cap. */
    fun isExhausted(spentMicros: Long, capMicros: Long): Boolean = capMicros <= 0L || spentMicros >= capMicros

    /** The cap a slider value means, clamped to 0..[ClaudeConfig.MAX_MONTHLY_CAP_USD]. */
    fun clampCapUsd(usd: Double): Double =
        if (usd.isNaN()) ClaudeConfig.DEFAULT_MONTHLY_CAP_USD else usd.coerceIn(0.0, ClaudeConfig.MAX_MONTHLY_CAP_USD)
}

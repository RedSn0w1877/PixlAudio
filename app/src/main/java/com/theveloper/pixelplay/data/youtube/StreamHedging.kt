package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Streaming speed R8: overlap the InnerTube clients when one is slow. When the newest client
 * hasn't answered within [afterMs], the next one starts as well; the first URL wins and the
 * others are cancelled. Off unless `remote/config.json` turns it on (see [StreamRemoteFlags]).
 *
 * Same switch, defaults and clamps as iOS (PixlNet `StreamHedging` / `RemoteClientConfig`):
 * `innertube.hedge = {"enabled": false, "afterSeconds": 1.5, "strategyTimeoutSeconds": 8}`.
 * Only `"enabled": true` turns it on; missing or odd numbers fall back to the defaults and are
 * clamped (start the next client after 0.5–10 s; 3–15 s per client).
 */
data class StreamHedging(
    val afterMs: Long = DEFAULT_AFTER_MS,
    val strategyTimeoutMs: Long = DEFAULT_STRATEGY_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_AFTER_MS = 1_500L
        const val DEFAULT_STRATEGY_TIMEOUT_MS = 8_000L
        /** Newer files may change the format; Android only understands schema 1. */
        const val SCHEMA = 1

        fun of(afterSeconds: Double?, strategyTimeoutSeconds: Double?): StreamHedging = StreamHedging(
            afterMs = clampedMs(afterSeconds, 0.5, 10.0, DEFAULT_AFTER_MS),
            strategyTimeoutMs = clampedMs(strategyTimeoutSeconds, 3.0, 15.0, DEFAULT_STRATEGY_TIMEOUT_MS)
        )

        private fun clampedMs(seconds: Double?, min: Double, max: Double, defaultMs: Long): Long {
            if (seconds == null || !seconds.isFinite()) return defaultMs
            return (seconds.coerceIn(min, max) * 1_000).toLong()
        }

        /**
         * Parses `remote/config.json` (the iOS repo's file; Android reads only the hedge object
         * and keeps its own client table). Null (hedging off) for anything but a schema-1 file
         * whose `innertube.hedge.enabled` is the JSON boolean `true`.
         */
        fun parse(text: String): StreamHedging? {
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val schema = (root["schema"] as? JsonPrimitive)?.intOrNull ?: 1
            if (schema > SCHEMA) return null
            val innertube = root["innertube"] as? JsonObject ?: return null
            val hedge = innertube["hedge"] as? JsonObject ?: return null
            val enabled = (hedge["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            if (enabled != true) return null
            return of(
                afterSeconds = (hedge["afterSeconds"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull,
                strategyTimeoutSeconds = (hedge["strategyTimeoutSeconds"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            )
        }
    }
}

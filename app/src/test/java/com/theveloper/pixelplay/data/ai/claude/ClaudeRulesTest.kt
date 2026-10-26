package com.theveloper.pixelplay.data.ai.claude

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoTools
import com.theveloper.pixelplay.data.ai.local.AiRoute
import com.theveloper.pixelplay.data.ai.local.AiRouteResolver
import com.theveloper.pixelplay.data.ai.local.LocalEngineId
import com.theveloper.pixelplay.data.ai.local.NanoStatus
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import com.theveloper.pixelplay.presentation.viewmodel.AiAvailability
import com.theveloper.pixelplay.presentation.viewmodel.AiAvailabilityResolver
import com.theveloper.pixelplay.presentation.viewmodel.ClaudeStatus
import com.theveloper.pixelplay.presentation.viewmodel.ClaudeStatusResolver
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class ClaudeRulesTest {

    // --- Routing ("always cloud, offline -> local") ---

    private fun route(
        provider: String = "ON_DEVICE",
        developerMode: Boolean = false,
        key: Boolean = true,
        online: Boolean = true,
        budget: Boolean = true,
        downloaded: Boolean = false,
    ) = AiRouteResolver.resolveAutomatic(provider, developerMode, key, online, budget, downloaded, downloaded)

    @Test
    fun `online with the key and budget left goes to Claude`() {
        assertThat(route()).isEqualTo(AiRoute.Claude)
    }

    @Test
    fun `offline, no key or no budget falls back to the phone`() {
        assertThat(route(online = false)).isEqualTo(AiRoute.OnDevice(LocalEngineId.NANO))
        assertThat(route(key = false)).isEqualTo(AiRoute.OnDevice(LocalEngineId.NANO))
        assertThat(route(budget = false)).isEqualTo(AiRoute.OnDevice(LocalEngineId.NANO))
        assertThat(route(online = false, downloaded = true)).isEqualTo(AiRoute.OnDevice(LocalEngineId.GEMMA))
    }

    @Test
    fun `an own-key cloud assistant only overrides in developer mode`() {
        assertThat(route(provider = "GROQ", developerMode = true)).isEqualTo(AiRoute.Cloud(AiProvider.GROQ))
        assertThat(route(provider = "GROQ", developerMode = true, online = false)).isEqualTo(AiRoute.Cloud(AiProvider.GROQ))
        assertThat(route(provider = "GROQ", developerMode = false)).isEqualTo(AiRoute.Claude)
    }

    @Test
    fun `availability says ready on Claude whatever Nano can do`() {
        val available = AiAvailabilityResolver.resolve(
            provider = AiProvider.ON_DEVICE,
            apiKey = "",
            baseUrl = "",
            useDownloadedModel = false,
            downloadedModelReady = false,
            nanoStatus = NanoStatus.NeedsDownload,
            developerMode = false,
            claudeAvailable = true,
        )
        assertThat(available).isEqualTo(AiAvailability.Ready(AiRoute.Claude))
    }

    @Test
    fun `status line follows the route`() {
        fun status(online: Boolean = true, key: Boolean = true, exhausted: Boolean = false, failure: ClaudeFailure? = null) =
            ClaudeStatusResolver.resolve("ON_DEVICE", false, key, online, exhausted, failure)
        assertThat(status()).isEqualTo(ClaudeStatus.Claude)
        assertThat(status(online = false)).isEqualTo(ClaudeStatus.Offline)
        assertThat(status(key = false)).isEqualTo(ClaudeStatus.NoKey)
        assertThat(status(exhausted = true)).isEqualTo(ClaudeStatus.OverBudget)
        assertThat(status(failure = ClaudeFailure.UNAVAILABLE)).isEqualTo(ClaudeStatus.Unavailable)
        assertThat(ClaudeStatusResolver.resolve("OPENAI", true, true, true, false, null))
            .isEqualTo(ClaudeStatus.OwnCloud(AiProvider.OPENAI.displayName))
    }

    // --- Budget math ---

    @Test
    fun `cost uses Sonnet 5_5 prices`() {
        // 1M input = $2, 1M output = $10, 1M cache reads = $0.20, 1M cache writes = $2.50, 1000 searches = $10.
        assertThat(ClaudePricing.costMicros(ClaudeUsage(inputTokens = 1_000_000))).isEqualTo(2_000_000)
        assertThat(ClaudePricing.costMicros(ClaudeUsage(outputTokens = 1_000_000))).isEqualTo(10_000_000)
        assertThat(ClaudePricing.costMicros(ClaudeUsage(cacheReadTokens = 1_000_000))).isEqualTo(200_000)
        assertThat(ClaudePricing.costMicros(ClaudeUsage(cacheWriteTokens = 1_000_000))).isEqualTo(2_500_000)
        assertThat(ClaudePricing.costMicros(ClaudeUsage(webSearchRequests = 1_000))).isEqualTo(10_000_000)
        // A typical chat turn: fractions of a cent, rounded up to whole micro-dollars.
        assertThat(ClaudePricing.costMicros(ClaudeUsage(inputTokens = 3, outputTokens = 1))).isEqualTo(16)
    }

    @Test
    fun `the month rolls over and the cap gates spending`() {
        val oct31 = java.time.LocalDateTime.of(2026, 10, 31, 23, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        val nov1 = java.time.LocalDateTime.of(2026, 11, 1, 0, 1).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertThat(ClaudeBudgetRules.monthKey(oct31, ZoneOffset.UTC)).isEqualTo("2026-10")
        assertThat(ClaudeBudgetRules.monthKey(nov1, ZoneOffset.UTC)).isEqualTo("2026-11")
        assertThat(ClaudeBudgetRules.isExhausted(spentMicros = 49_999_999, capMicros = 50_000_000)).isFalse()
        assertThat(ClaudeBudgetRules.isExhausted(spentMicros = 50_000_000, capMicros = 50_000_000)).isTrue()
        assertThat(ClaudeBudgetRules.isExhausted(spentMicros = 0, capMicros = 0)).isTrue()
        assertThat(ClaudeBudgetRules.clampCapUsd(500.0)).isEqualTo(ClaudeConfig.MAX_MONTHLY_CAP_USD)
        assertThat(ClaudeBudgetRules.clampCapUsd(-1.0)).isEqualTo(0.0)
        assertThat(ClaudeConfig.DEFAULT_MONTHLY_CAP_USD).isEqualTo(50.0)
    }

    @Test
    fun `usage reads the API fields, web searches included`() {
        val usage = ClaudeUsage.fromJson(
            Json.parseToJsonElement(
                """{"input_tokens":10,"output_tokens":20,"cache_read_input_tokens":30,"cache_creation_input_tokens":40,"server_tool_use":{"web_search_requests":2}}"""
            ).jsonObject
        )
        assertThat(usage).isEqualTo(ClaudeUsage(10, 20, 30, 40, 2))
    }

    // --- Key shares ---

    @Test
    fun `the key is the XOR of its two shares`() {
        val key = "sk-ant-test-123"
        val pad = ByteArray(key.length) { (it * 37 + 11).toByte() }
        val masked = ByteArray(key.length) { (key[it].code xor pad[it].toInt()).toByte() }
        fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertThat(BuiltInClaudeKey.decode(masked.hex(), pad.hex())).isEqualTo(key)
        assertThat(BuiltInClaudeKey.decode("", "")).isNull()
        assertThat(BuiltInClaudeKey.decode("abcd", "ab")).isNull()
        assertThat(BuiltInClaudeKey.decode("zz", "00")).isNull()
    }

    // --- Tool schemas and validation ---

    @Test
    fun `every client tool is strict, closed and eager`() {
        val tools = TaizoTools.requestTools(withWeb = true)
        val client = tools.map { it as JsonObject }.filter { it["input_schema"] != null }
        assertThat(client.map { (it["name"] as JsonPrimitive).content }).containsExactlyElementsIn(TaizoTools.clientToolNames).inOrder()
        client.forEach { tool ->
            // search_library is the one exception (too many optional filters for the strict grammar); see TaizoTools.nonStrictToolNames.
            if ((tool["name"] as JsonPrimitive).content in TaizoTools.nonStrictToolNames) {
                assertThat(tool["strict"]).isNull()
            } else {
                assertThat(tool["strict"]).isEqualTo(JsonPrimitive(true))
            }
            assertThat(tool["eager_input_streaming"]).isEqualTo(JsonPrimitive(true))
            assertThat(tool["input_schema"]!!.jsonObject["additionalProperties"]).isEqualTo(JsonPrimitive(false))
        }
        val server = tools.map { it as JsonObject }.filter { it["input_schema"] == null }
        assertThat(server.map { (it["type"] as JsonPrimitive).content })
            .containsExactly(ClaudeConfig.WEB_SEARCH_TOOL_TYPE, ClaudeConfig.WEB_FETCH_TOOL_TYPE).inOrder()
        server.forEach { assertThat(it["eager_input_streaming"]).isNull() }
    }

    @Test
    fun `inputs are validated against the schema`() {
        val schema = TaizoTools.schemaOf(TaizoTools.PLAYBACK_CONTROL)!!
        fun check(json: String) = ToolInputValidator.validate(schema, Json.parseToJsonElement(json))
        assertThat(check("""{"action":"seek","position_ms":1000}""")).isNull()
        assertThat(check("""{"action":"rewind"}""")).contains("one of")
        assertThat(check("""{}""")).contains("required")
        assertThat(check("""{"action":"play","volume":3}""")).contains("not an allowed property")
        assertThat(check("""{"action":"seek","position_ms":"10"}""")).contains("integer")

        val play = TaizoTools.schemaOf(TaizoTools.PLAY_SONGS)!!
        assertThat(ToolInputValidator.validate(play, Json.parseToJsonElement("""{"song_ids":["1",2]}"""))).contains("string")
        assertThat(ToolInputValidator.validate(play, Json.parseToJsonElement("""{"song_ids":["1","2"],"start_index":1}"""))).isNull()
        assertThat(ToolInputValidator.validate(play, JsonArray(emptyList()))).contains("object")
    }
}

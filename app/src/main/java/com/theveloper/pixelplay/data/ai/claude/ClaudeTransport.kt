package com.theveloper.pixelplay.data.ai.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import java.io.IOException
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Sends [ClaudeRequest]s. The real one goes through the Anthropic Java SDK; tests use a fake. */
interface ClaudeTransport {
    /** One non-streamed call. */
    suspend fun create(request: ClaudeRequest): ClaudeTurn

    /** One streamed call; [onText] gets text as it arrives, [onBlockStart] each new content block. */
    suspend fun stream(
        request: ClaudeRequest,
        onText: (String) -> Unit,
        onBlockStart: (JsonObject) -> Unit,
    ): ClaudeTurn
}

/**
 * [ClaudeTransport] over the official Anthropic Java SDK (anthropic-java 3.x, which sends
 * `anthropic-version: 2026-10-21`). The SDK does the HTTPS, auth, and the retries for
 * 429 / 5xx / connection errors (2, with backoff, honouring `retry-after`); 400 / 401 / 403 are
 * never retried.
 *
 * Bodies are built as raw JSON and handed to the SDK as [JsonValue]s, and responses are read raw
 * (`withRawResponse()`), so the assistant content goes back to the API byte-for-byte as it came
 * (thinking signatures, server tool blocks, citations) and the SSE stream is parsed by our own
 * tested [SseParser] / [ClaudeStreamAccumulator].
 *
 * Every request: model [ClaudeConfig.MODEL], `output_config.effort`, no temperature / top_p /
 * top_k, no thinking field (adaptive), and the server-side refusal fallback
 * (`fallbacks: "default"` with the [ClaudeConfig.FALLBACK_BETA] header).
 */
@Singleton
class SdkClaudeTransport @Inject constructor() : ClaudeTransport {

    @Volatile
    private var client: AnthropicClient? = null

    private fun client(): AnthropicClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: run {
                val key = BuiltInClaudeKey.get()
                    ?: throw ClaudeException(ClaudeFailure.NO_KEY, "No built-in Claude key in this build")
                AnthropicOkHttpClient.builder()
                    .apiKey(key)
                    .maxRetries(MAX_RETRIES)
                    .build()
                    .also { client = it }
            }
        }
    }

    override suspend fun create(request: ClaudeRequest): ClaudeTurn = withContext(Dispatchers.IO) {
        val params = buildParams(request)
        val body = call {
            client().messages().withRawResponse().create(params).use { response ->
                response.body().readBytes().toString(Charsets.UTF_8)
            }
        }
        val json = runCatching { ClaudeStreamAccumulator.STRICT.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: throw ClaudeException(ClaudeFailure.UNAVAILABLE, "Unreadable response")
        ClaudeTurn(
            content = json["content"] as? JsonArray ?: JsonArray(emptyList()),
            stopReason = json.string("stop_reason"),
            usage = ClaudeUsage.fromJson(json["usage"].asObject()),
            model = json.string("model"),
        )
    }

    // InternalCoroutinesApi: invokeOnCompletion(onCancelling = true), see below.
    @OptIn(InternalCoroutinesApi::class)
    override suspend fun stream(
        request: ClaudeRequest,
        onText: (String) -> Unit,
        onBlockStart: (JsonObject) -> Unit,
    ): ClaudeTurn = withContext(Dispatchers.IO) {
        val params = buildParams(request)
        val accumulator = ClaudeStreamAccumulator(onText = onText, onBlockStart = onBlockStart)
        val parser = SseParser()
        val job = currentCoroutineContext().job
        call {
            client().messages().withRawResponse().createStreaming(params).use { response ->
                // Cancelling the coroutine closes the stream, which unblocks readLine(). onCancelling: the job
                // only *completes* once this blocking read returns, so a plain completion handler would leave
                // the stream open until the next SSE line arrived.
                val closer = job.invokeOnCompletion(onCancelling = true) { runCatching { response.close() } }
                try {
                    response.body().bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            job.ensureActive()
                            val line = reader.readLine() ?: break
                            parser.feed(line)?.let { accumulator.accept(it.data) }
                            if (accumulator.finished) break
                        }
                        parser.finish()?.let { accumulator.accept(it.data) }
                    }
                } finally {
                    closer.dispose()
                }
            }
        }
        if (!accumulator.finished) {
            // The connection ended before message_stop: treat it like a dropped network.
            throw ClaudeException(ClaudeFailure.OFFLINE, "The stream ended early")
        }
        accumulator.result()
    }

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ClaudeException) {
        throw e
    } catch (e: AnthropicServiceException) {
        val status = e.statusCode()
        val failure = when {
            status == 429 || status == 408 || status == 409 || status >= 500 -> ClaudeFailure.BUSY
            else -> ClaudeFailure.UNAVAILABLE
        }
        throw ClaudeException(failure, "Claude API answered HTTP $status", status, e)
    } catch (e: AnthropicIoException) {
        throw ClaudeException(ClaudeFailure.OFFLINE, "Network error", cause = e)
    } catch (e: AnthropicRetryableException) {
        throw ClaudeException(ClaudeFailure.OFFLINE, "Network error", cause = e)
    } catch (e: InterruptedIOException) {
        throw ClaudeException(ClaudeFailure.OFFLINE, "Timed out", cause = e)
    } catch (e: IOException) {
        throw ClaudeException(ClaudeFailure.OFFLINE, "Network error", cause = e)
    }

    private fun buildParams(request: ClaudeRequest): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(ClaudeConfig.MODEL)
            .maxTokens(request.maxTokens)
            .messages(request.messages.toSdk())
            .system(request.system.toSdk())
            .outputConfig(OutputConfig.builder().effort(request.effort.toSdk()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeConfig.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from(ClaudeConfig.FALLBACKS_VALUE))
        request.tools?.takeIf { it.isNotEmpty() }?.let { builder.tools(it.toSdk()) }
        return builder.build()
    }

    private fun ClaudeEffort.toSdk(): OutputConfig.Effort = when (this) {
        ClaudeEffort.LOW -> OutputConfig.Effort.LOW
        ClaudeEffort.MEDIUM -> OutputConfig.Effort.MEDIUM
        ClaudeEffort.HIGH -> OutputConfig.Effort.HIGH
    }

    private fun JsonElement.toSdk(): JsonValue = JsonValue.fromJsonNode(jsonMapper().readTree(toString()))

    private companion object {
        const val MAX_RETRIES = 2
    }
}

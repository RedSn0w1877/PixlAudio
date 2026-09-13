package com.theveloper.pixelplay.data.ai.ondevice

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.theveloper.pixelplay.data.ai.provider.AiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Runs inference fully offline via MediaPipe's GenAI runtime against the file
 * [OnDeviceModelManager] holds. No network call, no API key.
 *
 * [LlmInference.LlmInferenceOptions] (confirmed against MediaPipe's source — the online docs for
 * this API disagree with each other and with the actual class) only exposes `setMaxTokens` and
 * `setMaxTopK` — an upper bound, not a per-call sampling value — plus model path/vision/audio/
 * LoRA/backend options. There's no engine-level temperature or per-call top-K, and the plain
 * `generateResponse(String)` entry point (as opposed to building an `LlmInferenceSession`, which
 * *does* expose per-call temperature/topK but adds real complexity for a first cut of this
 * feature) doesn't accept sampling overrides either — so unlike the cloud clients, the
 * `temperature`/`topK`/`topP` args this method receives are silently unused; on-device generation
 * always runs at the model's/runtime's built-in defaults. Revisit via `LlmInferenceSession` if
 * that turns out to matter in practice.
 *
 * The engine itself is built once, lazily, on first use — rebuilding it means reloading the whole
 * model file (hundreds of MB to a few GB) off disk, so it's kept alive and reused for every call
 * after that.
 */
class OnDeviceAiClient(
    private val context: Context,
    private val modelPath: String
) : AiClient {

    private val engineMutex = Mutex()
    private var engine: LlmInference? = null

    private suspend fun getOrCreateEngine(maxTokens: Int): LlmInference = engineMutex.withLock {
        engine?.let { return it }
        withContext(Dispatchers.IO) {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens.coerceIn(256, 4096))
                .setMaxTopK(64)
                .build()
            LlmInference.createFromOptions(context, options).also { engine = it }
        }
    }

    override suspend fun generateContent(
        model: String,
        systemPrompt: String,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        presencePenalty: Float,
        frequencyPenalty: Float
    ): String = withContext(Dispatchers.IO) {
        val llm = getOrCreateEngine(maxTokens)
        // No separate system-prompt channel in the sync generateResponse API — fold it into
        // the single prompt string the same way a plain completion model would expect it.
        val fullPrompt = if (systemPrompt.isNotBlank()) {
            "$systemPrompt\n\n$prompt"
        } else {
            prompt
        }
        try {
            llm.generateResponse(fullPrompt)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "On-device generation failed")
            throw e
        }
    }

    override suspend fun countTokens(model: String, systemPrompt: String, prompt: String): Int {
        // No dedicated tokenizer call exposed for a rough estimate; same heuristic the other
        // generic clients use.
        return (systemPrompt.length + prompt.length) / 4
    }

    override suspend fun getAvailableModels(apiKey: String): List<String> = listOf(getDefaultModel())

    override suspend fun validateApiKey(apiKey: String): Boolean = true

    override fun getDefaultModel(): String = "on-device"

    fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        private const val TAG = "OnDeviceAiClient"
    }
}

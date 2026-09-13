package com.theveloper.pixelplay.data.ai.provider

import android.content.Context
import com.theveloper.pixelplay.data.ai.ondevice.OnDeviceAiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating AI client instances based on provider type
 */
@Singleton
class AiClientFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** [AiProvider.ON_DEVICE] has no API key/URL — it needs the imported model's file path instead. */
    fun createOnDeviceClient(modelPath: String): AiClient = OnDeviceAiClient(context, modelPath)


    /**
     * Create an AI client for the specified provider
     * @param provider The AI provider type
     * @param apiKey The API key for the provider
     * @return AiClient instance
     */
    fun createClient(provider: AiProvider, apiKey: String): AiClient {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("API Key cannot be blank for ${provider.displayName}")
        }
        
        return when (provider) {
            AiProvider.GEMINI -> GeminiAiClient(apiKey)
            AiProvider.DEEPSEEK -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://api.deepseek.com",
                defaultModelId = "deepseek-chat",
                providerName = "DeepSeek"
            )
            AiProvider.GROQ -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://api.groq.com/openai/v1",
                defaultModelId = "llama-3.1-8b-instant",
                providerName = "Groq"
            )
            AiProvider.MISTRAL -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://api.mistral.ai/v1",
                defaultModelId = "mistral-large-latest",
                providerName = "Mistral"
            )
            AiProvider.NVIDIA -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://integrate.api.nvidia.com/v1",
                defaultModelId = "meta/llama-3.1-8b-instruct",
                providerName = "NVIDIA NIM"
            )
            AiProvider.KIMI -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://api.moonshot.cn/v1",
                defaultModelId = "moonshot-v1-8k",
                providerName = "Moonshot Kimi"
            )
            AiProvider.GLM -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                defaultModelId = "glm-4",
                providerName = "Zhipu GLM"
            )
            AiProvider.OPENAI -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://api.openai.com/v1",
                defaultModelId = "gpt-4o-mini",
                providerName = "OpenAI"
            )
            AiProvider.OPENROUTER -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://openrouter.ai/api/v1",
                defaultModelId = "google/gemini-2.0-flash-lite-preview-02-05:free",
                providerName = "OpenRouter"
            )
            // OLLAMA and CUSTOM both have hasConfigurableUrl = true, so every real call site
            // (AiHandler, SettingsViewModel.fetchAvailableModels) routes them through
            // createClientWithUrl instead — this branch only exists to keep the `when`
            // exhaustive and is never actually reached for either provider.
            AiProvider.OLLAMA -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "",
                defaultModelId = "llama3",
                providerName = "Ollama"
            )
            AiProvider.CUSTOM -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "",
                defaultModelId = "",
                providerName = "Custom Provider"
            )
            AiProvider.ON_DEVICE -> throw IllegalStateException(
                "AiProvider.ON_DEVICE must go through createOnDeviceClient(modelPath), not createClient() — it has no API key to build from."
            )
        }
    }

    fun createClientWithUrl(provider: AiProvider, apiKey: String, baseUrl: String): AiClient {
        val displayName = provider.displayName
        return GenericOpenAiClient(apiKey, baseUrl.trimEnd('/'), "", displayName)
    }
}

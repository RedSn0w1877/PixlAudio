package com.theveloper.pixelplay.data.ai.provider

/**
 * Enum representing available AI providers
 */
enum class AiProvider(val displayName: String, val requiresApiKey: Boolean, val hasConfigurableUrl: Boolean = false) {
    GEMINI("Google Gemini", requiresApiKey = true),
    DEEPSEEK("DeepSeek", requiresApiKey = true),
    GROQ("Groq", requiresApiKey = true),
    MISTRAL("Mistral", requiresApiKey = true),
    NVIDIA("NVIDIA NIM", requiresApiKey = true),
    KIMI("Kimi (Moonshot)", requiresApiKey = true),
    GLM("Zhipu GLM", requiresApiKey = true),
    OPENAI("OpenAI", requiresApiKey = true),
    OPENROUTER("OpenRouter", requiresApiKey = true),
    // Local-network model runner, not a cloud API: there is no fixed URL to hardcode (it runs
    // on whatever host/port the user started it on, e.g. http://localhost:11434), and the
    // default server has no authentication at all — forcing an API key here just blocks anyone
    // from actually using it.
    OLLAMA("Ollama", requiresApiKey = false, hasConfigurableUrl = true),
    CUSTOM("Custom Provider", requiresApiKey = true, hasConfigurableUrl = true),
    // The phone's own AI, and the default for every feature: Gemini Nano through AICore, or the
    // optional downloaded model (data/ai/local/LocalAi). Same value as iOS's on-device provider, so
    // cross-platform backups line up. The display name must not say "Offline": error tables match
    // that word and used to turn every on-device failure into "No Internet Connection".
    ON_DEVICE("On-device", requiresApiKey = false, hasConfigurableUrl = false);

    companion object {
        /** Unknown or missing values mean on-device: an unrecognised setting must never route to the cloud. */
        fun fromString(value: String): AiProvider {
            return entries.find { it.name == value } ?: ON_DEVICE
        }

        /** The providers the "Cloud assistants" section offers. */
        val cloudProviders: List<AiProvider> get() = entries.filter { it != ON_DEVICE }
    }
}

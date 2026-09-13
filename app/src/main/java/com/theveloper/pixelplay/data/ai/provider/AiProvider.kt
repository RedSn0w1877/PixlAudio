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
    // Runs fully offline on the phone itself via MediaPipe's GenAI runtime — no network, no
    // server, no key. The model isn't bundled or downloaded automatically: Gemma's official
    // .task builds are gated behind a Hugging Face login + license acceptance, so the user
    // downloads it themselves once and imports the file (see OnDeviceModelManager).
    ON_DEVICE("On-Device (Offline)", requiresApiKey = false, hasConfigurableUrl = false);
    
    companion object {
        fun fromString(value: String): AiProvider {
            return entries.find { it.name == value } ?: GEMINI
        }
    }
}

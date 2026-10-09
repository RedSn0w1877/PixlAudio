package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
            You are 'Vibe-Engine', a professional music curator.
            Analyze the user's request and listening profile to provide perfect music recommendations.
            Always prioritize flow, emotional resonance, and discovery.
        """.trimIndent()
        
        val DEFAULT_DEEPSEEK_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_GROQ_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_MISTRAL_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_NVIDIA_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_KIMI_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_GLM_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_OPENAI_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT
        val DEFAULT_OPENROUTER_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT

        /**
         * Settings › AI features › "Use downloaded AI model". Device-local on purpose: the model
         * file lives in noBackupFilesDir, so the switch is kept out of .pxpl backups and survives
         * restores ([UserPreferencesRepository]'s backup exclusions list this name).
         *
         * Same name as iOS. It must not end in `_model`, `_api_key`, `_system_prompt` or
         * `_base_url`: iOS's backup catalogue treats those suffixes as portable per-provider AI
         * keys, which is exactly the bug iOS's review found in its first name for this switch.
         */
        const val DOWNLOADED_MODEL_ENABLED_KEY = "ai_downloaded_model_enabled"
        /** The cloud assistant the "Use a cloud assistant" switch turns back on (portable). */
        const val CLOUD_PROVIDER_KEY = "ai_cloud_provider"
        /** The one-time move of unusable cloud setups to on-device has run (portable, see [migrateProviderIfNeeded]). */
        const val PROVIDER_MIGRATED_KEY = "ai_provider_migrated_v1"
    }

    private object Keys {
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val SAFE_TOKEN_LIMIT = booleanPreferencesKey("safe_token_limit")
        val AI_TEMPERATURE = floatPreferencesKey("ai_temperature")
        val AI_TOP_P = floatPreferencesKey("ai_top_p")
        val AI_TOP_K = intPreferencesKey("ai_top_k")
        val AI_MAX_TOKENS = intPreferencesKey("ai_max_tokens")
        val AI_PRESENCE_PENALTY = floatPreferencesKey("ai_presence_penalty")
        val AI_FREQUENCY_PENALTY = floatPreferencesKey("ai_frequency_penalty")
        val AI_SAMPLE_SIZE = intPreferencesKey("ai_sample_size")
        val AI_DIGEST_MODE = stringPreferencesKey("ai_digest_mode")
        val AI_INCLUDE_EXTENDED_FIELDS = booleanPreferencesKey("ai_include_extended_fields")
        val AI_CLOUD_PROVIDER = stringPreferencesKey(CLOUD_PROVIDER_KEY)
        val AI_PROVIDER_MIGRATED = booleanPreferencesKey(PROVIDER_MIGRATED_KEY)
        val AI_DOWNLOADED_MODEL_ENABLED = booleanPreferencesKey(DOWNLOADED_MODEL_ENABLED_KEY)

        fun getApiKey(provider: AiProvider) = stringPreferencesKey("${provider.name.lowercase()}_api_key")
        fun getModel(provider: AiProvider) = stringPreferencesKey("${provider.name.lowercase()}_model")
        fun getSystemPrompt(provider: AiProvider) = stringPreferencesKey("${provider.name.lowercase()}_system_prompt")
        fun getBaseUrl(provider: AiProvider) = stringPreferencesKey("${provider.name.lowercase()}_base_url")
    }

    // Generic accessors for AiHandler
    fun getApiKey(provider: AiProvider): Flow<String> =
        dataStore.prefFlow { preferences -> preferences[Keys.getApiKey(provider)]?.trim() ?: "" }

    fun getModel(provider: AiProvider): Flow<String> =
        dataStore.prefFlow { preferences -> preferences[Keys.getModel(provider)] ?: "" }

    fun getSystemPrompt(provider: AiProvider): Flow<String> =
        dataStore.prefFlow { preferences ->
            preferences[Keys.getSystemPrompt(provider)] ?: DEFAULT_SYSTEM_PROMPT
        }

    fun getBaseUrl(provider: AiProvider): Flow<String> =
        dataStore.prefFlow { preferences -> preferences[Keys.getBaseUrl(provider)] ?: "" }

    suspend fun setApiKey(provider: AiProvider, apiKey: String) {
        dataStore.edit { preferences -> preferences[Keys.getApiKey(provider)] = apiKey.trim() }
    }

    suspend fun setModel(provider: AiProvider, model: String) {
        dataStore.edit { preferences -> preferences[Keys.getModel(provider)] = model }
    }

    suspend fun setSystemPrompt(provider: AiProvider, prompt: String) {
        dataStore.edit { preferences -> preferences[Keys.getSystemPrompt(provider)] = prompt }
    }

    suspend fun resetSystemPrompt(provider: AiProvider) {
        dataStore.edit { preferences ->
            preferences[Keys.getSystemPrompt(provider)] = DEFAULT_SYSTEM_PROMPT
        }
    }

    suspend fun setBaseUrl(provider: AiProvider, url: String) {
        dataStore.edit { preferences -> preferences[Keys.getBaseUrl(provider)] = url.trim() }
    }

    // Convenience properties for legacy compatibility (e.g. PlayerViewModel)
    val geminiApiKey: Flow<String> = getApiKey(AiProvider.GEMINI)
    val geminiModel: Flow<String> = getModel(AiProvider.GEMINI)
    val geminiSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.GEMINI)

    val deepseekApiKey: Flow<String> = getApiKey(AiProvider.DEEPSEEK)
    val deepseekModel: Flow<String> = getModel(AiProvider.DEEPSEEK)
    val deepseekSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.DEEPSEEK)

    val groqApiKey: Flow<String> = getApiKey(AiProvider.GROQ)
    val groqModel: Flow<String> = getModel(AiProvider.GROQ)
    val groqSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.GROQ)

    val mistralApiKey: Flow<String> = getApiKey(AiProvider.MISTRAL)
    val mistralModel: Flow<String> = getModel(AiProvider.MISTRAL)
    val mistralSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.MISTRAL)

    val nvidiaApiKey: Flow<String> = getApiKey(AiProvider.NVIDIA)
    val nvidiaModel: Flow<String> = getModel(AiProvider.NVIDIA)
    val nvidiaSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.NVIDIA)

    val kimiApiKey: Flow<String> = getApiKey(AiProvider.KIMI)
    val kimiModel: Flow<String> = getModel(AiProvider.KIMI)
    val kimiSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.KIMI)

    val glmApiKey: Flow<String> = getApiKey(AiProvider.GLM)
    val glmModel: Flow<String> = getModel(AiProvider.GLM)
    val glmSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.GLM)

    val openaiApiKey: Flow<String> = getApiKey(AiProvider.OPENAI)
    val openaiModel: Flow<String> = getModel(AiProvider.OPENAI)
    val openaiSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.OPENAI)

    val openrouterApiKey: Flow<String> = getApiKey(AiProvider.OPENROUTER)
    val openrouterModel: Flow<String> = getModel(AiProvider.OPENROUTER)
    val openrouterSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.OPENROUTER)

    val ollamaApiKey: Flow<String> = getApiKey(AiProvider.OLLAMA)
    val ollamaModel: Flow<String> = getModel(AiProvider.OLLAMA)
    val ollamaSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.OLLAMA)

    val customApiKey: Flow<String> = getApiKey(AiProvider.CUSTOM)
    val customModel: Flow<String> = getModel(AiProvider.CUSTOM)
    val customSystemPrompt: Flow<String> = getSystemPrompt(AiProvider.CUSTOM)
    val customBaseUrl: Flow<String> = getBaseUrl(AiProvider.CUSTOM)

    // On-device is the default for every AI feature (owner decision, 2026-10-07). A cloud
    // provider is only ever used when the user turns on "Use a cloud assistant".
    val aiProvider: Flow<String> =
        dataStore.prefFlow { preferences -> preferences[Keys.AI_PROVIDER] ?: AiProvider.ON_DEVICE.name }
            .distinctUntilChanged()

    /** The last cloud assistant chosen, restored when "Use a cloud assistant" is switched back on. */
    val aiCloudProvider: Flow<String> =
        dataStore.prefFlow { preferences -> validCloudProvider(preferences[Keys.AI_CLOUD_PROVIDER]) }
            .distinctUntilChanged()

    val aiDownloadedModelEnabled: Flow<Boolean> =
        dataStore.prefFlow { preferences -> preferences[Keys.AI_DOWNLOADED_MODEL_ENABLED] ?: false }
            .distinctUntilChanged()

    private fun validCloudProvider(stored: String?): String =
        stored?.takeIf { name -> name != AiProvider.ON_DEVICE.name && AiProvider.entries.any { it.name == name } }
            ?: AiProvider.GEMINI.name

    val isSafeTokenLimitEnabled: Flow<Boolean> =
        dataStore.prefFlow { preferences -> preferences[Keys.SAFE_TOKEN_LIMIT] ?: true }

    val aiTemperature: Flow<Float> =
        dataStore.prefFlow { preferences -> preferences[Keys.AI_TEMPERATURE] ?: 0.7f }

    val aiTopP: Flow<Float> =
        dataStore.prefFlow { preferences -> preferences[Keys.AI_TOP_P] ?: 0.95f }

    val aiTopK: Flow<Int> =
        dataStore.prefFlow { preferences -> preferences[Keys.AI_TOP_K] ?: 64 }

    val aiMaxTokens: Flow<Int> =
        dataStore.prefFlow { preferences -> preferences[Keys.AI_MAX_TOKENS] ?: 4096 }

    val aiPresencePenalty: Flow<Float> =
        dataStore.prefFlow { preferences -> preferences[Keys.AI_PRESENCE_PENALTY] ?: 0.0f }

    val aiFrequencyPenalty: Flow<Float> =
        dataStore.prefFlow { preferences -> preferences[Keys.AI_FREQUENCY_PENALTY] ?: 0.0f }

    val aiSampleSize: Flow<Int> =
        dataStore.prefFlow { preferences -> preferences[Keys.AI_SAMPLE_SIZE] ?: 40 }

    val aiDigestMode: Flow<String> =
        dataStore.prefFlow { preferences -> preferences[Keys.AI_DIGEST_MODE] ?: "safe" }

    val aiIncludeExtendedFields: Flow<Boolean> =
        dataStore.prefFlow { preferences -> preferences[Keys.AI_INCLUDE_EXTENDED_FIELDS] ?: false }

    suspend fun setAiProvider(provider: String) {
        dataStore.edit { preferences -> preferences[Keys.AI_PROVIDER] = provider }
    }

    /** Selects [provider] as the active cloud assistant and remembers it for the cloud switch. */
    suspend fun setCloudProvider(provider: AiProvider) {
        if (provider == AiProvider.ON_DEVICE) return
        dataStore.edit { preferences ->
            preferences[Keys.AI_PROVIDER] = provider.name
            preferences[Keys.AI_CLOUD_PROVIDER] = provider.name
        }
    }

    /** "Use a cloud assistant": on restores the last cloud provider, off goes back to on-device. */
    suspend fun setCloudAssistantEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            if (enabled) {
                preferences[Keys.AI_PROVIDER] = validCloudProvider(preferences[Keys.AI_CLOUD_PROVIDER])
            } else {
                val current = preferences[Keys.AI_PROVIDER]
                if (current != null && current != AiProvider.ON_DEVICE.name) {
                    preferences[Keys.AI_CLOUD_PROVIDER] = current
                }
                preferences[Keys.AI_PROVIDER] = AiProvider.ON_DEVICE.name
            }
        }
    }

    suspend fun setDownloadedModelEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.AI_DOWNLOADED_MODEL_ENABLED] = enabled }
    }

    /**
     * One-time move of AI setups that could never answer to on-device (owner decisions 4 and 5):
     * a provider that needs a key saved without one (Gemini, the old default, most often), and
     * Ollama / Custom without a base URL. Setups that work stay on their cloud provider, which is
     * also remembered as the cloud switch's provider. One [DataStore.edit], so it is atomic.
     *
     * Runs at startup and again after a settings restore: the .pxpl restore clears and re-imports
     * the shared "settings" DataStore, so an old backup brings back GEMINI without the flag and
     * gets converted once more (a backup with a Gemini key correctly stays on Gemini).
     *
     * @return true when the provider changed.
     */
    suspend fun migrateProviderIfNeeded(): Boolean {
        var changed = false
        dataStore.edit { preferences ->
            if (preferences[Keys.AI_PROVIDER_MIGRATED] == true) return@edit
            val stored = preferences[Keys.AI_PROVIDER]
            val decision = AiProviderMigrationRules.decide(
                storedProvider = stored,
                apiKeyFor = { provider -> preferences[Keys.getApiKey(provider)]?.trim().orEmpty() },
                baseUrlFor = { provider -> preferences[Keys.getBaseUrl(provider)]?.trim().orEmpty() },
            )
            val newProvider = decision.provider
            if (newProvider != null && newProvider != stored) {
                preferences[Keys.AI_PROVIDER] = newProvider
                changed = true
            }
            decision.cloudProvider?.let { preferences[Keys.AI_CLOUD_PROVIDER] = it }
            preferences[Keys.AI_PROVIDER_MIGRATED] = true
        }
        return changed
    }

    suspend fun setSafeTokenLimitEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.SAFE_TOKEN_LIMIT] = enabled }
    }

    suspend fun setAiTemperature(value: Float) {
        dataStore.edit { preferences -> preferences[Keys.AI_TEMPERATURE] = value }
    }

    suspend fun setAiTopP(value: Float) {
        dataStore.edit { preferences -> preferences[Keys.AI_TOP_P] = value }
    }

    suspend fun setAiTopK(value: Int) {
        dataStore.edit { preferences -> preferences[Keys.AI_TOP_K] = value }
    }

    suspend fun setAiMaxTokens(value: Int) {
        dataStore.edit { preferences -> preferences[Keys.AI_MAX_TOKENS] = value }
    }

    suspend fun setAiPresencePenalty(value: Float) {
        dataStore.edit { preferences -> preferences[Keys.AI_PRESENCE_PENALTY] = value }
    }

    suspend fun setAiFrequencyPenalty(value: Float) {
        dataStore.edit { preferences -> preferences[Keys.AI_FREQUENCY_PENALTY] = value }
    }

    suspend fun setAiSampleSize(value: Int) {
        dataStore.edit { preferences -> preferences[Keys.AI_SAMPLE_SIZE] = value }
    }

    suspend fun setAiDigestMode(mode: String) {
        dataStore.edit { preferences -> preferences[Keys.AI_DIGEST_MODE] = mode }
    }

    suspend fun setAiIncludeExtendedFields(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.AI_INCLUDE_EXTENDED_FIELDS] = enabled }
    }
}

/**
 * The rules behind [AiPreferencesRepository.migrateProviderIfNeeded], pure so they are tested
 * without a DataStore.
 */
internal object AiProviderMigrationRules {
    /** [provider]: the new ai_provider value (null = leave unset); [cloudProvider]: ai_cloud_provider to write. */
    data class Decision(val provider: String?, val cloudProvider: String?)

    fun decide(
        storedProvider: String?,
        apiKeyFor: (AiProvider) -> String,
        baseUrlFor: (AiProvider) -> String,
    ): Decision {
        if (storedProvider == null) {
            // Never picked: the old default was Gemini. With a Gemini key that was a working cloud
            // setup, so keep it on Gemini instead of silently moving it to the new default.
            return if (apiKeyFor(AiProvider.GEMINI).isNotBlank()) {
                Decision(AiProvider.GEMINI.name, AiProvider.GEMINI.name)
            } else {
                Decision(null, null)
            }
        }
        val provider = AiProvider.entries.find { it.name == storedProvider }
            ?: return Decision(AiProvider.ON_DEVICE.name, null)
        if (provider == AiProvider.ON_DEVICE) return Decision(storedProvider, null)
        val missingKey = provider.requiresApiKey && apiKeyFor(provider).isBlank()
        val missingUrl = provider.hasConfigurableUrl && baseUrlFor(provider).isBlank()
        return if (missingKey || missingUrl) {
            // Remember the choice so turning the cloud switch on later comes back to it.
            Decision(AiProvider.ON_DEVICE.name, provider.name)
        } else {
            Decision(storedProvider, provider.name)
        }
    }
}

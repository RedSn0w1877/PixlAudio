package com.theveloper.pixelplay.data.ai


import com.theveloper.pixelplay.data.ai.local.AiRoute
import com.theveloper.pixelplay.data.ai.local.AiRouteResolver
import com.theveloper.pixelplay.data.ai.local.LocalAi
import com.theveloper.pixelplay.data.ai.local.LocalGenerationRequest
import com.theveloper.pixelplay.data.ai.local.OnDevicePrompts
import com.theveloper.pixelplay.data.ai.local.TokenBudget
import com.theveloper.pixelplay.data.ai.provider.AiClientFactory
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import com.theveloper.pixelplay.data.database.AiCacheDao
import com.theveloper.pixelplay.data.database.AiCacheEntity
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import com.theveloper.pixelplay.data.database.AiUsageDao
import com.theveloper.pixelplay.data.database.AiUsageEntity
import com.theveloper.pixelplay.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiHandler @Inject constructor(
    private val preferencesRepo: AiPreferencesRepository,
    private val clientFactory: AiClientFactory,
    private val localAi: LocalAi,
    private val cacheDao: AiCacheDao,
    private val usageDao: AiUsageDao,
    private val promptEngine: AiSystemPromptEngine,
    @AppScope private val appScope: CoroutineScope
) {
    // Cooldown timer: Provider -> Expiry Timestamp
    private val providerCooldowns = mutableMapOf<AiProvider, Long>()
    private val COOLDOWN_DURATION_MS = 1000L * 60 * 5 // 5 minutes

    // Cache TTL: 30 minutes — prevents stale results from being served indefinitely
    private val CACHE_TTL_MS = 1000L * 60 * 30

    // Request timeout: 60 seconds max per provider attempt
    private val REQUEST_TIMEOUT_MS = 60_000L

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(this.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun getBasePersona(provider: AiProvider): String {
        return preferencesRepo.getSystemPrompt(provider).first()
            .ifBlank { AiPreferencesRepository.DEFAULT_SYSTEM_PROMPT }
    }

    private suspend fun getApiKey(provider: AiProvider): String {
        return preferencesRepo.getApiKey(provider).first()
    }

    private suspend fun getModel(provider: AiProvider): String {
        return preferencesRepo.getModel(provider).first()
    }

    private suspend fun setModel(provider: AiProvider, model: String) {
        preferencesRepo.setModel(provider, model)
    }

    private data class GenerationParams(
        val temperature: Float,
        val topP: Float,
        val topK: Int,
        val maxTokens: Int,
        val presencePenalty: Float,
        val frequencyPenalty: Float,
    )

    private data class GenerationResult(
        val response: String,
        val modelUsed: String,
    )

    private suspend fun getGenerationParams(): GenerationParams {
        return GenerationParams(
            temperature = preferencesRepo.aiTemperature.first(),
            topP = preferencesRepo.aiTopP.first(),
            topK = preferencesRepo.aiTopK.first(),
            maxTokens = preferencesRepo.aiMaxTokens.first(),
            presencePenalty = preferencesRepo.aiPresencePenalty.first(),
            frequencyPenalty = preferencesRepo.aiFrequencyPenalty.first(),
        )
    }

    private suspend fun generateWithRecovery(
        provider: AiProvider,
        apiKey: String,
        systemPrompt: String,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        presencePenalty: Float,
        frequencyPenalty: Float,
    ): GenerationResult {
        // Ollama/Custom have no fixed cloud URL — createClient() alone builds them pointed at a
        // placeholder that was never a real endpoint (Ollama in particular runs locally, there
        // is no "https://api.ollama.ai" to hardcode). Settings' own "test connection" already
        // routes through createClientWithUrl for hasConfigurableUrl providers; every real
        // generateContent() call needs the same routing or it silently uses the wrong client
        // regardless of what the user configured.
        val client = when {
            provider.hasConfigurableUrl -> {
                val baseUrl = preferencesRepo.getBaseUrl(provider).first()
                clientFactory.createClientWithUrl(provider, apiKey, baseUrl)
            }
            else -> clientFactory.createClient(provider, apiKey)
        }
        val requestedModel = getModel(provider).ifBlank { client.getDefaultModel() }

        suspend fun callWithModel(model: String): String {
            return try {
                withTimeout(REQUEST_TIMEOUT_MS) {
                    client.generateContent(
                        model, systemPrompt, prompt, temperature,
                        topP, topK, maxTokens, presencePenalty, frequencyPenalty,
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw com.theveloper.pixelplay.data.ai.provider.AiProviderSupport.createException(
                    providerName = provider.displayName,
                    statusCode = null,
                    transportMessage = "Request timed out after ${REQUEST_TIMEOUT_MS / 1000}s. The model may be overloaded.",
                    responseBody = null,
                    requestedModel = model
                )
            }
        }

        return try {
            val response = callWithModel(requestedModel)
            GenerationResult(response, requestedModel)
        } catch (e: Exception) {
            val failure = com.theveloper.pixelplay.data.ai.provider.AiProviderSupport.wrapThrowable(
                provider.displayName, e, requestedModel
            )

            val recoveredModel = recoverModelIfNeeded(
                provider, apiKey, requestedModel, client, failure
            ) ?: throw failure

            val response = callWithModel(recoveredModel)
            GenerationResult(response, recoveredModel)
        }
    }

    private suspend fun recoverModelIfNeeded(
        provider: AiProvider,
        apiKey: String,
        requestedModel: String,
        client: com.theveloper.pixelplay.data.ai.provider.AiClient,
        failure: com.theveloper.pixelplay.data.ai.provider.AiProviderException
    ): String? {
        if (!failure.isModelUnavailable()) return null

        val availableModels = runCatching { client.getAvailableModels(apiKey) }.getOrDefault(emptyList())
        val recoveredModel = com.theveloper.pixelplay.data.ai.provider.AiProviderSupport.selectRecoveryModel(
            currentModel = requestedModel,
            defaultModel = client.getDefaultModel(),
            availableModels = availableModels
        ) ?: return null

        setModel(provider, recoveredModel)
        return recoveredModel
    }

    /**
     * Where the next request goes: the phone's own AI (the default) or the cloud assistant the user
     * turned on. Features with their own on-device path (playlists, lyric translation, Taizo) ask
     * this first.
     */
    suspend fun currentRoute(): AiRoute {
        val providerName = preferencesRepo.aiProvider.first()
        if (AiProvider.fromString(providerName) != AiProvider.ON_DEVICE) {
            return AiRoute.Cloud(AiProvider.fromString(providerName))
        }
        return AiRoute.OnDevice(localAi.engine())
    }

    /** The user's Temperature when they moved it off the default, else [featureDefault]. */
    suspend fun temperatureFor(featureDefault: Float): Float {
        val stored = preferencesRepo.aiTemperature.first()
        return if (stored == 0.7f) featureDefault else stored
    }

    /**
     * One prompt in, one answer out, on the current route.
     *
     * On-device: the compact [OnDevicePrompts] instruction replaces the layered cloud prompt, the
     * prompt is clamped to the on-device input budget, only Temperature is taken from the
     * preferences (Top P, Top K, penalties and Max Output Tokens are cloud knobs), and there is no
     * provider chain at all: an on-device failure is thrown as [com.theveloper.pixelplay.data.ai.local.OnDeviceAiException],
     * never retried on a cloud provider. [maxOutputTokens] sizes the on-device answer; cloud calls
     * keep the user's Max Output Tokens.
     */
    suspend fun generateContent(
        prompt: String,
        type: AiSystemPromptType = AiSystemPromptType.GENERAL,
        temperature: Float = 0.7f,
        context: String = "",
        maxOutputTokens: Int? = null,
    ): String {
        val route = currentRoute()
        if (route is AiRoute.OnDevice) {
            return generateOnDevice(prompt, type, temperature, context, maxOutputTokens)
        }

        val params = getGenerationParams()
        val effectiveTemperature = if (params.temperature == 0.7f) {
            if (temperature == 0.7f) {
                when (type) {
                    AiSystemPromptType.METADATA -> 0.1f
                    AiSystemPromptType.MOOD_ANALYSIS -> 0.2f
                    AiSystemPromptType.TAGGING -> 0.4f
                    AiSystemPromptType.PLAYLIST, AiSystemPromptType.DAILY_MIX -> 0.6f
                    AiSystemPromptType.PERSONA -> 0.85f
                    AiSystemPromptType.GENERAL -> 0.7f
                    AiSystemPromptType.TAIZO_CHAT -> 0.8f
                    AiSystemPromptType.GREETING -> 0.75f
                }
            } else temperature
        } else params.temperature

        val userProviderStr = preferencesRepo.aiProvider.first()
        val userProvider = AiProvider.fromString(userProviderStr)

        val basePersona = getBasePersona(userProvider)
        val combinedSystemPrompt = promptEngine.buildPrompt(basePersona, type, context)

        val hash = (userProvider.name + combinedSystemPrompt + prompt).sha256()

        cacheDao.getCache(hash)?.let { cached ->
            val age = System.currentTimeMillis() - cached.timestamp
            if (age < CACHE_TTL_MS) {
                return cached.responseJson
            }
        }

        val providersToTry = AiRouteResolver.cloudChain(userProvider)
        val failedProviders = mutableListOf<String>()
        val now = System.currentTimeMillis()

        for (provider in providersToTry) {
            val cooldownExpiry = providerCooldowns[provider] ?: 0L
            if (now < cooldownExpiry) {
                failedProviders.add("${provider.name}: on cooldown (${((cooldownExpiry - now) / 1000)}s remaining)")
                continue
            }

            try {
                val apiKey = getApiKey(provider)
                // ON_DEVICE (and Ollama with no local-server auth) legitimately have no key —
                // only bail here for providers that actually need one.
                if (apiKey.isBlank() && provider.requiresApiKey) {
                    failedProviders.add("${provider.name}: no API key configured")
                    continue
                }

                val providerPersona = getBasePersona(provider)
                val finalSystemPrompt = promptEngine.buildPrompt(providerPersona, type, context)

                val result = generateWithRecovery(
                    provider = provider,
                    apiKey = apiKey,
                    systemPrompt = finalSystemPrompt,
                    prompt = prompt,
                    temperature = effectiveTemperature,
                    topP = params.topP,
                    topK = params.topK,
                    maxTokens = params.maxTokens,
                    presencePenalty = params.presencePenalty,
                    frequencyPenalty = params.frequencyPenalty,
                )

                if (result.response.isBlank()) {
                    failedProviders.add("${provider.name}: returned empty response")
                    continue
                }

                val isThinkingModel = finalSystemPrompt.contains("think", true) || provider.name.contains("reasoning", true)
                val estimatedPromptTokens = (finalSystemPrompt.length + prompt.length) / 4
                val estimatedOutputTokens = result.response.length / 4
                val estimatedThoughtTokens = if (isThinkingModel) (estimatedOutputTokens * 1.5).toInt() else 0

                appScope.launch {
                    runCatching {
                        usageDao.insertUsage(
                            AiUsageEntity(
                                timestamp = now,
                                provider = provider.displayName,
                                model = result.modelUsed,
                                promptType = type.name,
                                promptTokens = estimatedPromptTokens,
                                outputTokens = estimatedOutputTokens,
                                thoughtTokens = estimatedThoughtTokens
                            )
                        )
                    }.onFailure { error ->
                        Timber.tag("AiHandler").e(error, "Failed to persist AI usage")
                    }
                }

                cacheDao.insert(AiCacheEntity(promptHash = hash, responseJson = result.response, timestamp = System.currentTimeMillis()))
                return result.response
            } catch (e: Exception) {
                // AI Optimization: Robust failover logic—if one provider fails, we log and try the next in the chain
                val failure = com.theveloper.pixelplay.data.ai.provider.AiProviderSupport.wrapThrowable(provider.displayName, e)
                Timber.tag("AiHandler").w(e, "Provider ${provider.name} failed: ${failure.message}")
                failedProviders.add("${provider.name}: ${failure.message ?: "Unknown error"}")
                // Trigger cooldown only on provider-level outages and account problems.
                if (failure.shouldCooldown()) {
                    providerCooldowns[provider] = now + COOLDOWN_DURATION_MS
                }
            }
        }
        
        // AI Integration: Bubble up a detailed, user-friendly error if all providers fail
        val errorMessage = when {
            failedProviders.all { it.contains("no API key") } ->
                "No API key configured. Go to Settings → AI Integration to set up your API key."
            
            failedProviders.all { it.contains("cooldown") } ->
                "All AI providers are on cooldown after recent errors. Wait a few minutes and try again."
            
            failedProviders.size == 1 ->
                "AI generation failed: ${failedProviders.first()}"
            
            else ->
                "AI generation failed after trying ${failedProviders.size} providers:\n${failedProviders.joinToString("\n• ", prefix = "• ")}"
        }
        
        Timber.tag("AiHandler").e("All providers failed. Details: %s", failedProviders.joinToString(" | "))
        throw Exception(errorMessage)
    }

    private suspend fun generateOnDevice(
        prompt: String,
        type: AiSystemPromptType,
        temperature: Float,
        context: String,
        maxOutputTokens: Int?,
    ): String {
        val persona = getBasePersona(AiProvider.ON_DEVICE)
        val instruction = OnDevicePrompts.instructionFor(type, persona, maxOutputTokens)
        val maxOut = (maxOutputTokens ?: OnDevicePrompts.maxOutputFor(type))
            .coerceIn(1, TokenBudget.MAX_OUTPUT_TOKENS)
        // The cloud digest/context layer is far too big for a 4k window; on-device features that
        // need context build their own compact prompt and pass it in [prompt].
        val fullPrompt = TokenBudget.clamp(
            if (context.isBlank()) prompt else "$context\n\n$prompt",
            TokenBudget.MAX_INPUT_TOKENS - TokenBudget.estimate(instruction),
        )
        val effectiveTemperature = temperatureFor(temperature)
        val engine = localAi.engine()

        val hash = (engine.routeId + type.name + maxOut + effectiveTemperature + instruction + fullPrompt).sha256()
        cacheDao.getCache(hash)?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) return cached.responseJson
        }

        val startedAt = System.currentTimeMillis()
        val answer = localAi.generate(
            LocalGenerationRequest(
                instruction = instruction,
                prompt = fullPrompt,
                temperature = effectiveTemperature,
                maxOutputTokens = maxOut,
            )
        )
        val response = AiResponseCleaner.cleanTextResponse(answer.text)

        appScope.launch {
            runCatching {
                usageDao.insertUsage(
                    AiUsageEntity(
                        timestamp = startedAt,
                        provider = answer.engine.usageLabel,
                        model = answer.engine.routeId,
                        promptType = type.name,
                        promptTokens = TokenBudget.estimate(instruction) + TokenBudget.estimate(fullPrompt),
                        outputTokens = TokenBudget.estimate(response),
                        thoughtTokens = 0
                    )
                )
            }.onFailure { error -> Timber.tag("AiHandler").e(error, "Failed to persist AI usage") }
        }
        // Only cache under the engine that was asked: when the downloaded model failed to load and
        // Gemini Nano stood in, the next request should try the downloaded model again.
        if (answer.engine == engine) {
            cacheDao.insert(AiCacheEntity(promptHash = hash, responseJson = response, timestamp = System.currentTimeMillis()))
        }
        return response
    }
}

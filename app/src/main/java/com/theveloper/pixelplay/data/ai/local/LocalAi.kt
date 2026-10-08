package com.theveloper.pixelplay.data.ai.local

import com.theveloper.pixelplay.data.ai.provider.AiProvider
import com.theveloper.pixelplay.data.ai.provider.AiProviderSupport
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Which on-device engine answers. [routeId] goes into the AI cache key so their answers never mix. */
enum class LocalEngineId(val routeId: String, val usageLabel: String) {
    NANO("NANO", "On-device (Gemini Nano)"),
    GEMMA("GEMMA4E2B", "On-device (Gemma 4)"),
}

/** Where an AI request goes. */
sealed interface AiRoute {
    data class OnDevice(val engine: LocalEngineId) : AiRoute
    data class Cloud(val provider: AiProvider) : AiRoute
}

/** The routing rules, pure (AiRouteResolverTest). */
object AiRouteResolver {
    fun resolve(
        providerName: String,
        useDownloadedModel: Boolean,
        downloadedModelReady: Boolean,
        downloadedModelSupported: Boolean = true,
    ): AiRoute {
        val provider = AiProvider.fromString(providerName)
        if (provider != AiProvider.ON_DEVICE) return AiRoute.Cloud(provider)
        val gemma = useDownloadedModel && downloadedModelReady && downloadedModelSupported
        return AiRoute.OnDevice(if (gemma) LocalEngineId.GEMMA else LocalEngineId.NANO)
    }

    /**
     * The cloud fallback chain for a cloud selection: the selected provider first, then the other
     * cloud providers that have keys (unchanged behaviour). On-device is never in it — and an
     * on-device selection never reaches a chain at all: no automatic fallback to the cloud.
     */
    fun cloudChain(primary: AiProvider): List<AiProvider> =
        AiProviderSupport.buildProviderChain(primary).filter { it != AiProvider.ON_DEVICE }
}

/**
 * The on-device AI facade: picks the engine per request and never leaves the phone.
 *
 * - Gemma (the downloaded model) when "Use downloaded AI model" is on and the verified file is
 *   there; Gemini Nano otherwise.
 * - If Gemma fails to load (MODEL_LOAD_FAILED: corrupt file, out of memory), that request is
 *   answered by Gemini Nano instead and the reason shows under the model row in Settings
 *   (owner decision 3). There is never a fallback to a cloud provider.
 */
@Singleton
class LocalAi @Inject constructor(
    private val preferences: AiPreferencesRepository,
    private val nano: GeminiNanoEngine,
    private val gemma: GemmaLiteRtEngine,
    private val downloadedModel: DownloadedModelManager,
) {
    data class Answer(val text: String, val engine: LocalEngineId)

    suspend fun engine(): LocalEngineId {
        val wanted = preferences.aiDownloadedModelEnabled.first()
        if (!wanted || !gemma.isSupportedDevice) return LocalEngineId.NANO
        val ready = withContext(Dispatchers.IO) { downloadedModel.readyModelPath() != null }
        return if (ready) LocalEngineId.GEMMA else LocalEngineId.NANO
    }

    suspend fun generate(request: LocalGenerationRequest): Answer {
        if (engine() == LocalEngineId.GEMMA) {
            val path = withContext(Dispatchers.IO) { downloadedModel.readyModelPath() }
            if (path != null) {
                try {
                    return Answer(gemma.generate(path, request), LocalEngineId.GEMMA)
                } catch (e: OnDeviceAiException) {
                    if (e.failure != OnDeviceFailure.MODEL_LOAD_FAILED) throw e
                    Timber.tag(TAG).w(e, "Downloaded model failed to load; answering with Gemini Nano")
                }
            }
        }
        return Answer(nano.generate(request), LocalEngineId.NANO)
    }

    /** Loads whichever engine the next request will use (an AI sheet just opened). */
    suspend fun prewarm() {
        runCatching {
            if (engine() == LocalEngineId.GEMMA) {
                val path = withContext(Dispatchers.IO) { downloadedModel.readyModelPath() } ?: return
                gemma.prewarm(path)
            } else {
                nano.prewarm()
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    private companion object {
        const val TAG = "LocalAi"
    }
}

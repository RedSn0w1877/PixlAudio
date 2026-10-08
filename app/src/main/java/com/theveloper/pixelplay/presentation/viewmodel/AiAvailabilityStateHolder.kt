package com.theveloper.pixelplay.presentation.viewmodel

import androidx.annotation.StringRes
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.ai.local.AiRoute
import com.theveloper.pixelplay.data.ai.local.DownloadedModelManager
import com.theveloper.pixelplay.data.ai.local.DownloadedModelState
import com.theveloper.pixelplay.data.ai.local.GeminiNanoEngine
import com.theveloper.pixelplay.data.ai.local.GemmaLiteRtEngine
import com.theveloper.pixelplay.data.ai.local.LocalEngineId
import com.theveloper.pixelplay.data.ai.local.NanoStatus
import com.theveloper.pixelplay.data.ai.local.OnDeviceFailure
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import com.theveloper.pixelplay.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether AI features can run right now, and why not. */
sealed interface AiAvailability {
    /** Not known yet (Gemini Nano's status hasn't been asked this process). Treated as usable. */
    data object Checking : AiAvailability
    data class Ready(val route: AiRoute) : AiAvailability
    /** Gemini Nano is supported but Android hasn't downloaded it yet; asking starts it. */
    data object NeedsNanoDownload : AiAvailability
    data class Preparing(val bytesDownloaded: Long, val bytesTotal: Long?) : AiAvailability
    /** A cloud assistant is on but its key (or Ollama/Custom base URL) is missing. */
    data class NeedsCloudSetup(val provider: AiProvider) : AiAvailability
    data class Unavailable(val failure: OnDeviceFailure) : AiAvailability

    /** Whether an AI entry point should open. Checking opens too: the request then says exactly what's wrong. */
    val isUsable: Boolean get() = this is Ready || this is Checking

    /** What to tell the user when [isUsable] is false; null when it is usable. */
    @get:StringRes
    val reasonRes: Int?
        get() = when (this) {
            Checking, is Ready -> null
            NeedsNanoDownload, is Preparing -> R.string.ai_availability_preparing
            is NeedsCloudSetup -> R.string.ai_availability_needs_cloud_setup
            is Unavailable -> when (failure) {
                OnDeviceFailure.NOT_SUPPORTED -> R.string.ai_availability_not_supported
                else -> failure.messageRes
            }
        }
}

/**
 * Whether AI features can run (Library "With AI", the playlist sheet, Daily Mix's sparkle, the
 * home greeting). It replaces PlayerViewModel's 12-way API-key combine, which said `false` for
 * on-device and so locked on-device users out of every AI entry point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AiAvailabilityStateHolder @Inject constructor(
    private val preferences: AiPreferencesRepository,
    private val nano: GeminiNanoEngine,
    private val gemma: GemmaLiteRtEngine,
    private val downloadedModel: DownloadedModelManager,
    @AppScope private val appScope: CoroutineScope,
) {
    private data class CloudSetup(val provider: AiProvider, val apiKey: String, val baseUrl: String)

    private val cloudSetup = preferences.aiProvider.flatMapLatest { name ->
        val provider = AiProvider.fromString(name)
        combine(preferences.getApiKey(provider), preferences.getBaseUrl(provider)) { key, url ->
            CloudSetup(provider, key, url)
        }
    }

    val availability: StateFlow<AiAvailability> = combine(
        cloudSetup,
        preferences.aiDownloadedModelEnabled,
        nano.status,
        downloadedModel.state,
    ) { cloud, useDownloaded, nanoStatus, modelState ->
        AiAvailabilityResolver.resolve(
            provider = cloud.provider,
            apiKey = cloud.apiKey,
            baseUrl = cloud.baseUrl,
            useDownloadedModel = useDownloaded && gemma.isSupportedDevice,
            downloadedModelReady = modelState is DownloadedModelState.Ready,
            nanoStatus = nanoStatus,
        )
    }
        .onStart { appScope.launch { nano.refreshIfUnknown() } }
        .distinctUntilChanged()
        .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), AiAvailability.Checking)

    /**
     * The availability now. AICore is asked again unless Gemini Nano is already known to be ready,
     * and the downloaded model is checked on disk: at app start (the home greeting) the observed
     * [DownloadedModelManager.state] hasn't read the file yet.
     */
    suspend fun current(): AiAvailability {
        val nanoStatus = nano.refreshUnlessReady()
        val provider = AiProvider.fromString(preferences.aiProvider.first())
        return AiAvailabilityResolver.resolve(
            provider = provider,
            apiKey = preferences.getApiKey(provider).first(),
            baseUrl = preferences.getBaseUrl(provider).first(),
            useDownloadedModel = preferences.aiDownloadedModelEnabled.first() && gemma.isSupportedDevice,
            downloadedModelReady = withContext(Dispatchers.IO) { downloadedModel.readyModelPath() != null },
            nanoStatus = nanoStatus,
        )
    }

    /** Re-asks AICore (Settings opened, an AI entry was tapped). */
    fun refresh() {
        appScope.launch { nano.refresh() }
    }

    /** Asks Android to download Gemini Nano ("Get ready"). */
    fun prepareOnDevice() {
        nano.prepareInBackground()
    }
}

/** The availability rules, pure (AiAvailabilityResolverTest). */
object AiAvailabilityResolver {
    fun resolve(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        useDownloadedModel: Boolean,
        downloadedModelReady: Boolean,
        nanoStatus: NanoStatus,
    ): AiAvailability {
        if (provider != AiProvider.ON_DEVICE) {
            val missingKey = provider.requiresApiKey && apiKey.isBlank()
            val missingUrl = provider.hasConfigurableUrl && baseUrl.isBlank()
            return if (missingKey || missingUrl) AiAvailability.NeedsCloudSetup(provider)
            else AiAvailability.Ready(AiRoute.Cloud(provider))
        }
        // The downloaded model answers by itself, whatever Gemini Nano's state.
        if (useDownloadedModel && downloadedModelReady) {
            return AiAvailability.Ready(AiRoute.OnDevice(LocalEngineId.GEMMA))
        }
        return when (nanoStatus) {
            NanoStatus.Unknown -> AiAvailability.Checking
            NanoStatus.Ready -> AiAvailability.Ready(AiRoute.OnDevice(LocalEngineId.NANO))
            NanoStatus.NeedsDownload -> AiAvailability.NeedsNanoDownload
            is NanoStatus.Downloading -> AiAvailability.Preparing(nanoStatus.bytesDownloaded, nanoStatus.bytesTotal)
            is NanoStatus.Unavailable ->
                // No Nano and the switch is on without a finished download: say what's missing.
                if (useDownloadedModel) AiAvailability.Unavailable(OnDeviceFailure.MODEL_MISSING)
                else AiAvailability.Unavailable(nanoStatus.failure)
        }
    }
}

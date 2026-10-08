package com.theveloper.pixelplay.data.ai.local

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Gemini Nano's state on this phone, from AICore. */
sealed interface NanoStatus {
    /** Not asked yet this process. */
    data object Unknown : NanoStatus
    data object Ready : NanoStatus
    /** Supported, but Android hasn't downloaded the model yet ("Get ready" in Settings). */
    data object NeedsDownload : NanoStatus
    data class Downloading(val bytesDownloaded: Long, val bytesTotal: Long?) : NanoStatus
    data class Unavailable(val failure: OnDeviceFailure) : NanoStatus
}

/**
 * Gemini Nano through AICore (ML Kit GenAI Prompt API) — the default engine for every AI feature.
 *
 * - One [GenerativeModel] per process, created on first use off the main thread.
 * - Requests are serialized with a [Mutex]: AICore answers concurrent calls from the same app
 *   with BUSY, so queueing them here is both faster and gives a cancelled request (a closed sheet,
 *   a Taizo time-out) a cancellable wait instead of a failed call.
 * - Foreground only: AICore refuses apps that aren't on screen (BACKGROUND_USE_BLOCKED). Never
 *   call this from a Worker (AiWorker refuses the on-device route for that reason).
 * - Thinking, prefix caching (experimental) and structured output (alpha) are deliberately left
 *   out of v1: the features parse plain text leniently instead.
 */
@Singleton
class GeminiNanoEngine @Inject constructor() {

    private val _status = MutableStateFlow<NanoStatus>(NanoStatus.Unknown)
    val status: StateFlow<NanoStatus> = _status.asStateFlow()

    private val clientLock = Any()
    @Volatile private var client: GenerativeModel? = null
    @Volatile private var systemPromptSupported: Boolean? = null
    private val generateMutex = Mutex()
    private val downloadMutex = Mutex()

    private fun model(): GenerativeModel {
        client?.let { return it }
        return synchronized(clientLock) {
            client ?: Generation.getClient().also { client = it }
        }
    }

    /** Asks AICore for the current status. Cheap; safe to call whenever Settings or an AI entry opens. */
    suspend fun refresh(): NanoStatus = withContext(Dispatchers.Default) {
        val current = _status.value
        if (current is NanoStatus.Downloading && downloadMutex.isLocked) return@withContext current
        val next = try {
            when (model().checkStatus()) {
                FeatureStatus.AVAILABLE -> NanoStatus.Ready
                FeatureStatus.DOWNLOADABLE -> NanoStatus.NeedsDownload
                FeatureStatus.DOWNLOADING -> NanoStatus.Downloading(0L, null)
                else -> NanoStatus.Unavailable(OnDeviceFailure.NOT_SUPPORTED)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            NanoStatus.Unavailable(OnDeviceErrorMapper.fromGenAi(e).failure)
        } catch (t: Throwable) {
            // No AICore at all (non-Pixel phones, emulators): the client or the binder call throws.
            Timber.tag(TAG).w(t, "Gemini Nano status check failed")
            NanoStatus.Unavailable(OnDeviceFailure.NOT_SUPPORTED)
        }
        _status.value = next
        next
    }

    suspend fun refreshIfUnknown(): NanoStatus =
        _status.value.takeUnless { it is NanoStatus.Unknown } ?: refresh()

    /**
     * Has AICore download the model (it decides when; Wi-Fi and charging help). Progress shows in
     * [status]. Returns once the download ends, either way.
     */
    suspend fun prepare() {
        if (downloadMutex.isLocked) return
        downloadMutex.withLock {
            withContext(Dispatchers.Default) {
                var total: Long? = null
                try {
                    model().download().collect { update ->
                        when (update) {
                            is DownloadStatus.DownloadStarted -> {
                                total = update.bytesToDownload.takeIf { it > 0L }
                                _status.value = NanoStatus.Downloading(0L, total)
                            }
                            is DownloadStatus.DownloadProgress ->
                                _status.value = NanoStatus.Downloading(update.totalBytesDownloaded, total)
                            is DownloadStatus.DownloadCompleted -> _status.value = NanoStatus.Ready
                            is DownloadStatus.DownloadFailed -> {
                                Timber.tag(TAG).w(update.e, "Gemini Nano download failed")
                                _status.value = NanoStatus.Unavailable(OnDeviceErrorMapper.fromGenAi(update.e).failure)
                            }
                            else -> Unit
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GenAiException) {
                    _status.value = NanoStatus.Unavailable(OnDeviceErrorMapper.fromGenAi(e).failure)
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "Gemini Nano download threw")
                    _status.value = NanoStatus.Unavailable(OnDeviceFailure.OTHER)
                }
            }
        }
        if (_status.value is NanoStatus.Downloading) refresh()
    }

    /** Loads the model ahead of a request (when an AI sheet opens). Best effort. */
    suspend fun prewarm() {
        if (refreshIfUnknown() != NanoStatus.Ready) return
        withContext(Dispatchers.Default) {
            runCatching { model().warmup() }
                .onFailure { if (it is CancellationException) throw it else Timber.tag(TAG).d(it, "warmup failed") }
        }
    }

    suspend fun generate(request: LocalGenerationRequest): String {
        when (val status = refreshIfUnknown()) {
            NanoStatus.Ready, NanoStatus.Unknown -> Unit
            NanoStatus.NeedsDownload, is NanoStatus.Downloading -> throw OnDeviceAiException(OnDeviceFailure.PREPARING)
            is NanoStatus.Unavailable -> throw OnDeviceAiException(status.failure)
        }
        return generateMutex.withLock {
            withContext(Dispatchers.Default) {
                try {
                    val model = model()
                    val folded = !(systemPromptSupported ?: runCatching { model.isSystemPromptAvailable() }
                        .getOrDefault(false)
                        .also { systemPromptSupported = it })
                    val built = buildRequest(request, foldInstruction = folded)
                    val response = model.generateContent(built)
                    val text = response.candidates.firstOrNull()?.text.orEmpty().trim()
                    if (text.isEmpty()) throw OnDeviceAiException(OnDeviceFailure.BLOCKED)
                    text
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OnDeviceAiException) {
                    throw e
                } catch (e: GenAiException) {
                    val mapped = OnDeviceErrorMapper.fromGenAi(e)
                    if (mapped.failure == OnDeviceFailure.NOT_SUPPORTED || mapped.failure == OnDeviceFailure.NEEDS_UPDATE) {
                        _status.value = NanoStatus.Unavailable(mapped.failure)
                    }
                    throw mapped
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "Gemini Nano generation failed")
                    throw OnDeviceAiException(OnDeviceFailure.OTHER, cause = t)
                }
            }
        }
    }

    private fun buildRequest(request: LocalGenerationRequest, foldInstruction: Boolean): GenerateContentRequest {
        // System instructions need nano-v3 or later; older Nano builds get the instruction as the
        // first paragraph of the prompt instead, which they follow almost as well.
        val builder = if (foldInstruction || request.instruction.isBlank()) {
            val text = if (request.instruction.isBlank()) request.prompt else "${request.instruction}\n\n${request.prompt}"
            GenerateContentRequest.Builder(TextPart(text))
        } else {
            GenerateContentRequest.Builder(SystemInstruction(request.instruction), TextPart(request.prompt))
        }
        // Kept within 0..1 for the small model; the Settings slider goes up to 2 for cloud models.
        builder.temperature = request.temperature.coerceIn(0f, 1f)
        builder.topK = request.topK.coerceIn(1, 64)
        builder.maxOutputTokens = request.maxOutputTokens.coerceIn(1, TokenBudget.MAX_OUTPUT_TOKENS)
        request.seed?.let { builder.seed = it }
        return builder.build()
    }

    private companion object {
        const val TAG = "GeminiNano"
    }
}

/** One on-device request, engine-agnostic. */
data class LocalGenerationRequest(
    val instruction: String,
    val prompt: String,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val maxOutputTokens: Int = OnDevicePrompts.GENERAL_MAX_OUTPUT,
    val seed: Int? = null,
    /** Constrained decoding (Gemma only; Nano ignores it and the caller parses leniently). */
    val regex: String? = null,
)

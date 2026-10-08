package com.theveloper.pixelplay.data.ai.local

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.theveloper.pixelplay.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The optional downloaded model (Gemma 4 E2B, "Use downloaded AI model") on LiteRT-LM.
 *
 * One engine per process. The previous MediaPipe path built a new client — and so loaded the
 * whole model again — on every request and never closed the old one, leaking a native
 * LlmInference per call. Here the engine is loaded once, reused, and released:
 * - after [IDLE_RELEASE_MS] without a request (it holds ~0.8-1.5 GB),
 * - when the app goes to the background (onTrimMemory UI_HIDDEN),
 * - when the switch goes off or the model is deleted.
 *
 * Loading tries the GPU backend first and the CPU backend second. Any loading failure
 * (corrupt file, out of memory, no .so for a 32-bit-only CPU) is MODEL_LOAD_FAILED, which
 * [LocalAi] answers with Gemini Nano for that request — never with the cloud.
 */
@Singleton
class GemmaLiteRtEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private var idleJob: Job? = null

    private val _lastLoadError = MutableStateFlow<String?>(null)
    /** Why the last load failed (shown under the model row), or null. */
    val lastLoadError: StateFlow<String?> = _lastLoadError.asStateFlow()

    /** LiteRT-LM ships arm64-v8a and x86_64 libraries only. */
    val isSupportedDevice: Boolean
        get() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    suspend fun generate(modelPath: String, request: LocalGenerationRequest): String = mutex.withLock {
        val loaded = ensureLoaded(modelPath)
        try {
            runGeneration(loaded, request)
        } finally {
            scheduleIdleRelease()
        }
    }

    /** Loads the engine ahead of a request (when an AI sheet opens). Best effort. */
    suspend fun prewarm(modelPath: String) {
        mutex.withLock {
            runCatching { ensureLoaded(modelPath) }
                .onFailure { if (it is CancellationException) throw it }
            scheduleIdleRelease()
        }
    }

    /** Frees the engine now (switch off, model deleted, app in the background). */
    fun release() {
        appScope.launch {
            mutex.withLock { closeEngineLocked() }
        }
    }

    private suspend fun ensureLoaded(modelPath: String): Engine {
        engine?.let { if (loadedPath == modelPath) return it }
        closeEngineLocked()
        if (!isSupportedDevice) throw OnDeviceAiException(OnDeviceFailure.MODEL_LOAD_FAILED)
        return withContext(Dispatchers.Default) {
            val created = tryLoad(modelPath, Backend.GPU()) ?: tryLoad(modelPath, Backend.CPU())
                ?: throw OnDeviceAiException(OnDeviceFailure.MODEL_LOAD_FAILED)
            engine = created
            loadedPath = modelPath
            _lastLoadError.value = null
            created
        }
    }

    private fun tryLoad(modelPath: String, backend: Backend): Engine? {
        val candidate = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = ENGINE_MAX_TOKENS,
                cacheDir = context.cacheDir.path,
            )
        )
        return try {
            candidate.initialize()
            Timber.tag(TAG).i("Gemma loaded on %s", backend.name)
            candidate
        } catch (t: Throwable) {
            // Throwable on purpose: UnsatisfiedLinkError and OutOfMemoryError are Errors.
            Timber.tag(TAG).w(t, "Gemma failed to load on %s", backend.name)
            _lastLoadError.value = t.message ?: t::class.java.simpleName
            runCatching { candidate.close() }
            null
        }
    }

    private suspend fun runGeneration(loaded: Engine, request: LocalGenerationRequest): String {
        return try {
            generateOnce(loaded, request, request.regex)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (request.regex != null) {
                // Constrained decoding is new in LiteRT-LM 0.x and unproven with Gemma 4 on device;
                // a request that fails with it gets one plain try, and the caller parses leniently.
                Timber.tag(TAG).w(t, "Constrained decoding failed, retrying without it")
                try {
                    generateOnce(loaded, request, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (t2: Throwable) {
                    throw OnDeviceErrorMapper.fromLiteRt(t2, whileLoading = false)
                }
            } else {
                throw OnDeviceErrorMapper.fromLiteRt(t, whileLoading = false)
            }
        }
    }

    private suspend fun generateOnce(loaded: Engine, request: LocalGenerationRequest, regex: String?): String {
        val maxOut = request.maxOutputTokens.coerceIn(1, TokenBudget.MAX_OUTPUT_TOKENS)
        val conversation = withContext(Dispatchers.Default) {
            loaded.createConversation(
                ConversationConfig(
                    systemInstruction = request.instruction.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                    samplerConfig = SamplerConfig(
                        topK = request.topK.coerceAtLeast(1),
                        topP = request.topP.coerceIn(0f, 1f).toDouble(),
                        temperature = request.temperature.coerceAtLeast(0f).toDouble(),
                        seed = request.seed ?: 0,
                    ),
                    maxOutputToken = maxOut,
                    enableResponseFormat = regex != null,
                )
            )
        }
        try {
            // sendMessage blocks in native code. It runs on its own child so a cancelled caller
            // (closed sheet, Taizo time-out) can stop the decode with cancelProcess() instead of
            // waiting for the whole answer.
            val message = coroutineScope {
                val work = async(Dispatchers.Default) {
                    conversation.sendMessage(
                        request.prompt,
                        maxOutputToken = maxOut,
                        responseFormat = regex?.let { ResponseFormat.regex(it) },
                    )
                }
                try {
                    work.await()
                } catch (e: CancellationException) {
                    runCatching { conversation.cancelProcess() }
                    throw e
                }
            }
            val text = message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
                .trim()
            if (text.isEmpty()) throw OnDeviceAiException(OnDeviceFailure.BLOCKED)
            return text
        } finally {
            runCatching { conversation.close() }
        }
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = appScope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock { closeEngineLocked() }
        }
    }

    private fun closeEngineLocked() {
        val current = engine ?: return
        engine = null
        loadedPath = null
        runCatching { current.close() }
            .onFailure { Timber.tag(TAG).w(it, "Closing the Gemma engine failed") }
        Timber.tag(TAG).d("Gemma engine released")
    }

    private companion object {
        const val TAG = "GemmaLiteRt"
        /** Input + output; the prompts are sized for this (see [TokenBudget]). */
        const val ENGINE_MAX_TOKENS = 4_096
        const val IDLE_RELEASE_MS = 90_000L
    }
}
